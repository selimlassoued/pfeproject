FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --upgrade pip
RUN pip install setuptools wheel

COPY requirements.txt .
RUN grep -v "openai-whisper" requirements.txt > requirements_no_whisper.txt && \
    pip install --no-cache-dir -r requirements_no_whisper.txt
RUN pip install --no-cache-dir --no-build-isolation openai-whisper==20240930

COPY main.py .

EXPOSE 8000

# Model is downloaded at startup into the mounted volume — not at build time
ENV WHISPER_MODEL=small
CMD python -c "import whisper; whisper.load_model('${WHISPER_MODEL}')" && \
    uvicorn main:app --host 0.0.0.0 --port 8000
