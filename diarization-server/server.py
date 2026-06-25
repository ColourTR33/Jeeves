"""
Local speaker diarization server using pyannote-audio.
Exposes a simple REST API for Jeeves to call after transcription.

All processing happens on-device — no data leaves your machine.

Usage:
    pip install -r requirements.txt
    python server.py

First run will download the pyannote models (~600MB) from HuggingFace.
You must accept the model terms and provide your HF token:
    1. Go to https://huggingface.co/pyannote/speaker-diarization-3.1
    2. Accept the user conditions
    3. Also accept https://huggingface.co/pyannote/segmentation-3.0
    4. Create a token at https://huggingface.co/settings/tokens
    5. Set HF_TOKEN environment variable or pass --token flag
"""
import os
import tempfile
from pathlib import Path

from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import JSONResponse
import uvicorn

app = FastAPI(title="Jeeves Diarization Server")

# Global pipeline instance (loaded on first request)
pipeline = None
HF_TOKEN = os.environ.get("HF_TOKEN", "")


def get_pipeline():
    """Lazy-load the pyannote diarization pipeline."""
    global pipeline
    if pipeline is None:
        if not HF_TOKEN:
            raise RuntimeError(
                "HF_TOKEN environment variable not set. "
                "Get a token from https://huggingface.co/settings/tokens "
                "after accepting pyannote/speaker-diarization-3.1 terms."
            )

        from pyannote.audio import Pipeline
        import torch

        print("Loading pyannote speaker-diarization-3.1 model...")
        print("(First run downloads ~600MB of models — subsequent runs use cache)")

        pipeline = Pipeline.from_pretrained(
            "pyannote/speaker-diarization-3.1",
            use_auth_token=HF_TOKEN
        )

        # Use GPU if available, otherwise CPU
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        pipeline.to(device)
        print(f"Diarization pipeline loaded on {device}")

    return pipeline


@app.get("/")
def root():
    return {"status": "ok", "service": "pyannote-diarization"}


@app.get("/health")
def health():
    return {"status": "ok", "model_loaded": pipeline is not None}


@app.post("/v1/diarize")
async def diarize(
    file: UploadFile = File(...),
    num_speakers: int = Form(default=0),
    min_speakers: int = Form(default=1),
    max_speakers: int = Form(default=10),
):
    """
    Diarize an audio file and return speaker segments.

    Parameters:
        file: WAV audio file
        num_speakers: Exact number of speakers (0 = auto-detect)
        min_speakers: Minimum expected speakers (used when num_speakers=0)
        max_speakers: Maximum expected speakers (used when num_speakers=0)

    Returns:
        JSON with speaker segments:
        {
            "segments": [
                {"speaker": "SPEAKER_00", "start": 0.5, "end": 4.2},
                {"speaker": "SPEAKER_01", "start": 4.5, "end": 8.1},
                ...
            ],
            "num_speakers": 2
        }
    """
    try:
        diarization_pipeline = get_pipeline()
    except RuntimeError as e:
        raise HTTPException(status_code=503, detail=str(e))

    # Save uploaded audio to temp file
    audio_bytes = await file.read()
    suffix = Path(file.filename).suffix if file.filename else ".wav"

    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(audio_bytes)
        tmp_path = tmp.name

    try:
        print(f"Diarizing: {file.filename} ({len(audio_bytes)} bytes)")

        # Build kwargs for the pipeline
        kwargs = {}
        if num_speakers > 0:
            kwargs["num_speakers"] = num_speakers
        else:
            if min_speakers > 1:
                kwargs["min_speakers"] = min_speakers
            if max_speakers < 10:
                kwargs["max_speakers"] = max_speakers

        # Run diarization
        diarization = diarization_pipeline(tmp_path, **kwargs)

        # Convert to JSON-friendly format
        segments = []
        speakers_seen = set()

        for turn, _, speaker in diarization.itertracks(yield_label=True):
            segments.append({
                "speaker": speaker,
                "start": round(turn.start, 3),
                "end": round(turn.end, 3),
            })
            speakers_seen.add(speaker)

        print(f"Diarization complete: {len(segments)} segments, {len(speakers_seen)} speakers")

        return JSONResponse({
            "segments": segments,
            "num_speakers": len(speakers_seen)
        })

    except Exception as e:
        print(f"Diarization error: {e}")
        raise HTTPException(status_code=500, detail=f"Diarization failed: {str(e)}")
    finally:
        os.unlink(tmp_path)


if __name__ == "__main__":
    port = int(os.environ.get("DIARIZATION_PORT", "8180"))

    if not HF_TOKEN:
        print("=" * 60)
        print("WARNING: HF_TOKEN not set!")
        print("Set it with: $env:HF_TOKEN='hf_your_token_here'")
        print("Get a token from: https://huggingface.co/settings/tokens")
        print("Accept terms at:")
        print("  https://huggingface.co/pyannote/speaker-diarization-3.1")
        print("  https://huggingface.co/pyannote/segmentation-3.0")
        print("=" * 60)
    else:
        print(f"HF_TOKEN: {'*' * 4}{HF_TOKEN[-4:]}")

    print(f"Starting diarization server on port {port}...")
    print(f"Endpoint: http://localhost:{port}/v1/diarize")
    uvicorn.run(app, host="0.0.0.0", port=port)
