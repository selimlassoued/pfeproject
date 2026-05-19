import json
import logging
import os
import traceback
from pathlib import Path
import numpy as np
import wave
import subprocess
import tempfile
from datetime import datetime, timezone


import httpx
import whisper
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

ENERGY_DOMINANCE_RATIO = 1.5   # speaker must be 50% louder to be confirmed
ENERGY_WINDOW_SEC      = 0.8   # seconds around segment start to sample
ENERGY_FRAME_MS        = 20    # ms per energy frame

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("analysis")

app = FastAPI(title="Interview Analysis Sidecar")

# ── Config ────────────────────────────────────────────────────────────────────
WHISPER_MODEL  = os.getenv("WHISPER_MODEL", "base")       # tiny/base/small/medium
OLLAMA_BASE    = os.getenv("OLLAMA_BASE",   "http://ollama:11434")
OLLAMA_MODEL   = os.getenv("OLLAMA_MODEL",  "qwen2.5:7b")
RECORDINGS_DIR = os.getenv("RECORDINGS_DIR", "/recordings")

# Load Whisper once at startup (expensive)
log.info("Loading Whisper model '%s'…", WHISPER_MODEL)
_whisper_model = whisper.load_model(WHISPER_MODEL)
log.info("Whisper ready.")


# ── Request / Response schemas ────────────────────────────────────────────────
class AnalyseRequest(BaseModel):
    interview_id:     str
    job_title:        str
    job_description:   str | None = None
    job_requirements:  list[str] | None = None
    candidate_name:   str | None = None
    candidate_skills: list[str] | None = None
    candidate_summary: str | None = None
    github_score:     str | None = None
    recruiter_joined_at: str | None = None   # ISO-8601, e.g. "2024-01-15T09:00:05.123Z"
    candidate_joined_at: str | None = None


class AnalyseResponse(BaseModel):
    transcript:          str          # merged, labelled
    summary:             str
    candidate_score:     int          # 1-10
    candidate_strengths: list[str]
    candidate_weaknesses: list[str]
    suggested_questions: list[str]
    hiring_recommendation: str        # STRONG_YES / YES / MAYBE / NO

class QuestionGenRequest(BaseModel):
    job_title:          str
    job_description:    str | None = None
    candidate_name:     str | None = None
    candidate_skills:   list[str] | None = None
    candidate_summary:  str | None = None
    github_score:       str | None = None
    github_frameworks:  list[str] | None = None
    cv_weaknesses:      list[str] | None = None  # from CV evaluation

class QuestionGenResponse(BaseModel):
    technical:   list[str]  # job-specific technical questions
    behavioral:  list[str]  # soft skills / situational
    cv_specific: list[str]  # directly about their CV/projects/GitHub
# ── Helpers ───────────────────────────────────────────────────────────────────

def _transcribe(path: Path, speaker: str,
                job_title: str = "",
                job_description: str = "",
                job_requirements: list[str] | None = None) -> list[dict]:
    log.info("Transcribing %s as %s…", path, speaker)

    # Build domain vocabulary from job requirements
    req_text = ""
    if job_requirements:
        req_text = " ".join(job_requirements)

    result = _whisper_model.transcribe(
        str(path),
        word_timestamps=True,
        language="en",
        fp16=False,
        condition_on_previous_text=False,
        initial_prompt=(
            f"Job interview for {job_title} at VERMEG. "
            f"{job_description[:200] if job_description else ''} "
            f"{req_text[:300] if req_text else ''} "
            "PostgreSQL, Oracle, Docker, Kubernetes, microservices, SQL, "
            "indexing, query optimization, transaction isolation."
        )
    )
    segments = []
    for seg in result["segments"]:
        segments.append({
            "start":   seg["start"],
            "end":     seg["end"],
            "text":    seg["text"].strip(),
            "speaker": speaker,
        })
    return segments


