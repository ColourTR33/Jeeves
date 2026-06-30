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
    WHISPER_MODEL  — Model size: tiny, base, small, medium, large-v3, turbo (default: small)
    WHISPER_PORT   — Server port (default: 8178)
    WHISPER_BACKEND — Force a backend: mlx, cuda, cpu, whisper_cpp (default: auto-detect)
"""
import os
import sys
import platform
import tempfile
import subprocess
import shutil
from pathlib import Path

from fastapi import FastAPI, UploadFile, File, Form
from fastapi.responses import JSONResponse, PlainTextResponse
import uvicorn

MODEL_SIZE = os.environ.get("WHISPER_MODEL", "small")
FORCED_BACKEND = os.environ.get("WHISPER_BACKEND", "").lower()

app = FastAPI(title="Jeeves Whisper Server")

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
    if system == "Linux" and "arm" in machine or "aarch" in machine:
        if shutil.which("whisper-cpp") or shutil.which("whisper"):
            return "whisper_cpp"

    # Default: faster-whisper on CPU (works everywhere)
    return "cpu"


BACKEND = detect_backend()

# ─── Backend Implementations ─────────────────────────────────────────────────────

# Global model instance (lazy-loaded)
_model = None


def get_model():
    """Lazy-load the transcription model for the detected backend."""
    global _model
    if _model is not None:
        return _model

    if BACKEND == "mlx":
        import mlx_whisper
        # mlx_whisper uses model name directly, no pre-load needed
        _model = "mlx"
        print(f"MLX Whisper ready (model: {MODEL_SIZE})")

    elif BACKEND == "cuda":
        from faster_whisper import WhisperModel
        print(f"Loading faster-whisper '{MODEL_SIZE}' on CUDA (GPU)...")
        _model = WhisperModel(MODEL_SIZE, device="cuda", compute_type="float16")
        print("Model loaded on GPU.")

    elif BACKEND == "cpu":
        from faster_whisper import WhisperModel
        print(f"Loading faster-whisper '{MODEL_SIZE}' on CPU (INT8)...")
        _model = WhisperModel(MODEL_SIZE, device="cpu", compute_type="int8")
        print("Model loaded on CPU.")

    elif BACKEND == "whisper_cpp":
        # whisper.cpp is invoked as a subprocess — no Python model to load
        _model = "whisper_cpp"
        print(f"Using whisper.cpp subprocess (model: {MODEL_SIZE})")

    return _model


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
    # Find the whisper.cpp binary
    binary = shutil.which("whisper-cpp") or shutil.which("whisper") or shutil.which("main")
    if not binary:
        raise RuntimeError("whisper.cpp binary not found in PATH")

    # Find model file
    model_path = os.environ.get("WHISPER_CPP_MODEL", f"models/ggml-{MODEL_SIZE}.bin")

    cmd = [
        binary,
        "-m", model_path,
        "-f", file_path,
        "-l", language or "en",
        "--output-json",
        "-t", str(min(os.cpu_count() or 4, 8)),  # Use up to 8 threads
    ]

    result = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    if result.returncode != 0:
        raise RuntimeError(f"whisper.cpp failed: {result.stderr}")

    # Parse the JSON output file (whisper.cpp writes <input>.json)
    import json
    json_path = file_path + ".json"
    if not os.path.exists(json_path):
        # Fallback: parse stdout
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
        # Convert timestamp strings to seconds
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
    return {"status": "ok", "model": MODEL_SIZE, "backend": BACKEND, "platform": platform.system()}


@app.get("/health")
def health_check():
    return {"status": "ok", "backend": BACKEND}


@app.post("/v1/audio/transcriptions")
async def transcribe_endpoint(
    file: UploadFile = File(...),
    model: str = Form(default="whisper-small"),
    response_format: str = Form(default="verbose_json"),
    language: str = Form(default="en"),
):
    """OpenAI-compatible transcription endpoint with auto-detected backend."""
    audio_bytes = await file.read()
    suffix = Path(file.filename).suffix if file.filename else ".wav"

    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(audio_bytes)
        tmp_path = tmp.name

    try:
        # Ensure model is loaded
        get_model()

        print(f"[{BACKEND.upper()}] Transcribing: {file.filename} ({len(audio_bytes)} bytes)")
        text, segments, lang, duration = do_transcribe(tmp_path, language)
        print(f"[{BACKEND.upper()}] Done: {len(segments)} segments, {len(text)} chars")

        if response_format == "text":
            return PlainTextResponse(text)
        elif response_format == "verbose_json":
            resp = {"text": text, "segments": segments, "language": lang}
            if duration is not None:
                resp["duration"] = duration
            return JSONResponse(resp)
        else:
            return JSONResponse({"text": text})
    finally:
        os.unlink(tmp_path)


# Aliases for compatibility
@app.post("/v1/audio/inference")
async def inference(file: UploadFile = File(...), model: str = Form(default=""), response_format: str = Form(default="verbose_json"), language: str = Form(default="en")):
    return await transcribe_endpoint(file=file, model=model, response_format=response_format, language=language)

@app.post("/inference")
async def inference_root(file: UploadFile = File(...), model: str = Form(default=""), response_format: str = Form(default="verbose_json"), language: str = Form(default="en")):
    return await transcribe_endpoint(file=file, model=model, response_format=response_format, language=language)


# ─── Main ────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    port = int(os.environ.get("WHISPER_PORT", "8178"))

    print("=" * 60)
    print("Jeeves Whisper Server — Multi-Backend")
    print(f"  Platform:  {platform.system()} {platform.machine()}")
    print(f"  Backend:   {BACKEND}")
    print(f"  Model:     {MODEL_SIZE}")
    print(f"  Port:      {port}")
    print("=" * 60)
    print()

    uvicorn.run(app, host="0.0.0.0", port=port)
