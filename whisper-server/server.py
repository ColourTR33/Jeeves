"""
Jeeves Whisper Transcription Server — Multi-Backend with Auto-Detection.

Automatically selects the optimal transcription backend based on platform:

  macOS (Apple Silicon) → MLX Whisper (Neural Engine / Metal)
  Windows + NVIDIA GPU  → faster-whisper CUDA
  Windows CPU (Intel)   → faster-whisper CPU INT8
  Linux + NVIDIA GPU    → faster-whisper CUDA
  Linux ARM             → whisper.cpp (subprocess)

All backends use VAD (Voice Activity Detection) for natural sentence
boundary splitting — no manual chunking needed.

Usage:
    pip install -r requirements.txt
    python server.py

Environment variables:
    WHISPER_MODEL    — Model size: tiny, base, small, medium, large-v3, turbo (default: small)
    WHISPER_PORT     — Server port (default: 8178)
    WHISPER_BACKEND  — Force a backend: mlx, cuda, cpu, whisper_cpp (default: auto-detect)
    WHISPER_VERBOSE  — Set to "1" for verbose/debug logging (default: "0")
    WHISPER_IDLE_UNLOAD — Minutes of idle before unloading model (default: 0 = never unload)
    WHISPER_MAX_FILE_MB — Maximum upload file size in MB (default: 200)
"""
import os
import sys
import gc
import time
import signal
import platform
import tempfile
import subprocess
import shutil
import logging
import asyncio
import threading
from pathlib import Path
from contextlib import asynccontextmanager

from fastapi import FastAPI, UploadFile, File, Form, HTTPException, Request
from fastapi.responses import JSONResponse, PlainTextResponse
from starlette.middleware.base import BaseHTTPMiddleware
import uvicorn

MODEL_SIZE = os.environ.get("WHISPER_MODEL", "small")
FORCED_BACKEND = os.environ.get("WHISPER_BACKEND", "").lower()
VERBOSE = os.environ.get("WHISPER_VERBOSE", "0") == "1"
IDLE_UNLOAD_MINUTES = int(os.environ.get("WHISPER_IDLE_UNLOAD", "0"))
MAX_FILE_MB = int(os.environ.get("WHISPER_MAX_FILE_MB", "200"))

# ─── Logging ─────────────────────────────────────────────────────────────────────

log_level = logging.DEBUG if VERBOSE else logging.INFO
logging.basicConfig(
    level=log_level,
    format="[%(asctime)s][%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
    stream=sys.stdout
)
logger = logging.getLogger("whisper-server")

# Suppress noisy uvicorn access logs unless verbose
if not VERBOSE:
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)


# ─── Concurrency Control ─────────────────────────────────────────────────────────

# Semaphore to ensure only ONE transcription runs at a time (memory constrained)
_transcription_lock = asyncio.Lock()
_request_queue_size = 0
_last_request_time = time.time()


# ─── Backend Detection ───────────────────────────────────────────────────────────

def detect_backend() -> str:
    """Auto-detect the best transcription backend for this platform."""
    if FORCED_BACKEND in ("mlx", "cuda", "cpu", "whisper_cpp"):
        return FORCED_BACKEND

    system = platform.system()
    machine = platform.machine().lower()

    # macOS Apple Silicon → MLX
    if system == "Darwin" and machine == "arm64":
        try:
            import mlx_whisper
            return "mlx"
        except ImportError:
            pass  # Fall through to faster-whisper

    # Check for NVIDIA GPU (CUDA)
    if system in ("Windows", "Linux"):
        try:
            import torch
            if torch.cuda.is_available():
                return "cuda"
        except ImportError:
            pass

    # Linux ARM without CUDA → whisper.cpp
    if system == "Linux" and ("arm" in machine or "aarch" in machine):
        if shutil.which("whisper-cpp") or shutil.which("whisper"):
            return "whisper_cpp"

    # Default: faster-whisper on CPU (works everywhere)
    return "cpu"


BACKEND = detect_backend()

# ─── Model Management (Memory-Aware) ─────────────────────────────────────────────

_model = None
_model_lock = threading.Lock()