def _merge_transcripts(recruiter_segs: list[dict],
                       candidate_segs: list[dict]) -> str:
    """Interleave segments by start time, return labelled plain text."""
    all_segs = sorted(recruiter_segs + candidate_segs, key=lambda s: s["start"])
    lines = []
    for seg in all_segs:
        ts   = f"[{int(seg['start']//60):02d}:{int(seg['start']%60):02d}]"
        who  = "Recruiter" if seg["speaker"] == "recruiter" else "Candidate"
        lines.append(f"{ts} {who}: {seg['text']}")
    return "\n".join(lines)


def _word_overlap(a: str, b: str) -> float:
    wa = set(a.lower().split())
    wb = set(b.lower().split())
    if not wa or not wb:
        return 0.0
    return len(wa & wb) / min(len(wa), len(wb))


def _remove_repeated_phrases(text: str) -> str:
    """Remove consecutive duplicate sentences within a segment."""
    parts = [p.strip() for p in text.replace('?', '.').replace('!', '.').split('.') if p.strip()]
    deduped = []
    for p in parts:
        if not deduped or p.lower() != deduped[-1].lower():
            deduped.append(p)
    return '. '.join(deduped)


def _filter_echo_segments(segments: list[dict], similarity_threshold: float = 0.65) -> list[dict]:
    """Drop segments whose text is too similar to a recent segment from the other speaker."""
    filtered: list[dict] = []
    for seg in segments:
        is_echo = False
        for prev in filtered[-4:]:
            if prev['speaker'] != seg['speaker']:
                if _word_overlap(prev['text'], seg['text']) >= similarity_threshold:
                    log.debug("Echo dropped [%s @ %.1fs]: %s", seg['speaker'], seg['start'], seg['text'][:60])
                    is_echo = True
                    break
        if not is_echo:
            seg = dict(seg)
            seg['text'] = _remove_repeated_phrases(seg['text'])
            filtered.append(seg)
    return filtered


def _call_ollama(prompt: str) -> str:
    payload = {
        "model":  OLLAMA_MODEL,
        "prompt": prompt,
        "stream": False,
        "format": "json",          # ← forces Ollama to output valid JSON
        "options": {
            "temperature": 0.3,
            "num_predict": 2048,
        },
    }
    with httpx.Client(timeout=300) as client:
        r = client.post(f"{OLLAMA_BASE}/api/generate", json=payload)
        r.raise_for_status()
    return r.json()["response"]

def _analyse_with_llm(transcript: str, job_title: str, req: AnalyseRequest) -> dict:
    # Build context block from prior CV/GitHub analysis
    context_lines = []
    if req.candidate_name:
        context_lines.append(f"Candidate name: {req.candidate_name}")
    if req.candidate_summary:
        context_lines.append(f"CV summary: {req.candidate_summary}")
    if req.candidate_skills:
        context_lines.append(f"Skills listed on CV: {', '.join(req.candidate_skills)}")
    if req.github_score:
        context_lines.append(f"GitHub profile score: {req.github_score}")

    context_block = ""
    if context_lines:
        context_block = (
                "Prior information about this candidate from their CV and GitHub analysis:\n"
                + "\n".join(f"- {l}" for l in context_lines)
                + "\n\n"
        )

    prompt = f"""You are an expert technical interviewer and HR analyst.

{context_block}Below is the transcript of a job interview for the position: {job_title}

---
{transcript}
---

Analyse the interview and respond ONLY with a valid JSON object.
Do NOT include any text before or after the JSON.
Do NOT use markdown code fences.
Use ONLY double quotes for strings.
The JSON must have exactly these keys:

{{
  "summary": "2-3 sentence factual summary",
  "candidate_score": 7,
  "candidate_strengths": ["strength 1", "strength 2"],
  "candidate_weaknesses": ["weakness 1", "weakness 2"],
  "suggested_questions": ["question 1", "question 2"],
  "hiring_recommendation": "YES"
}}

hiring_recommendation must be exactly one of: STRONG_YES, YES, MAYBE, NO
candidate_score must be an integer between 1 and 10.
"""
    raw = _call_ollama(prompt)
    log.info("Raw LLM response: %s", raw[:500])

    cleaned = raw.strip()

    # Replace smart/curly quotes with straight quotes
    cleaned = cleaned.replace('\u201c', '"').replace('\u201d', '"')
    cleaned = cleaned.replace('\u2018', "'").replace('\u2019', "'")

    # Remove zero-width and other invisible characters
    cleaned = ''.join(ch for ch in cleaned if ch.isprintable() or ch in '\n\r\t')

    # Remove markdown fences
    cleaned = cleaned.removeprefix("```json").removeprefix("```").removesuffix("```").strip()

    # Extract JSON block
    start = cleaned.find('{')
    end = cleaned.rfind('}')
    if start != -1 and end != -1:
        cleaned = cleaned[start:end+1]

    cleaned = _sanitize_json_string(cleaned)
    log.info("Cleaned JSON to parse: %s", cleaned[:500])

    try:
        return json.loads(cleaned)
    except json.JSONDecodeError as e:
        log.error("Failed to parse JSON at position %d. Cleaned was:\n%s", e.pos, cleaned)
        # Log the exact character causing the issue
        if e.pos and e.pos < len(cleaned):
            log.error("Char at error position %d: %r (ord=%d)",
                      e.pos, cleaned[e.pos], ord(cleaned[e.pos]))
        raise


