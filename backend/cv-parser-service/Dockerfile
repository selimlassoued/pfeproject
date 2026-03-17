FROM python:3.11-slim

LABEL org.opencontainers.image.authors="recrutment.com"

WORKDIR /app

RUN apt-get update && apt-get install -y gcc && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
RUN python -m spacy download en_core_web_sm

COPY app/ ./app/
COPY .env .env

EXPOSE 8085

ENTRYPOINT ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8085"]