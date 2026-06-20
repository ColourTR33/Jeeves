#!/bin/bash
# Start the WhisperX server for Jeeves
# Set HF_TOKEN environment variable for speaker diarization support

eval "$(conda shell.bash hook)"
conda activate whisperx

# Optionally set HuggingFace token for diarization
# export HF_TOKEN="hf_your_token_here"

cd "$(dirname "$0")"
python server.py
