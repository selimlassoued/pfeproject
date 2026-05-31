#!/usr/bin/env python3
"""
Whisper model comparison harness — decide medium vs large-v2 vs large-v3 on
YOUR own interview audio instead of generic benchmarks.

Mirrors the exact transcribe() settings used by main.py so the comparison is
apples-to-apples. For each model it reports:

  - load time + inference time (the CPU speed cost that matters here)
  - segment count, word count
  - mean avg_logprob (higher = more confident / usually cleaner)
  - # segments that look like silence hallucinations (stock-phrase match OR
    high no_speech_prob with very few words)
  - full transcript dumped to bench_out/<model>.txt for eyeballing

Run it INSIDE the analysis-service container (whisper + ffmpeg live there):

    docker compose cp ./some-interview.webm analysis-service:/tmp/clip.webm
    docker compose exec analysis-service python bench_whisper.py /tmp/clip.webm

Or point it at a recording already on the mounted /recordings volume:

    docker compose exec analysis-service \
        python bench_whisper.py /recordings/<interview-id>/candidate.webm

Optionally pass the models to compare (default: medium large-v2 large-v3):

    docker compose exec analysis-service \
        python bench_whisper.py /tmp/clip.webm medium large-v2
"""
import sys
import time
import subprocess
import tempfile
from pathlib import Path

import whisper

# ── Mirror main.py's transcribe settings exactly ───────────────────────────
LANGUAGE = "en"
TRANSCRIBE_KWARGS = dict(
    language=LANGUAGE,
    word_timestamps=True,
    fp16=False,
    no_speech_threshold=0.85,
    logprob_threshold=-1.2,
    compression_ratio_threshold=2.4,
    condition_on_previous_text=False,
    temperature=(0.0, 0.2, 0.4),
)

# Same stock phrases main.py treats as silence hallucinations.
HALLUCINATION_PATTERNS = {
    "thank you for watching", "thanks for watching", "please subscribe",
    "subtitles by", "transcribed by",
}
NO_SPEECH_DROP_THRESHOLD = 0.90
NO_SPEECH_KEEP_MIN_WORDS = 4

DEFAULT_MODELS = ["medium", "large-v2", "large-v3"]


def to_wav(src: Path) -> Path:
    """16kHz mono wav — same preprocessing shape main.py feeds Whisper."""
    out = Path(tempfile.gettempdir()) / f"bench_{src.stem}.wav"
    subprocess.run(
        ["ffmpeg", "-y", "-i", str(src), "-ac", "1", "-ar", "16000",
         "-af", "lowpass=8000", str(out)],
        check=True, capture_output=True,
    )
    return out


def looks_like_hallucination(text: str, no_speech_prob: float) -> bool:
    t = text.strip().lower()
    if any(p in t for p in HALLUCINATION_PATTERNS):
        return True
    # high no-speech probability AND too few words to be a real answer
    if no_speech_prob >= NO_SPEECH_DROP_THRESHOLD and len(t.split()) < NO_SPEECH_KEEP_MIN_WORDS:
        return True
    return False


def bench(model_name: str, wav: Path, out_dir: Path) -> dict:
    print(f"\n=== {model_name} ===", flush=True)
    t0 = time.monotonic()
    model = whisper.load_model(model_name)
    load_s = time.monotonic() - t0
    print(f"  loaded in {load_s:.1f}s", flush=True)

    t1 = time.monotonic()
    result = model.transcribe(str(wav), **TRANSCRIBE_KWARGS)
    infer_s = time.monotonic() - t1
    print(f"  inference in {infer_s:.1f}s", flush=True)

    segs = result.get("segments", [])
    words = sum(len((s.get("text") or "").split()) for s in segs)
    logprobs = [s.get("avg_logprob", 0.0) for s in segs if "avg_logprob" in s]
    mean_lp = sum(logprobs) / len(logprobs) if logprobs else 0.0
    hallu = sum(
        1 for s in segs
        if looks_like_hallucination(s.get("text", ""), s.get("no_speech_prob", 0.0))
    )

    # Dump transcript for manual review.
    out_file = out_dir / f"{model_name.replace('/', '_')}.txt"
    with out_file.open("w", encoding="utf-8") as f:
        for s in segs:
            f.write(f"[{s.get('start',0):7.2f}-{s.get('end',0):7.2f}] "
                    f"(ns={s.get('no_speech_prob',0):.2f} lp={s.get('avg_logprob',0):.2f}) "
                    f"{(s.get('text') or '').strip()}\n")

    # Free model before loading the next (CPU RAM is the constraint).
    del model

    return {
        "model": model_name,
        "load_s": load_s,
        "infer_s": infer_s,
        "segments": len(segs),
        "words": words,
        "mean_logprob": mean_lp,
        "hallucinations": hallu,
        "transcript": str(out_file),
    }


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    audio = Path(sys.argv[1])
    if not audio.exists():
        print(f"Audio file not found: {audio}")
        sys.exit(1)
    models = sys.argv[2:] or DEFAULT_MODELS

    out_dir = Path("bench_out")
    out_dir.mkdir(exist_ok=True)

    print(f"Audio: {audio}")
    print("Converting to 16kHz mono wav…", flush=True)
    wav = to_wav(audio)
    try:
        # audio duration for speed-ratio
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(wav)],
            capture_output=True, text=True,
        )
        dur = float(probe.stdout.strip() or 0)

        rows = [bench(m, wav, out_dir) for m in models]
    finally:
        wav.unlink(missing_ok=True)

    # ── Summary table ──────────────────────────────────────────────────────
    print("\n" + "=" * 78)
    print(f"AUDIO DURATION: {dur:.0f}s ({dur/60:.1f} min)\n")
    hdr = f"{'model':<10} {'infer_s':>8} {'xRealtime':>10} {'segs':>5} {'words':>6} {'meanLP':>7} {'hallu':>6}"
    print(hdr)
    print("-" * len(hdr))
    for r in rows:
        xrt = (r["infer_s"] / dur) if dur else 0
        print(f"{r['model']:<10} {r['infer_s']:>8.1f} {xrt:>9.2f}x "
              f"{r['segments']:>5} {r['words']:>6} {r['mean_logprob']:>7.2f} {r['hallucinations']:>6}")
    print("\nLower xRealtime = faster. Higher meanLP = more confident. Lower hallu = fewer silence artifacts.")
    print("Full transcripts written to bench_out/ — eyeball them against what was actually said.")
    print("Pick the smallest model whose transcript you trust; bigger only wins if accuracy gain is real on your accents.")


if __name__ == "__main__":
    main()