# ── Endpoint ──────────────────────────────────────────────────────────────────

@app.post("/analyse", response_model=AnalyseResponse)
def analyse(req: AnalyseRequest):
    base = Path(RECORDINGS_DIR) / req.interview_id
    recruiter_path = base / "recruiter.webm"
    candidate_path = base / "candidate.webm"

    if not recruiter_path.exists():
        raise HTTPException(status_code=404, detail="recruiter.webm not found")
    if not candidate_path.exists():
        raise HTTPException(status_code=404, detail="candidate.webm not found")

    try:
        recruiter_segs = _transcribe(
            recruiter_path, "recruiter",
            job_title=req.job_title,
            job_description=req.job_description or "",
            job_requirements=req.job_requirements,
        )
        candidate_segs = _transcribe(
            candidate_path, "candidate",
            job_title=req.job_title,
            job_description=req.job_description or "",
            job_requirements=req.job_requirements,
        )
    except Exception:
        log.error("Transcription failed:\n%s", traceback.format_exc())
        raise HTTPException(status_code=500, detail="Transcription failed")

    # Text-based echo filter applied before energy-based VAD
    all_segs = sorted(recruiter_segs + candidate_segs, key=lambda s: s["start"])
    all_segs = _filter_echo_segments(all_segs)
    recruiter_segs = [s for s in all_segs if s["speaker"] == "recruiter"]
    candidate_segs = [s for s in all_segs if s["speaker"] == "candidate"]

    try:
        transcript = _merge_by_vad(
            recruiter_path, candidate_path,
            recruiter_segs, candidate_segs,
            recruiter_joined_at=req.recruiter_joined_at,
            candidate_joined_at=req.candidate_joined_at,
        )
    except Exception:
        log.error("VAD merge failed:\n%s", traceback.format_exc())
        transcript = _merge_transcripts(recruiter_segs, candidate_segs)

    log.info("Merged transcript length: %d chars", len(transcript))

    try:
        llm_result = _analyse_with_llm(transcript, req.job_title, req)
    except json.JSONDecodeError as e:
        log.error("LLM returned non-JSON: %s", e)
        raise HTTPException(status_code=500, detail="LLM returned invalid JSON")
    except Exception:
        log.error("LLM call failed:\n%s", traceback.format_exc())
        raise HTTPException(status_code=500, detail="LLM analysis failed")

    return AnalyseResponse(transcript=transcript, **llm_result)