def get_model():
    """Lazy-load the transcription model for the detected backend."""
    global _model, _last_request_time
    _last_request_time = time.time()

    with _model_lock:
        if _model is not None:
            return _model

        if BACKEND == "mlx":
            import mlx_whisper
            _model = "mlx"
            logger.info(f"MLX Whisper ready (model: {MODEL_SIZE})")

        elif BACKEND == "cuda":
            from faster_whisper import WhisperModel
            logger.info(f"Loading faster-whisper '{MODEL_SIZE}' on CUDA (GPU)...")
            _model = WhisperModel(MODEL_SIZE, device="cuda", compute_type="float16")
            logger.info("Model loaded on GPU.")

        elif BACKEND == "cpu":
            from faster_whisper import WhisperModel
            logger.info(f"Loading faster-whisper '{MODEL_SIZE}' on CPU (INT8)...")
            _model = WhisperModel(
                MODEL_SIZE,
                device="cpu",
                compute_type="int8",
                cpu_threads=min(os.cpu_count() or 4, 4),  # Limit threads to reduce memory
                num_workers=1  # Single worker to limit memory
            )
            logger.info("Model loaded on CPU (INT8, 4 threads).")

        elif BACKEND == "whisper_cpp":
            _model = "whisper_cpp"
            logger.info(f"Using whisper.cpp subprocess (model: {MODEL_SIZE})")

        return _model


def unload_model():
    """Unload the model to free memory."""
    global _model
    with _model_lock:
        if _model is not None and _model not in ("mlx", "whisper_cpp"):
            logger.info("Unloading model to free memory...")
            del _model
            _model = None
            gc.collect()
            logger.info("Model unloaded.")


async def idle_unloader():
    """Background task that unloads the model after IDLE_UNLOAD_MINUTES of inactivity."""
    if IDLE_UNLOAD_MINUTES <= 0:
        return
    idle_seconds = IDLE_UNLOAD_MINUTES * 60
    while True:
        await asyncio.sleep(60)  # Check every minute
        if _model is not None and (time.time() - _last_request_time) > idle_seconds:
            unload_model()


# ─── App Lifecycle ───────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup/shutdown lifecycle."""
    logger.info("Server starting up...")
    # Start idle unloader background task
    if IDLE_UNLOAD_MINUTES > 0:
        asyncio.create_task(idle_unloader())
        logger.info(f"Model will auto-unload after {IDLE_UNLOAD_MINUTES} min idle")
    yield
    # Shutdown: unload model
    unload_model()
    logger.info("Server shut down cleanly.")


app = FastAPI(title="Jeeves Whisper Server", lifespan=lifespan)


# ─── Middleware: Connection Error Handling ────────────────────────────────────────

class BrokenPipeMiddleware(BaseHTTPMiddleware):
    """
    Catch broken pipe / WinError 64 errors when client disconnects mid-response.
    These are harmless — the client simply closed the connection before we finished.
    Without this middleware, uvicorn crashes the whole server.
    """
    async def dispatch(self, request: Request, call_next):
        try:
            response = await call_next(request)
            return response
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError) as e:
            logger.warning(f"Client disconnected: {type(e).__name__} — {request.url.path}")
            return JSONResponse(
                status_code=499,  # Client Closed Request (nginx convention)
                content={"error": "client_disconnected"}
            )
        except OSError as e:
            # WinError 64: The specified network name is no longer available
            if hasattr(e, 'winerror') and e.winerror == 64:
                logger.warning(f"Client disconnected (WinError 64) — {request.url.path}")
                return JSONResponse(
                    status_code=499,
                    content={"error": "client_disconnected"}
                )
            raise


app.add_middleware(BrokenPipeMiddleware)


# ─── Backend Implementations ─────────────────────────────────────────────────────

def transcribe_faster_whisper(file_path: str, language: str):
    """Transcribe using faster-whisper (CPU or CUDA)."""
    model = get_model()
    segments, info = model.transcribe(
        file_path,
        language=language if language else None,
        beam_size=5,
        vad_filter=True,
        vad_parameters=dict(min_silence_duration_ms=500),
    )

    result_segments = []
    text_parts = []
    for segment in segments:
        result_segments.append({
            "start": round(segment.start, 2),
            "end": round(segment.end, 2),
            "text": segment.text.strip()
        })
        text_parts.append(segment.text.strip())

    return " ".join(text_parts), result_segments, info.language, info.duration


