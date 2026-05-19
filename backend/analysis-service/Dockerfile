FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --upgrade pip
RUN pip install setuptools wheel

# Install librosa
RUN pip install --no-cache-dir librosa

# Install PyTorch CPU-only FIRST (much smaller, ~250MB vs 2GB+ with CUDA)
# If you DO need GPU support, replace with the appropriate CUDA index URL
RUN pip install --no-cache-dir --retries 10 --timeout 300 \
    torch torchaudio --index-url https://download.pytorch.org/whl/cpu

# Now install pyannote.audio — torch is already satisfied, so it won't re-download it
RUN pip install --no-cache-dir --retries 10 --timeout 300 pyannote.audio

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Bake in the fallback model so the container is always usable even before
# the runtime model (which may be larger, e.g. large-v3) finishes downloading.
ARG WHISPER_FALLBACK_MODEL=medium
ENV WHISPER_FALLBACK_MODEL=${WHISPER_FALLBACK_MODEL}
RUN python -c "import whisper; whisper.load_model('${WHISPER_FALLBACK_MODEL}')"

# Optionally pre-download the primary model at build time if it differs from
# the fallback. Skipped (and downloaded lazily at startup) when unset.
ARG WHISPER_MODEL=""
ENV WHISPER_MODEL=${WHISPER_MODEL}
RUN if [ -n "$WHISPER_MODEL" ] && [ "$WHISPER_MODEL" != "$WHISPER_FALLBACK_MODEL" ]; then \
        python -c "import whisper; whisper.load_model('${WHISPER_MODEL}')" ; \
    fi

COPY . .

EXPOSE 8000

# Single uvicorn worker — Whisper is loaded once per process. Adding workers
# would multiply the model's memory footprint without parallelising inference.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