@app.post("/generate-questions", response_model=QuestionGenResponse)
def generate_questions(req: QuestionGenRequest):
    context_parts = []
    if req.candidate_name:
        context_parts.append(f"Candidate name: {req.candidate_name}")
    if req.candidate_summary:
        context_parts.append(f"CV summary: {req.candidate_summary}")
    if req.candidate_skills:
        context_parts.append(f"Skills listed on CV: {', '.join(req.candidate_skills)}")
    if req.github_score:
        context_parts.append(f"GitHub profile score: {req.github_score}")
    if req.github_frameworks:
        context_parts.append(f"Frameworks confirmed on GitHub: {', '.join(req.github_frameworks)}")
    if req.cv_weaknesses:
        context_parts.append(f"CV gaps or weaknesses flagged: {', '.join(req.cv_weaknesses)}")

    context_block = (
        "Candidate profile:\n" + "\n".join(f"- {p}" for p in context_parts) + "\n\n"
        if context_parts else ""
    )

    prompt = f"""You are a senior technical interviewer preparing for a job interview.

Position: {req.job_title}
{f"Job description: {req.job_description}" if req.job_description else ""}

{context_block}Generate interview questions in exactly 3 categories.
Respond ONLY with a valid JSON object, no explanation, no markdown fences.

{{
  "technical": [
    "question 1",
    "question 2",
    "question 3",
    "question 4",
    "question 5"
  ],
  "behavioral": [
    "question 1",
    "question 2",
    "question 3"
  ],
  "cv_specific": [
    "question 1",
    "question 2",
    "question 3"
  ]
}}

Rules:
- technical: 5 deep questions specific to this job and the candidate's claimed skills. Test actual depth, not definitions.
- behavioral: 3 situational or soft-skill questions relevant to this role.
- cv_specific: 3 questions that directly reference something concrete from their CV, projects, or GitHub. If GitHub confirmed certain skills, do not re-test those — focus on gaps or unverified claims.
- Each question must be a complete sentence ending with a question mark.
- Do not number the questions.
"""

    raw = _call_ollama(prompt)
    log.info("Raw question gen response: %s", raw[:300])

    cleaned = raw.strip()
    cleaned = cleaned.replace('\u201c', '"').replace('\u201d', '"')
    cleaned = cleaned.replace('\u2018', "'").replace('\u2019', "'")
    cleaned = ''.join(ch for ch in cleaned if ch.isprintable() or ch in '\n\r\t')
    cleaned = cleaned.removeprefix("```json").removeprefix("```").removesuffix("```").strip()

    start = cleaned.find('{')
    end   = cleaned.rfind('}')
    if start != -1 and end != -1:
        cleaned = cleaned[start:end+1]

    cleaned = _sanitize_json_string(cleaned)

    try:
        data = json.loads(cleaned)
    except json.JSONDecodeError as e:
        log.error("Question gen JSON parse failed: %s\nCleaned: %s", e, cleaned[:500])
        raise HTTPException(status_code=500, detail="LLM returned invalid JSON for questions")

    return QuestionGenResponse(
        technical=data.get("technical", []),
        behavioral=data.get("behavioral", []),
        cv_specific=data.get("cv_specific", []),
    )
def _sanitize_json_string(raw: str) -> str:
    """Remove control characters that break JSON parsing."""
    # Remove actual newlines/tabs inside JSON string values
    # Replace literal \n \r \t inside quoted strings with escaped versions
    result = []
    in_string = False
    escape_next = False
    for ch in raw:
        if escape_next:
            result.append(ch)
            escape_next = False
            continue
        if ch == '\\':
            escape_next = True
            result.append(ch)
            continue
        if ch == '"' and not escape_next:
            in_string = not in_string
            result.append(ch)
            continue
        if in_string and ch in '\n\r\t':
            # Replace bare control chars inside strings with space
            result.append(' ')
            continue
        result.append(ch)
    return ''.join(result)

@app.get("/health")
def health():
    return {"status": "ok", "whisper_model": WHISPER_MODEL, "ollama": OLLAMA_BASE}