def transcribe_mlx(file_path: str, language: str):
    """Transcribe using MLX Whisper (macOS Apple Silicon)."""
    import mlx_whisper

    result = mlx_whisper.transcribe(
        file_path,
        path_or_hf_repo=f"mlx-community/whisper-{MODEL_SIZE}-mlx",
        language=language if language else None,
        verbose=False,
    )

    segments = []
    for seg in result.get("segments", []):
        segments.append({
            "start": round(seg["start"], 2),
            "end": round(seg["end"], 2),
            "text": seg["text"].strip()
        })

    return result["text"].strip(), segments, result.get("language", "en"), None


def transcribe_whisper_cpp(file_path: str, language: str):
    """Transcribe using whisper.cpp as a subprocess (Linux ARM)."""
    binary = shutil.which("whisper-cpp") or shutil.which("whisper") or shutil.which("main")
    if not binary:
        raise RuntimeError("whisper.cpp binary not found in PATH")

    model_path = os.environ.get("WHISPER_CPP_MODEL", f"models/ggml-{MODEL_SIZE}.bin")

    cmd = [
        binary,
        "-m", model_path,
        "-f", file_path,
        "-l", language or "en",
        "--output-json",
        "-t", str(min(os.cpu_count() or 4, 8)),
    ]

    result = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    if result.returncode != 0:
        raise RuntimeError(f"whisper.cpp failed: {result.stderr}")

    import json
    json_path = file_path + ".json"
    if not os.path.exists(json_path):
        return result.stdout.strip(), [], language or "en", None

    with open(json_path) as f:
        data = json.load(f)

    os.unlink(json_path)

    segments = []
    text_parts = []
    for seg in data.get("transcription", []):
        start = seg.get("timestamps", {}).get("from", "00:00:00.000")
        end = seg.get("timestamps", {}).get("to", "00:00:00.000")
        text = seg.get("text", "").strip()
        start_sec = _parse_timestamp(start)
        end_sec = _parse_timestamp(end)
        segments.append({"start": round(start_sec, 2), "end": round(end_sec, 2), "text": text})
        text_parts.append(text)

    return " ".join(text_parts), segments, language or "en", None


def _parse_timestamp(ts: str) -> float:
    """Parse HH:MM:SS.mmm to seconds."""
    try:
        parts = ts.split(":")
        h, m = int(parts[0]), int(parts[1])
        s = float(parts[2])
        return h * 3600 + m * 60 + s
    except (ValueError, IndexError):
        return 0.0


# ─── Routing ─────────────────────────────────────────────────────────────────────

def do_transcribe(file_path: str, language: str):
    """Route to the correct backend."""
    if BACKEND in ("cpu", "cuda"):
        return transcribe_faster_whisper(file_path, language)
    elif BACKEND == "mlx":
        return transcribe_mlx(file_path, language)
    elif BACKEND == "whisper_cpp":
        return transcribe_whisper_cpp(file_path, language)
    else:
        raise RuntimeError(f"Unknown backend: {BACKEND}")


# ─── API Endpoints ───────────────────────────────────────────────────────────────

@app.get("/")
def root():
    return {
        "status": "ok",
        "model": MODEL_SIZE,
        "backend": BACKEND,
        "platform": platform.system(),
        "model_loaded": _model is not None,
        "verbose": VERBOSE
    }


@app.get("/health")
def health_check():
    return {"status": "ok", "backend": BACKEND, "model_loaded": _model is not None}


