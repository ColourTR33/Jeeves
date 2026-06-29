"""
Whisper transcription server optimized for Intel CPU (no CUDA).
Uses faster-whisper with INT8 quantization and VAD-based segmentation.

Key optimizations for Intel i7 + integrated graphics:
- INT8 quantization: ~4x less RAM than FP32, leverages CTranslate2 Intel optimizations
- VAD filter: Silero VAD skips silence automatically, no manual chunking needed
- Streaming segments: processes audio progressively, not all-at-once

Usage:
    pip install faster-whisper fastapi uvicorn python-multipart
    python server.py
"""
import tempfile
import os
from pathlib import Path

from fastapi import FastAPI, UploadFile, File, Form
from fastapi.responses import JSONResponse, PlainTextResponse
import uvicorn

# Global model instance (loaded on first request)
model = None
MODEL_SIZE = os.environ.get("WHISPER_MODEL", "small")

app = FastAPI(title="Jeeves Whisper Server")


def get_model():
    global model
    if model is None:
        from faster_whisper import WhisperModel
        print(f"Loading Whisper model '{MODEL_SIZE}' (CPU, INT8)...")
        print("(First run downloads the model — subsequent runs use cache)")
        model = WhisperModel(MODEL_SIZE, device="cpu", compute_type="int8")
        print(f"Model '{MODEL_SIZE}' loaded. Ready for transcription.")
    return model


@app.get("/")
def health():
    return {"status": "ok", "model": MODEL_SIZE, "compute": "int8", "device": "cpu"}


@app.get("/health")
def health_check():
    return {"status": "ok"}


@app.post("/v1/audio/transcriptions")
async def transcribe(
    file: UploadFile = File(...),
    model: str = Form(default="whisper-small"),
    response_format: str = Form(default="verbose_json"),
    language: str = Form(default="en"),
):
    """
    OpenAI-compatible transcription endpoint.
    Uses VAD filter for natural sentence boundaries — no manual chunking needed.
    Handles files of any length efficiently.
    """
    audio_bytes = await file.read()
    suffix = Path(file.filename).suffix if file.filename else ".wav"

    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(audio_bytes)
        tmp_path = tmp.name

    try:
        whisper = get_model()
        print(f"Transcribing: {file.filename} ({len(audio_bytes)} bytes)")

        # VAD filter handles silence detection and natural segmentation
        # No need for manual chunking — faster-whisper streams segments progressively
        segments, info = whisper.transcribe(
            tmp_path,
            language=language if language else None,
            beam_size=5,
            vad_filter=True,
            vad_parameters=dict(min_silence_duration_ms=500),
        )

        result_segments = []
        full_text_parts = []
        for segment in segments:
            result_segments.append({
                "start": round(segment.start, 2),
                "end": round(segment.end, 2),
                "text": segment.text.strip()
            })
            full_text_parts.append(segment.text.strip())

        full_text = " ".join(full_text_parts)
        print(f"Done: {len(result_segments)} segments, {len(full_text)} chars")

        if response_format == "text":
            return PlainTextResponse(full_text)
        elif response_format == "verbose_json":
            return JSONResponse({
                "text": full_text,
                "segments": result_segments,
                "language": info.language,
                "duration": info.duration
            })
        else:
            return JSONResponse({"text": full_text})
    finally:
        os.unlink(tmp_path)


# Also support /inference endpoints for streaming compatibility
@app.post("/v1/audio/inference")
async def inference(
    file: UploadFile = File(...),
    model: str = Form(default="whisper-small"),
    response_format: str = Form(default="verbose_json"),
    language: str = Form(default="en"),
):
    return await transcribe(file=file, model=model, response_format=response_format, language=language)


@app.post("/inference")
async def inference_root(
    file: UploadFile = File(...),
    model: str = Form(default="whisper-small"),
    response_format: str = Form(default="verbose_json"),
    language: str = Form(default="en"),
):
    return await transcribe(file=file, model=model, response_format=response_format, language=language)


if __name__ == "__main__":
    port = int(os.environ.get("WHISPER_PORT", "8178"))
    print(f"Starting Whisper server on port {port}...")
    print(f"Model: {MODEL_SIZE} | Device: CPU | Compute: INT8")
    print(f"Optimized for Intel CPU (no CUDA required)")
    print(f"Endpoint: http://localhost:{port}/v1/audio/transcriptions")
    uvicorn.run(app, host="0.0.0.0", port=port)