def _merge_no_duplicates(recruiter_segs: list[dict],
                         candidate_segs: list[dict],
                         overlap_threshold: float = 1.5) -> str:
    """
    For each time window, if recruiter and candidate segments overlap,
    keep ONLY the one from the file where that person is the primary speaker.

    Key insight: if a segment appears in recruiter.webm, it was likely
    spoken by the recruiter (their mic picks them up louder).
    Same for candidate.webm. Echo/bleed segments are shorter and quieter
    so Whisper produces shorter text for them.

    Strategy: when two segments overlap, keep the LONGER one — it's from
    the actual speaker's file where their voice dominates.
    """
    all_segs = sorted(recruiter_segs + candidate_segs, key=lambda s: s["start"])
    deduped: list[dict] = []

    i = 0
    while i < len(all_segs):
        seg = all_segs[i]

        # Look ahead for overlapping segments from the OTHER speaker
        overlapping = []
        j = i + 1
        while j < len(all_segs):
            nxt = all_segs[j]
            # Check overlap
            if nxt["start"] < seg["end"] + overlap_threshold:
                if nxt["speaker"] != seg["speaker"]:
                    overlapping.append((j, nxt))
            else:
                break
            j += 1

        if overlapping:
            # Pick the segment with the most text — that's the real speaker
            candidates_pool = [(i, seg)] + overlapping
            best_idx, best_seg = max(candidates_pool, key=lambda x: len(x[1]["text"]))

            deduped.append(best_seg)

            # Skip all segments we just evaluated
            skip_indices = {idx for idx, _ in candidates_pool}
            while i in skip_indices or (i + 1 < len(all_segs) and i + 1 in skip_indices):
                i += 1
                if i >= len(all_segs):
                    break
            # advance past the group
            i = max(skip_indices) + 1
        else:
            deduped.append(seg)
            i += 1

    lines = []
    for seg in deduped:
        ts  = f"[{int(seg['start']//60):02d}:{int(seg['start']%60):02d}]"
        who = "Recruiter" if seg["speaker"] == "recruiter" else "Candidate"
        lines.append(f"{ts} {who}: {seg['text']}")
    return "\n".join(lines)