@app.post("/v1/audio/transcriptions")
async def transcribe_endpoint(
    file: UploadFile = File(...),
    model: str = Form(default="whisper-small"),
    response_format: str = Form(default="verbose_json"),
    language: str = Form(default="en"),
):
    """OpenAI-compatible transcription endpoint with auto-detected backend."""
    global _request_queue_size

    # File size check (stream-friendly: read in chunks)
    max_bytes = MAX_FILE_MB * 1024 * 1024
    audio_bytes = await file.read()
    if len(audio_bytes) > max_bytes:
        raise HTTPException(
            status_code=413,
            detail=f"File too large ({len(audio_bytes) // 1024 // 1024}MB). Max: {MAX_FILE_MB}MB"
        )

    suffix = Path(file.filename).suffix if file.filename else ".wav"

    # Write to temp file (don't hold audio bytes in memory during transcription)
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(audio_bytes)
        tmp_path = tmp.name

    # Free the bytes from memory immediately
    file_size = len(audio_bytes)
    file_name = file.filename or "unknown"
    del audio_bytes
    gc.collect()

    # Acquire the transcription lock (one at a time to avoid OOM)
    _request_queue_size += 1
    queue_pos = _request_queue_size
    if queue_pos > 1:
        logger.info(f"Request queued (position {queue_pos}): {file_name}")

    try:
        async with _transcription_lock:
            _request_queue_size -= 1

            # Ensure model is loaded
            get_model()

            logger.info(f"[{BACKEND.upper()}] Transcribing: {file_name} ({file_size} bytes)")
            start_time = time.time()

            # Run transcription in thread pool to not block the event loop
            loop = asyncio.get_event_loop()
            text, segments, lang, duration = await loop.run_in_executor(
                None, do_transcribe, tmp_path, language
            )

            elapsed = time.time() - start_time
            logger.info(f"[{BACKEND.upper()}] Done: {len(segments)} segments, {len(text)} chars in {elapsed:.1f}s")

            if response_format == "text":
                return PlainTextResponse(text)
            elif response_format == "verbose_json":
                resp = {"text": text, "segments": segments, "language": lang}
                if duration is not None:
                    resp["duration"] = duration
                return JSONResponse(resp)
            else:
                return JSONResponse({"text": text})
    except Exception as e:
        logger.error(f"Transcription failed for {file_name}: {type(e).__name__}: {e}")
        raise HTTPException(status_code=500, detail=f"Transcription failed: {str(e)}")
    finally:
        # Always clean up the temp file
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


# Aliases for compatibility
@app.post("/v1/audio/inference")
async def inference(file: UploadFile = File(...), model: str = Form(default=""), response_format: str = Form(default="verbose_json"), language: str = Form(default="en")):
    return await transcribe_endpoint(file=file, model=model, response_format=response_format, language=language)

@app.post("/inference")
async def inference_root(file: UploadFile = File(...), model: str = Form(default=""), response_format: str = Form(default="verbose_json"), language: str = Form(default="en")):
    return await transcribe_endpoint(file=file, model=model, response_format=response_format, language=language)


@app.post("/v1/unload")
async def unload_model_endpoint():
    """Manually unload the model to free memory."""
    unload_model()
    return {"status": "ok", "message": "Model unloaded"}


@app.get("/v1/status")
def status():
    """Detailed server status for monitoring."""
    import psutil
    process = psutil.Process()
    mem = process.memory_info()
    return {
        "status": "ok",
        "backend": BACKEND,
        "model": MODEL_SIZE,
        "model_loaded": _model is not None,
        "verbose": VERBOSE,
        "memory_mb": round(mem.rss / 1024 / 1024, 1),
        "cpu_percent": process.cpu_percent(interval=0.1),
        "queue_size": _request_queue_size,
        "uptime_seconds": round(time.time() - _server_start_time, 0),
        "idle_unload_minutes": IDLE_UNLOAD_MINUTES,
    }


# ─── Main ────────────────────────────────────────────────────────────────────────

_server_start_time = time.time()

if __name__ == "__main__":
    port = int(os.environ.get("WHISPER_PORT", "8178"))

    print("=" * 60)
    print("Jeeves Whisper Server — Multi-Backend")
    print(f"  Platform:  {platform.system()} {platform.machine()}")
    print(f"  Backend:   {BACKEND}")
    print(f"  Model:     {MODEL_SIZE}")
    print(f"  Port:      {port}")
    print(f"  Verbose:   {VERBOSE}")
    print(f"  Max file:  {MAX_FILE_MB}MB")
    if IDLE_UNLOAD_MINUTES > 0:
        print(f"  Idle unload: {IDLE_UNLOAD_MINUTES} min")
    print("=" * 60)
    print()

    # Graceful shutdown on Ctrl+C / SIGTERM
    def handle_signal(signum, frame):
        logger.info("Received shutdown signal, cleaning up...")
        unload_model()
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    uvicorn.run(
        app,
        host="0.0.0.0",
        port=port,
        timeout_keep_alive=120,  # Keep connections alive longer to avoid broken pipe
        limit_concurrency=3,     # Max 3 concurrent connections (1 active + 2 waiting)
        limit_max_requests=1000, # Restart worker after 1000 requests (memory leak prevention)
        access_log=VERBOSE,      # Only show access logs in verbose mode
    )
