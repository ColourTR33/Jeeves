"""
Simple Whisper transcription server using faster-whisper.
Exposes OpenAI-compatible /v1/audio/transcriptions endpoint.
Runs on CPU by default. Automatically chunks long audio files
to avoid timeout issues.

Usage:
    pip install faster-whisper fastapi uvicorn python-multipart
    python server.py
"""
import io
import json
import tempfile
import os
import math
from pathlib import Path

from fastapi import FastAPI, UploadFile, File, Form
from fastapi.responses import JSONResponse, PlainTextResponse
import uvicorn

# Will be loaded on first request
model = None
MODEL_SIZE = os.environ.get("WHISPER_MODEL", "small")

# Maximum duration (seconds) before chunking kicks in
CHUNK_THRESHOLD_SECONDS = 300  # 5 minutes
CHUNK_SIZE_SECONDS = 300       # 5-minute chunks

app = FastAPI(title="Jeeves Whisper Server")


def get_model():
    global model
    if model is None:
        from faster_whisper import WhisperModel
        print(f"Loading Whisper model '{MODEL_SIZE}' (CPU)... this may take a moment on first run.")
        model = WhisperModel(MODEL_SIZE, device="cpu", compute_type="int8")
        print("Model loaded.")
    return model


def get_audio_duration(file_path: str) -> float:
    """Get duration of a WAV audio file in seconds using the wave module."""
    try:
        import wave
        with wave.open(file_path, 'rb') as wf:
            frames = wf.getnframes()
            rate = wf.getframerate()
            return frames / float(rate)
    except Exception as e:
        print(f"Could not determine audio duration via wave module: {e}")
        # Fallback: estimate from file size (16kHz, 16-bit mono WAV ~ 32KB/sec)
        file_size = os.path.getsize(file_path)
        return file_size / 32000.0


def split_audio_file(file_path: str, chunk_seconds: int) -> list:
    """
    Split a WAV audio file into chunks of chunk_seconds duration.
    Returns a list of (temp_file_path, time_offset_seconds) tuples.
    Uses the standard library wave module (no pydub dependency).
    """
    import wave
    import struct

    with wave.open(file_path, 'rb') as wf:
        n_channels = wf.getnchannels()
        sample_width = wf.getsampwidth()
        frame_rate = wf.getframerate()
        n_frames = wf.getnframes()
        duration_seconds = n_frames / float(frame_rate)

        frames_per_chunk = chunk_seconds * frame_rate
        num_chunks = math.ceil(n_frames / frames_per_chunk)
        print(f"Splitting audio ({duration_seconds:.1f}s) into {num_chunks} chunks of {chunk_seconds}s each")

        chunk_paths = []
        for i in range(num_chunks):
            start_frame = i * frames_per_chunk
            end_frame = min((i + 1) * frames_per_chunk, n_frames)
            chunk_frames = end_frame - start_frame

            wf.setpos(start_frame)
            audio_data = wf.readframes(chunk_frames)

            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
                with wave.open(tmp.name, 'wb') as chunk_wf:
                    chunk_wf.setnchannels(n_channels)
                    chunk_wf.setsampwidth(sample_width)
                    chunk_wf.setframerate(frame_rate)
                    chunk_wf.writeframes(audio_data)

                time_offset = start_frame / float(frame_rate)
                chunk_paths.append((tmp.name, time_offset))
                print(f"  Chunk {i+1}/{num_chunks}: {time_offset:.1f}s - {end_frame/float(frame_rate):.1f}s")

    return chunk_paths


def transcribe_file(whisper_model, file_path: str, language: str, time_offset: float = 0.0):
    """
    Transcribe a single audio file and return (segments, text_parts, duration).
    time_offset is added to segment timestamps for chunked files.
    """
    segments, info = whisper_model.transcribe(
        file_path,
        language=language if language else None,
        beam_size=5,
        vad_filter=True,
    )

    result_segments = []
    full_text_parts = []
    for segment in segments:
        result_segments.append({
            "start": round(segment.start + time_offset, 2),
            "end": round(segment.end + time_offset, 2),
            "text": segment.text.strip()
        })
        full_text_parts.append(segment.text.strip())

    return result_segments, full_text_parts, info


@app.get("/")
def health():
    return {"status": "ok", "model": MODEL_SIZE}


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
    """OpenAI-compatible transcription endpoint with automatic chunking for long files."""
    # Save uploaded audio to temp file
    audio_bytes = await file.read()
    suffix = Path(file.filename).suffix if file.filename else ".wav"

    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(audio_bytes)
        tmp_path = tmp.name

    try:
        whisper = get_model()
        duration = get_audio_duration(tmp_path)
        print(f"Received audio file: {file.filename} ({len(audio_bytes)} bytes, ~{duration:.1f}s)")

        if duration > CHUNK_THRESHOLD_SECONDS:
            # Long file: split into chunks and process sequentially
            print(f"File exceeds {CHUNK_THRESHOLD_SECONDS}s threshold, using chunked transcription")
            chunk_paths = split_audio_file(tmp_path, CHUNK_SIZE_SECONDS)

            all_segments = []
            all_text_parts = []
            total_duration = duration
            detected_language = language

            try:
                for i, (chunk_path, time_offset) in enumerate(chunk_paths):
                    print(f"Transcribing chunk {i+1}/{len(chunk_paths)}...")
                    try:
                        segments, text_parts, info = transcribe_file(
                            whisper, chunk_path, language, time_offset
                        )
                        all_segments.extend(segments)
                        all_text_parts.extend(text_parts)
                        detected_language = info.language
                        print(f"  Chunk {i+1} done: {len(text_parts)} segments")
                    finally:
                        os.unlink(chunk_path)
            except Exception as e:
                # Clean up remaining chunk files on error
                for chunk_path, _ in chunk_paths:
                    if os.path.exists(chunk_path):
                        os.unlink(chunk_path)
                raise e

            full_text = " ".join(all_text_parts)

            if response_format == "text":
                return PlainTextResponse(full_text)
            elif response_format == "verbose_json":
                return JSONResponse({
                    "text": full_text,
                    "segments": all_segments,
                    "language": detected_language,
                    "duration": total_duration
                })
            else:
                return JSONResponse({"text": full_text})
        else:
            # Short file: transcribe directly (existing behavior)
            segments, text_parts, info = transcribe_file(whisper, tmp_path, language)
            full_text = " ".join(text_parts)

            if response_format == "text":
                return PlainTextResponse(full_text)
            elif response_format == "verbose_json":
                return JSONResponse({
                    "text": full_text,
                    "segments": segments,
                    "language": info.language,
                    "duration": info.duration
                })
            else:
                return JSONResponse({"text": full_text})
    finally:
        os.unlink(tmp_path)


# Also support /inference endpoint for streaming compatibility
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
    print(f"Model: {MODEL_SIZE} | Device: CPU")
    print(f"Endpoint: http://localhost:{port}/v1/audio/transcriptions")
    uvicorn.run(app, host="0.0.0.0", port=port)
