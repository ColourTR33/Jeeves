"""
WhisperX API Server for Jeeves.
Provides OpenAI-compatible /v1/audio/transcriptions endpoint with speaker diarization.
"""
import os
import tempfile
import json
from flask import Flask, request, jsonify
import whisperx
import torch

app = Flask(__name__)

# Configuration
DEVICE = "cpu"  # Use "cuda" if you have NVIDIA GPU
COMPUTE_TYPE = "int8"  # Use "float16" for GPU
MODEL_SIZE = "small.en"
HF_TOKEN = os.environ.get("HF_TOKEN", "")  # Needed for pyannote diarization

# Load model once at startup
print(f"Loading WhisperX model: {MODEL_SIZE}")
model = whisperx.load_model(MODEL_SIZE, DEVICE, compute_type=COMPUTE_TYPE)
print("Model loaded.")


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"})


@app.route("/v1/audio/inference", methods=["POST"])
@app.route("/inference", methods=["POST"])
@app.route("/v1/audio/transcriptions", methods=["POST"])
def transcribe():
    if "file" not in request.files:
        return jsonify({"error": "No audio file provided"}), 400

    audio_file = request.files["file"]
    diarize = request.form.get("diarize", "false").lower() == "true"

    # Save uploaded file temporarily
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        audio_file.save(tmp.name)
        tmp_path = tmp.name

    try:
        # Load audio
        audio = whisperx.load_audio(tmp_path)

        # Transcribe
        result = model.transcribe(audio, batch_size=16)

        # Align timestamps
        model_a, metadata = whisperx.load_align_model(
            language_code=result["language"],
            device=DEVICE
        )
        result = whisperx.align(
            result["segments"],
            model_a,
            metadata,
            audio,
            DEVICE,
            return_char_alignments=False
        )

        # Speaker diarization (if requested and HF token available)
        if diarize and HF_TOKEN:
            diarize_model = whisperx.DiarizationPipeline(
                use_auth_token=HF_TOKEN,
                device=DEVICE
            )
            diarize_segments = diarize_model(audio)
            result = whisperx.assign_word_speakers(diarize_segments, result)

        # Format response (OpenAI-compatible verbose_json)
        segments = []
        full_text = ""
        for seg in result.get("segments", []):
            speaker = seg.get("speaker", None)
            text = seg.get("text", "")
            full_text += text
            segments.append({
                "start": seg.get("start", 0),
                "end": seg.get("end", 0),
                "text": text,
                "speaker": speaker
            })

        duration = len(audio) / 16000.0  # audio is 16kHz

        response = {
            "text": full_text,
            "segments": segments,
            "language": result.get("language", "en"),
            "duration": duration
        }

        return jsonify(response)

    finally:
        os.unlink(tmp_path)


if __name__ == "__main__":
    print("Starting WhisperX server on port 8179...")
    app.run(host="127.0.0.1", port=8179, debug=False)
