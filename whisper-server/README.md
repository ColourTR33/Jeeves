# Jeeves Whisper Server

Local speech-to-text transcription server using OpenAI Whisper. Automatically detects the best backend for your hardware:

| Platform | Backend | Acceleration |
|----------|---------|--------------|
| macOS (Apple Silicon) | MLX Whisper | Neural Engine / Metal |
| Windows + NVIDIA GPU | faster-whisper | CUDA |
| Windows CPU | faster-whisper | INT8 quantization |
| Linux + NVIDIA | faster-whisper | CUDA |
| Linux ARM | whisper.cpp | CPU |

## Quick Start

```bash
# Create virtual environment
python -m venv .venv

# Activate (Windows)
.venv\Scripts\activate

# Activate (macOS/Linux)
source .venv/bin/activate

# Install dependencies
pip install -r requirements.txt

# Run server
python server.py
```

Server runs on `http://localhost:8178` by default.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `WHISPER_MODEL` | `small` | Model size: tiny, base, small, medium, large-v3, turbo |
| `WHISPER_PORT` | `8178` | Server port |
| `WHISPER_BACKEND` | auto | Force backend: mlx, cuda, cpu, whisper_cpp |
| `WHISPER_VERBOSE` | `0` | Set to `1` for debug logging |
| `WHISPER_IDLE_UNLOAD` | `0` | Minutes before unloading model (0 = never) |
| `WHISPER_MAX_FILE_MB` | `200` | Maximum upload file size in MB |

## API Endpoints

### Health Check
```
GET /health
```
Returns server status and loaded model info.

### Transcribe Audio
```
POST /transcribe
Content-Type: multipart/form-data

file: <audio file>
language: (optional) en, es, fr, etc.
```

Returns JSON with transcription text and segments.

## Running as a Windows Service

The whisper server can run as a background Windows Service that starts automatically on boot.

### Prerequisites

1. **NSSM** (Non-Sucking Service Manager)
   ```
   winget install nssm
   ```
   Or download from: https://nssm.cc/download

2. **Python virtual environment** set up (see Quick Start above)

### Install Service

Run as Administrator:
```batch
install-service.bat
```

Or with a specific model:
```batch
install-service.bat medium
```

### Service Commands

```batch
# Stop service
nssm stop JeevesWhisper

# Start service
nssm start JeevesWhisper

# Restart service
nssm restart JeevesWhisper

# View service status
sc query JeevesWhisper

# Uninstall service
uninstall-service.bat
```

### Logs

Service logs are written to:
- `%USERPROFILE%\Jeeves\logs\whisper-server.log`
- `%USERPROFILE%\Jeeves\logs\whisper-server-error.log`

Logs are rotated at 10MB.

## Troubleshooting

### "CUDA out of memory"
Use a smaller model or set `WHISPER_IDLE_UNLOAD=10` to unload after 10 minutes of inactivity.

### "Model loading slowly"
First load downloads the model (~500MB for small, ~3GB for large). Subsequent loads are faster.

### Service won't start
1. Check logs in `%USERPROFILE%\Jeeves\logs\`
2. Verify Python venv exists: `.venv\Scripts\python.exe`
3. Try running manually first: `python server.py`

### Port already in use
Set a different port: `set WHISPER_PORT=8179` before running.
