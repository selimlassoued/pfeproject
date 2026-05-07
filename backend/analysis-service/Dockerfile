FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --upgrade pip

# Install setuptools first in its own step so it's available to whisper's build
RUN pip install setuptools wheel

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

ARG WHISPER_MODEL=small
RUN python -c "import whisper; whisper.load_model('${WHISPER_MODEL}')"

COPY main.py .

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]