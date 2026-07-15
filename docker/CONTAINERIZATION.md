# Jeeves — Containerization Guide

This guide covers how to containerize the full Jeeves stack using Docker and Docker Compose.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│  Host Machine (Windows/Linux/macOS)                         │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ Jeeves App   │  │   iOS App    │  │  Browser (future) │  │
│  │ (native jar) │  │  (mobile)    │  │                    │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬──────────┘  │
│         │                  │                    │             │
│  ───────┼──────────────────┼────────────────────┼──────────  │
│         │      Docker Network (jeeves-net)       │           │
│  ───────┼──────────────────┼────────────────────┼──────────  │
│         ▼                  ▼                    ▼             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │whisper-server│  │ diarization  │  │     ollama        │  │
│  │  :8178       │  │   :8180      │  │    :11434         │  │
│  │ CPU INT8     │  │  pyannote    │  │   qwen3:8b        │  │
│  │  ~800MB      │  │   ~2GB       │  │    ~5GB           │  │
│  └──────────────┘  └──────────────┘  └──────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

The desktop app (Jeeves.jar) runs natively on the host because it needs:
- Microphone access
- System audio capture (Stereo Mix)
- Global hotkeys (JNativeHook)
- Desktop UI (Compose)

The backend services (whisper, diarization, ollama) are ideal for containers because they are stateless HTTP APIs that consume significant memory and can be started/stopped independently.

---

## Quick Start

### Prerequisites
- Docker Desktop (Windows/macOS) or Docker Engine (Linux)
- At least 8GB RAM available for containers
- HuggingFace token (for diarization — optional)

### 1. Set up environment

```bash
cd docker
cp .env.example .env
# Edit .env with your HuggingFace token
```

### 2. Start the backend services

```bash
docker compose up -d
```

### 3. Pull the LLM model (first time only)

```bash
docker exec -it jeeves-ollama ollama pull qwen3:8b
```

### 4. Verify services are running

```bash
docker compose ps
curl http://localhost:8178/health   # Whisper
curl http://localhost:8180/health   # Diarization
curl http://localhost:11434/        # Ollama
```

### 5. Point the desktop app at containers

In Jeeves Settings:
- Transcription URL: `http://localhost:8178`
- Summarization URL: `http://localhost:11434`
- Diarization URL: `http://localhost:8180`

(These are the defaults — no change needed if running on the same machine.)

---

## Resource Requirements

| Service | RAM (idle) | RAM (active) | CPU | Disk |
|---------|-----------|-------------|-----|------|
| whisper-server (small) | 50MB | 800MB | 4 cores | 500MB |
| diarization (pyannote) | 200MB | 2GB | 4 cores | 4GB (image) |
| ollama (qwen3:8b) | 100MB | 5GB | 4 cores | 5GB (model) |
| **Total** | **350MB** | **~8GB** | — | **~10GB** |

With idle unloading enabled (default), whisper drops to ~50MB when not transcribing. Ollama similarly unloads models after 5 minutes of inactivity.

---

## Memory-Constrained Deployment (16GB System)

If your machine has 16GB total, with the desktop app + OS using ~6GB, you have ~10GB for containers. Recommendations:

```yaml
# In docker-compose.yml, reduce limits:
whisper-server:
  environment:
    - WHISPER_MODEL=base          # 300MB instead of 800MB
    - WHISPER_IDLE_UNLOAD=5       # Unload after 5 min idle
  deploy:
    resources:
      limits:
        memory: 1G

diarization:
  # Only start when needed:
  profiles: ["full"]              # docker compose --profile full up

ollama:
  deploy:
    resources:
      limits:
        memory: 4G
```

Run without diarization by default:
```bash
docker compose up -d whisper-server ollama
# Only when you need speaker ID:
docker compose --profile full up -d diarization
```

---

## Building Custom Images

### Whisper Server

```bash
docker build -f docker/Dockerfile.whisper -t jeeves-whisper:latest .
```

### Diarization Server

```bash
docker build -f docker/Dockerfile.diarization -t jeeves-diarization:latest .
```

### NVIDIA GPU Support (CUDA)

If you have an NVIDIA GPU, use the CUDA variant:

```yaml
# docker-compose.gpu.yml (override file)
services:
  whisper-server:
    environment:
      - WHISPER_BACKEND=cuda
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu]
```