def _merge_by_energy(recruiter_segs: list[dict],
                     candidate_segs: list[dict]) -> str:
    """
    Instead of overlapping segments, assign each time window to exactly
    one speaker based on who has MORE text in that window.
    Chop timeline into 2-second buckets and pick the dominant speaker.
    """
    from collections import defaultdict

    bucket_size = 2.0  # seconds

    # Assign each segment to buckets
    rec_buckets: dict[int, list[str]] = defaultdict(list)
    can_buckets: dict[int, list[str]] = defaultdict(list)

    for seg in recruiter_segs:
        bucket = int(seg["start"] // bucket_size)
        rec_buckets[bucket].append(seg["text"])

    for seg in candidate_segs:
        bucket = int(seg["start"] // bucket_size)
        can_buckets[bucket].append(seg["text"])

    all_buckets = sorted(set(list(rec_buckets.keys()) + list(can_buckets.keys())))

    lines = []
    prev_speaker = None

    for bucket in all_buckets:
        rec_text = " ".join(rec_buckets.get(bucket, []))
        can_text = " ".join(can_buckets.get(bucket, []))

        if not rec_text and not can_text:
            continue

        # Pick speaker with more content in this window
        if len(rec_text) >= len(can_text) and rec_text:
            speaker = "recruiter"
            text = rec_text
        elif can_text:
            speaker = "candidate"
            text = can_text
        else:
            continue

        ts = f"[{int(bucket * bucket_size // 60):02d}:{int(bucket * bucket_size % 60):02d}]"
        who = "Recruiter" if speaker == "recruiter" else "Candidate"

        # Merge consecutive same-speaker buckets into one line
        if speaker == prev_speaker and lines:
            lines[-1] = lines[-1] + " " + text
        else:
            lines.append(f"{ts} {who}: {text}")

        prev_speaker = speaker

    return "\n".join(lines)
def _get_audio_energy(webm_path: Path, frame_duration_ms: int = 30) -> list[float]:
    """
    Convert webm to raw PCM and compute RMS energy per frame.
    Returns list of (timestamp_seconds, rms_energy) tuples.
    """
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp_path = tmp.name

    try:
        # Convert webm to 16kHz mono WAV using ffmpeg
        subprocess.run([
            "ffmpeg", "-y", "-i", str(webm_path),
            "-ar", "16000", "-ac", "1", "-f", "wav", tmp_path
        ], capture_output=True, check=True)

        with wave.open(tmp_path, 'rb') as wf:
            sample_rate = wf.getframerate()
            n_frames = wf.getnframes()
            raw = wf.readframes(n_frames)
            samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)

        frame_size = int(sample_rate * frame_duration_ms / 1000)
        energies = []
        for i in range(0, len(samples) - frame_size, frame_size):
            frame = samples[i:i + frame_size]
            rms = np.sqrt(np.mean(frame ** 2))
            timestamp = i / sample_rate
            energies.append((timestamp, rms))

        return energies
    finally:
        Path(tmp_path).unlink(missing_ok=True)


def _parse_iso(ts: str | None) -> float | None:
    """Parse ISO-8601 string → unix timestamp float, or None."""
    if not ts:
        return None
    try:
        # Handle both 'Z' suffix and '+00:00'
        ts = ts.replace("Z", "+00:00")
        return datetime.fromisoformat(ts).timestamp()
    except ValueError:
        return None
def _get_rms_timeline(webm_path: Path, frame_ms: int = ENERGY_FRAME_MS) -> dict[float, float]:
    """Convert webm → 16 kHz mono WAV, compute RMS per frame.
    Returns {timestamp_seconds_from_file_start: rms_value}."""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp_path = tmp.name
    try:
        subprocess.run(
            ["ffmpeg", "-y", "-i", str(webm_path),
             "-ar", "16000", "-ac", "1", "-f", "wav", tmp_path],
            capture_output=True, check=True
        )
        with wave.open(tmp_path, "rb") as wf:
            sr      = wf.getframerate()
            raw     = wf.readframes(wf.getnframes())
            samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)

        frame_size = int(sr * frame_ms / 1000)
        timeline: dict[float, float] = {}
        for i in range(0, len(samples) - frame_size, frame_size):
            t   = round(i / sr, 3)
            rms = float(np.sqrt(np.mean(samples[i:i + frame_size] ** 2)))
            timeline[t] = rms
        return timeline
    finally:
        Path(tmp_path).unlink(missing_ok=True)


def _shift_timeline(timeline: dict[float, float], offset_sec: float) -> dict[float, float]:
    """
    Shift all timestamps by offset_sec.
    A positive offset means this file started LATER than the reference,
    so we subtract to align it forward.
    """
    return {round(t + offset_sec, 3): rms for t, rms in timeline.items()}


def _window_max_rms(timeline: dict[float, float],
                    center: float,
                    half_window: float = ENERGY_WINDOW_SEC / 2) -> float:
    """Peak RMS in [center - half_window, center + half_window]."""
    lo, hi = center - half_window, center + half_window
    vals = [v for t, v in timeline.items() if lo <= t <= hi]
    return max(vals, default=0.0)