Run with:
```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d
```

---

## Production Deployment (Remote Server)

For running the backend on a separate server (e.g., a NAS or cloud VM) that the desktop/mobile app connects to over the network:

### 1. On the server

```bash
# Clone the repo
git clone <your-repo-url> jeeves
cd jeeves/docker

# Set environment
cp .env.example .env
vim .env  # Add HF_TOKEN

# Start services (bind to all interfaces)
docker compose up -d

# Pull the model
docker exec -it jeeves-ollama ollama pull qwen3:8b
```

### 2. On the client (desktop app)

Update Settings to point at the server IP:
- Transcription URL: `http://192.168.1.100:8178`
- Summarization URL: `http://192.168.1.100:11434`
- Diarization URL: `http://192.168.1.100:8180`

### 3. Firewall rules

Open ports 8178, 8180, 11434 on the server. For security, consider:
- VPN/WireGuard tunnel instead of exposing ports
- Reverse proxy (Caddy/nginx) with authentication
- Docker network isolation

---

## Monitoring & Logging

### View logs

```bash
docker compose logs -f whisper-server
docker compose logs -f --tail=100 diarization
```

### Enable verbose logging

```bash
docker compose exec whisper-server sh -c "export WHISPER_VERBOSE=1"
# Or restart with the env var:
WHISPER_VERBOSE=1 docker compose up -d whisper-server
```

### Check memory usage

```bash
docker stats --no-stream
```

### Whisper server status endpoint

```bash
curl http://localhost:8178/v1/status
# Returns: memory_mb, cpu_percent, queue_size, model_loaded, uptime
```

### Force model unload (free RAM)

```bash
curl -X POST http://localhost:8178/v1/unload
```

---

## Backup & Data

The desktop app stores data in `C:\Users\<user>\Jeeves\` (or `~/Jeeves/` on Linux/macOS). This is on the host, not in containers.

If you run the app server-side in future, mount volumes:

```yaml
volumes:
  - ./data/recordings:/app/Jeeves/recordings
  - ./data/transcriptions:/app/Jeeves/transcriptions
  - ./data/summaries:/app/Jeeves/summaries
  - ./data/settings:/app/Jeeves/settings.json
```

---

## Upgrading

```bash
cd docker

# Pull latest code
git pull

# Rebuild containers
docker compose build --no-cache

# Restart
docker compose down
docker compose up -d
```

---

## Troubleshooting

### "Cannot connect to whisper server"
- Check container is running: `docker compose ps`
- Check logs: `docker compose logs whisper-server`
- Model may still be loading (first start takes 30-60s): wait for healthcheck to pass

### "Out of memory" / container killed
- Check limits in docker-compose.yml
- Reduce model size: `WHISPER_MODEL=base`
- Enable idle unload: `WHISPER_IDLE_UNLOAD=5`
- Stop diarization when not needed

### "HF_TOKEN not set" (diarization)
- Ensure `.env` file exists with your token
- Token must have accepted model terms at HuggingFace

### Ollama "model not found"
- Pull the model first: `docker exec -it jeeves-ollama ollama pull qwen3:8b`
- Models persist in the `ollama-models` volume across restarts

---

## Future: Full Containerization

When the iOS app is implemented and needs a server backend, the architecture will be:

```
┌────────────┐     ┌────────────────────────────────────────┐
│  iOS App   │────▶│  Docker Host                           │
│  (mobile)  │     │  ┌─────────────────────────────────┐   │
└────────────┘     │  │ jeeves-api (Ktor server)        │   │
                   │  │ - Recording upload endpoint      │   │
┌────────────┐     │  │ - Transcription/summary fetch    │   │
│ Desktop App│────▶│  │ - Time tracking sync             │   │
│ (Windows)  │     │  └───┬─────────┬──────────┬────────┘   │
└────────────┘     │      │         │          │             │
                   │      ▼         ▼          ▼             │
                   │  ┌────────┐ ┌──────┐ ┌──────────────┐  │
                   │  │whisper │ │ollama│ │ diarization  │  │
                   │  │ :8178  │ │:11434│ │    :8180     │  │
                   │  └────────┘ └──────┘ └──────────────┘  │
                   └────────────────────────────────────────┘
```

This requires extracting the recording/processing logic from the desktop app into a shared Ktor HTTP server module — a natural extension of the existing KMP shared module.