def _merge_by_vad(recruiter_path: Path, candidate_path: Path,
                  recruiter_segs: list[dict], candidate_segs: list[dict],
                  recruiter_joined_at: str | None = None,
                  candidate_joined_at: str | None = None) -> str:
    """
    Energy-ratio VAD with proper time alignment between the two recordings.

    Each webm file's timestamps start at 0 = the moment that person's
    browser started recording (when they joined).  If the recruiter joined
    5 s before the candidate, then recruiter t=10s == candidate t=5s.

    We shift timelines so both are in a common "interview time" space
    anchored to whoever joined first.
    """
    log.info("Building RMS timelines for VAD…")
    try:
        rec_tl = _get_rms_timeline(recruiter_path)
        can_tl = _get_rms_timeline(candidate_path)
    except Exception as exc:
        log.warning("RMS timeline failed (%s) — falling back to bucket merge", exc)
        return _merge_by_energy(recruiter_segs, candidate_segs)

    # ── Compute time offset ───────────────────────────────────────────────────
    rec_join = _parse_iso(recruiter_joined_at)
    can_join = _parse_iso(candidate_joined_at)

    if rec_join is not None and can_join is not None:
        # Positive = recruiter joined earlier; their file is longer at the start
        offset = rec_join - can_join   # seconds
        log.info("Join offset: recruiter joined %.2fs %s candidate",
                 abs(offset), "before" if offset < 0 else "after")
        if offset < 0:
            # Recruiter joined first → recruiter file has |offset| extra seconds at start
            # Shift candidate timeline forward so t=0 aligns with recruiter t=|offset|
            can_tl = _shift_timeline(can_tl, -offset)   # add |offset| to candidate times
        elif offset > 0:
            # Candidate joined first → shift recruiter timeline forward
            rec_tl = _shift_timeline(rec_tl, offset)
        # if offset == 0, no shift needed
    else:
        log.warning("Join timestamps missing — VAD may be inaccurate without time alignment")

    # ── Filter segments by energy ratio ──────────────────────────────────────
    confirmed: list[dict] = []

    for seg in sorted(recruiter_segs + candidate_segs, key=lambda s: s["start"]):
        mid = seg["start"] + (seg["end"] - seg["start"]) / 2
        sample_points = [seg["start"], mid, seg["end"]]

        rec_peak = max(_window_max_rms(rec_tl, t) for t in sample_points)
        can_peak = max(_window_max_rms(can_tl, t) for t in sample_points)

        is_recruiter_seg = seg["speaker"] == "recruiter"

        if rec_peak < 1.0 and can_peak < 1.0:
            # Both nearly silent — keep it, probably a quiet moment
            confirmed.append(seg)
            continue

        if is_recruiter_seg:
            ratio = rec_peak / (can_peak + 1e-6)
            keep  = ratio >= ENERGY_DOMINANCE_RATIO
        else:
            ratio = can_peak / (rec_peak + 1e-6)
            keep  = ratio >= ENERGY_DOMINANCE_RATIO

        if keep:
            confirmed.append(seg)
        else:
            log.debug(
                "Dropped echo [%s @ %.1fs] ratio=%.2f rec=%.1f can=%.1f: %s",
                seg["speaker"], seg["start"], ratio, rec_peak, can_peak,
                seg["text"][:60]
            )

    # ── Graceful fallback if filter is too aggressive ─────────────────────────
    total = len(recruiter_segs) + len(candidate_segs)
    kept  = len(confirmed)
    log.info("VAD kept %d / %d segments (%.0f%%)", kept, total, 100 * kept / max(total, 1))

    if kept < total * 0.3:
        log.warning(
            "VAD kept <30%% of segments — offset may be wrong or mics are too similar. "
            "Falling back to dominant-energy assignment (no ratio threshold)."
        )
        confirmed = []
        for seg in sorted(recruiter_segs + candidate_segs, key=lambda s: s["start"]):
            mid      = seg["start"] + (seg["end"] - seg["start"]) / 2
            rec_peak = max(_window_max_rms(rec_tl, t) for t in [seg["start"], mid])
            can_peak = max(_window_max_rms(can_tl, t) for t in [seg["start"], mid])
            dominant = "recruiter" if rec_peak >= can_peak else "candidate"
            if seg["speaker"] == dominant:
                confirmed.append(seg)

    # ── Merge consecutive same-speaker segments ───────────────────────────────
    merged: list[dict] = []
    for seg in confirmed:
        if (merged
                and merged[-1]["speaker"] == seg["speaker"]
                and seg["start"] - merged[-1]["end"] < 2.5):
            merged[-1]["text"] += " " + seg["text"]
            merged[-1]["end"]   = seg["end"]
        else:
            merged.append(dict(seg))

    lines = []
    for seg in merged:
        ts  = f"[{int(seg['start']//60):02d}:{int(seg['start']%60):02d}]"
        who = "Recruiter" if seg["speaker"] == "recruiter" else "Candidate"
        lines.append(f"{ts} {who}: {seg['text']}")
    return "\n".join(lines)