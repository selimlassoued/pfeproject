import json
import logging
import os
import re
import time
import traceback
import subprocess
import tempfile
from pathlib import Path

import httpx
import whisper
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from vocab import select_vocab, list_categories, get_category_vocab
from audio_arbitration import arbitrate_by_energy
from transcript_corrections import correct_segments, correct_text

# ── Logging ───────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s %(name)s | %(message)s",
)
log = logging.getLogger("analysis")

# ── App & config ──────────────────────────────────────────────────────────────
app = FastAPI(title="Interview Analysis Sidecar")

WHISPER_MODEL          = os.getenv("WHISPER_MODEL", "medium")
WHISPER_FALLBACK_MODEL = os.getenv("WHISPER_FALLBACK_MODEL", "medium")
WHISPER_LANGUAGE       = os.getenv("WHISPER_LANGUAGE", "en")
OLLAMA_BASE            = os.getenv("OLLAMA_BASE",   "http://ollama:11434")
OLLAMA_MODEL           = os.getenv("OLLAMA_MODEL",  "qwen2.5:3b")
RECORDINGS_DIR         = os.getenv("RECORDINGS_DIR", "/recordings")

# Confidence / filter knobs — tuned to KEEP candidate technical answers.
# Whisper's no_speech_prob is unreliable for quiet or technical speech: it
# regularly fires at 0.95+ on perfectly coherent multi-word sentences. So we
# don't trust no_speech_prob alone — a segment is only dropped as noise when
# BOTH the VAD score is high AND the text is too short to be meaningful (i.e.
# we trust word-count as a sanity check against false positives).
NO_SPEECH_DROP_THRESHOLD = float(os.getenv("NO_SPEECH_DROP_THRESHOLD", "0.90"))
NO_SPEECH_KEEP_MIN_WORDS = int(os.getenv("NO_SPEECH_KEEP_MIN_WORDS", "4"))
LOW_CONF_MERGE_THRESHOLD = float(os.getenv("LOW_CONF_MERGE_THRESHOLD", "0.60"))
MIN_SEGMENT_CHARS        = int(os.getenv("MIN_SEGMENT_CHARS", "2"))
MERGE_GAP_SEC            = float(os.getenv("MERGE_GAP_SEC", "3.0"))
ENERGY_RATIO_THRESHOLD   = float(os.getenv("ENERGY_RATIO_THRESHOLD", "1.25"))
AVG_LOGPROB_KEEP_FLOOR   = float(os.getenv("AVG_LOGPROB_KEEP_FLOOR", "-1.2"))

_whisper_model = None
_diarization_pipeline = None  # lazy — only used by the optional diarization path

# Hallucination patterns commonly produced by Whisper on silence / music.
_HALLUCINATION_PATTERNS = {
    "thank you for watching", "thanks for watching", "please subscribe",
    "subtitles by", "transcribed by",
}


# ── Whisper lifecycle ─────────────────────────────────────────────────────────
def _load_whisper(name: str):
    """Load a Whisper model by name. Raises on failure."""
    log.info("Loading Whisper model %r …", name)
    t0 = time.monotonic()
    model = whisper.load_model(name)
    log.info("Whisper model %r ready in %.1fs", name, time.monotonic() - t0)
    return model


def _get_whisper_model():
    """Return the cached Whisper model, loading it once on first use.

    Tries WHISPER_MODEL first; on failure falls back to WHISPER_FALLBACK_MODEL
    (default ``medium``). The model lives for the process lifetime — we do not
    unload it between requests (that was the root cause of multi-minute reload
    delays on every analysis).
    """
    global _whisper_model
    if _whisper_model is not None:
        return _whisper_model
    try:
        _whisper_model = _load_whisper(WHISPER_MODEL)
    except Exception as e:
        log.error("Primary model %r failed to load: %s", WHISPER_MODEL, e)
        if WHISPER_FALLBACK_MODEL and WHISPER_FALLBACK_MODEL != WHISPER_MODEL:
            log.warning("Falling back to %r", WHISPER_FALLBACK_MODEL)
            _whisper_model = _load_whisper(WHISPER_FALLBACK_MODEL)
        else:
            raise
    return _whisper_model


@app.on_event("startup")
def _preload_whisper():
    """Preload Whisper at startup so the first /analyse request isn't delayed."""
    try:
        _get_whisper_model()
    except Exception:
        log.exception("Whisper preload failed — first request will retry")


# ── Audio preprocessing ───────────────────────────────────────────────────────
def _to_wav(path: Path) -> Path:
    """Convert audio to a clean 16kHz mono wav for Whisper.

    Pipeline:
      1. Decode to 16k mono PCM.
      2. Highpass at 80 Hz to kill HVAC rumble / mains hum.
      3. Lowpass at 8 kHz — Whisper is trained on 16k audio so anything above
         Nyquist/2 is just aliasing fuel.
      4. EBU R128 loudness normalisation (loudnorm) to land at -16 LUFS, which
         is roughly the level Whisper was trained on. Quiet candidate audio is
         the #1 cause of "low-confidence drop" cascades.

    Returns a temp .wav path. Caller MUST delete it.
    """
    tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    tmp.close()
    af = "highpass=f=80,lowpass=f=8000,loudnorm=I=-16:TP=-1.5:LRA=11"
    cmd = [
        "ffmpeg", "-y", "-i", str(path),
        "-vn", "-ac", "1", "-ar", "16000",
        "-af", af,
        "-acodec", "pcm_s16le",
        tmp.name,
    ]
    t0 = time.monotonic()
    r = subprocess.run(cmd, capture_output=True, timeout=180)
    if r.returncode != 0:
        # Fallback to a plain decode in case the filter chain is unsupported
        # (very old ffmpeg builds without loudnorm).
        log.warning(
            "ffmpeg filtered conversion failed (rc=%d), retrying without filters. stderr=%s",
            r.returncode, r.stderr.decode(errors="ignore")[:300],
        )
        r = subprocess.run(
            ["ffmpeg", "-y", "-i", str(path), "-vn", "-ac", "1", "-ar", "16000",
             "-acodec", "pcm_s16le", tmp.name],
            capture_output=True, timeout=180,
        )
    if r.returncode != 0:
        Path(tmp.name).unlink(missing_ok=True)
        raise RuntimeError(f"ffmpeg conversion failed: {r.stderr.decode(errors='ignore')[:300]}")

    size_kb = Path(tmp.name).stat().st_size / 1024
    log.info("Converted %s → wav (%.1f KB) in %.1fs", path.name, size_kb, time.monotonic() - t0)
    return Path(tmp.name)


# ── Schemas ───────────────────────────────────────────────────────────────────
class AnalyseRequest(BaseModel):
    interview_id:        str
    job_title:           str
    job_description:     str | None = None
    job_requirements:    list[str] | None = None
    candidate_name:      str | None = None
    recruiter_name:      str | None = None
    candidate_skills:    list[str] | None = None
    candidate_summary:   str | None = None
    github_score:        str | None = None
    github_frameworks:   list[str] | None = None
    cv_weaknesses:       list[str] | None = None
    recruiter_joined_at: str | None = None
    candidate_joined_at: str | None = None
    # ── Semantic match handoff: pre-interview baseline the LLM will revise ──
    job_fit_score:                int | None = None      # 0-100
    pre_interview_recommendation: str | None = None      # STRONG_YES / YES / MAYBE / NO
    required_skills_matched:      list[str] | None = None
    required_skills_missing:      list[str] | None = None
    semantic_strengths:           list[str] | None = None
    semantic_weaknesses:          list[str] | None = None


class DimensionalScore(BaseModel):
    score:    int      # 0-100 for this dimension
    evidence: str      # one-sentence justification quoting the transcript


class AnalyseResponse(BaseModel):
    transcript:            str
    summary:               str
    candidate_score:       int                    # 1-10 (kept for backwards compat — derived from final_score)
    candidate_strengths:   list[str]
    candidate_weaknesses:  list[str]
    suggested_questions:   list[str]
    hiring_recommendation: str
    # ── Unified phase-by-phase scoring ──────────────────────────────────────
    pre_interview_score:   int | None = None      # echoed from job_fit_score (0-100)
    interview_delta:       int | None = None      # -20..+20 — what the interview added/removed
    final_score:           int | None = None      # 0-100 — clip(pre + delta), source of truth for ranking
    final_grade:           str | None = None      # A+ / A / B / C / D
    interview_verdict:     str | None = None      # CONFIRMED / RAISED / LOWERED / NEW
    dimensional_scores:    dict[str, DimensionalScore] | None = None


# ── Whisper prompts (per-speaker) ─────────────────────────────────────────────
def _vocab_block(
        job_title: str,
        job_requirements: list[str] | None,
        candidate_skills: list[str] | None,
        github_frameworks: list[str] | None,
) -> str:
    """Compact vocabulary block reused by both prompts. Whisper's initial_prompt
    is capped at ~224 tokens by the model, so we keep this tight."""
    terms: list[str] = []
    seen: set[str] = set()

    def _add(items: list[str] | None, limit: int) -> None:
        if not items:
            return
        for t in items[:limit]:
            key = t.lower()
            if key not in seen:
                seen.add(key)
                terms.append(t)

    _add(github_frameworks, 12)
    _add(candidate_skills, 20)
    _add(select_vocab(job_title, job_requirements), 60)

    # Hard-coded high-priority terms that Whisper consistently mangles.
    _add([
        "Java", "Spring Boot", "Spring Security", "Spring Data JPA",
        "Hibernate", "JPA", "PostgreSQL", "MySQL", "MongoDB", "Redis",
        "Docker", "Kubernetes", "Maven", "Gradle", "Flyway", "Liquibase",
        "microservices", "REST API", "GraphQL", "JWT", "Keycloak",
        "Kafka", "RabbitMQ", "Elasticsearch", "Jenkins", "GitHub Actions",
        "CI/CD", "AWS", "Azure", "GCP", "Linux", "Nginx",
        "fintech", "VERMEG",
    ], 32)

    return ", ".join(terms[:90])


def _build_recruiter_prompt(req: "AnalyseRequest | dict") -> str:
    g = (lambda k: getattr(req, k, None)) if not isinstance(req, dict) else req.get
    vocab = _vocab_block(g("job_title"), g("job_requirements"),
                         g("candidate_skills"), g("github_frameworks"))
    return (
        "Technical job interview at VERMEG, a fintech company building banking "
        "and insurance software. The speaker is the recruiter / interviewer "
        "asking concise technical questions about the candidate's experience. "
        f"Position: {g('job_title')}. "
        f"Technical vocabulary to expect: {vocab}."
    )


def _build_candidate_prompt(req: "AnalyseRequest | dict") -> str:
    g = (lambda k: getattr(req, k, None)) if not isinstance(req, dict) else req.get
    vocab = _vocab_block(g("job_title"), g("job_requirements"),
                         g("candidate_skills"), g("github_frameworks"))
    return (
        "Technical job interview at VERMEG, a fintech company. The speaker is "
        "the candidate, a backend software engineer giving detailed technical "
        "answers about Java, Spring Boot, microservices, databases, and DevOps. "
        f"Position they're applying for: {g('job_title')}. "
        f"Technical vocabulary to expect: {vocab}."
    )


# ── Transcription ─────────────────────────────────────────────────────────────
def _is_hallucination(text: str) -> bool:
    """Catch Whisper hallucination cascades and stock phrases.

    Hallucinations on quiet audio commonly take the form of:
      - A single token repeated ("IEE, IEE, IEE, …", "you you you")
      - Sentence-end stock phrases ("thank you for watching", "subtitles by")
      - Punctuation-padded repetition that defeats naive word-set checks
    We strip punctuation before the repetition check so "IEE, IEE, IEE" with
    only 3 split tokens still flags as duplicate.
    """
    t = text.lower().strip()
    if len(t) < MIN_SEGMENT_CHARS:
        return True
    if any(p in t for p in _HALLUCINATION_PATTERNS):
        return True
    # Strip punctuation for the repetition check so "IEE, IEE, IEE" counts.
    stripped = "".join(c if c.isalnum() or c.isspace() else " " for c in t)
    words = stripped.split()
    if len(words) >= 3 and len(set(words)) == 1:
        return True
    # ≥ 80% of tokens are the same word — classic stuck-loop hallucination.
    if len(words) >= 5:
        most_common = max(set(words), key=words.count)
        if words.count(most_common) / len(words) >= 0.8:
            return True
    return False


def _merge_adjacent_low_conf(segments: list[dict]) -> list[dict]:
    """Merge a low-confidence segment into a neighbour from the same speaker
    instead of dropping it. Whisper sometimes splits a single utterance across
    two segments where the joining frame has high no_speech_prob — dropping it
    creates jagged transcripts. Merging preserves the words."""
    if not segments:
        return segments
    merged: list[dict] = []
    for seg in segments:
        if not merged:
            merged.append(dict(seg))
            continue
        prev = merged[-1]
        gap = seg["start"] - prev["end"]
        is_low_conf = seg.get("no_speech_prob", 0) >= LOW_CONF_MERGE_THRESHOLD
        same_speaker = prev.get("speaker") == seg.get("speaker")
        if is_low_conf and same_speaker and gap < 1.5:
            prev["text"] = (prev["text"].rstrip() + " " + seg["text"].lstrip()).strip()
            prev["end"] = seg["end"]
            prev["no_speech_prob"] = min(
                prev.get("no_speech_prob", 0), seg.get("no_speech_prob", 1)
            )
        else:
            merged.append(dict(seg))
    return merged


def _transcribe(path: Path, speaker: str, prompt: str) -> list[dict]:
    log.info("Transcribing %s (speaker=%s)…", path.name, speaker)
    t_total = time.monotonic()
    wav_path = _to_wav(path)
    try:
        model = _get_whisper_model()
        log.debug("Whisper prompt for %s (%d chars): %s", speaker, len(prompt), prompt[:200])
        t0 = time.monotonic()
        result = model.transcribe(
            str(wav_path),
            language=WHISPER_LANGUAGE,
            initial_prompt=prompt,
            word_timestamps=True,
            fp16=False,
            no_speech_threshold=0.85,        # was 0.6 — too aggressive
            logprob_threshold=-1.2,          # was -1.0 — keep slightly noisier hypotheses
            compression_ratio_threshold=2.4, # default; trips fallback on repetition
            # Hallucination prevention: do NOT carry previous text into the next
            # chunk's prompt. On quiet / noisy audio Whisper occasionally locks
            # onto a stock phrase ("you you you", "IEE, IEE, IEE…"); with
            # conditioning ON, that phrase poisons every subsequent chunk and
            # the temperature-fallback ladder retries each chunk many times,
            # blowing up inference time without recovering real content.
            condition_on_previous_text=False,
            temperature=(0.0, 0.2, 0.4),
        )
        log.info("[%s] Whisper inference: %.1fs", speaker, time.monotonic() - t0)
    finally:
        wav_path.unlink(missing_ok=True)

    raw_segments = result.get("segments", [])
    kept: list[dict] = []
    dropped_hallu = 0
    dropped_noise = 0

    for seg in raw_segments:
        text = (seg.get("text") or "").strip()
        if not text:
            continue
        if _is_hallucination(text):
            dropped_hallu += 1
            log.debug("[%s] Hallucination drop: %r", speaker, text[:60])
            continue

        no_speech = seg.get("no_speech_prob", 0.0)
        avg_logprob = seg.get("avg_logprob", 0.0)
        word_count = len(text.split())

        # Only drop as noise if BOTH the VAD score is high AND the content is
        # too short to be a real answer AND Whisper's own per-token logprob
        # also looks bad. Real candidate sentences regularly score 0.95+ on
        # no_speech_prob — but if 4+ coherent words came out at decent
        # logprob, that *is* speech regardless of what the VAD says.
        looks_substantive = (
            word_count >= NO_SPEECH_KEEP_MIN_WORDS
            and avg_logprob > AVG_LOGPROB_KEEP_FLOOR
        )
        if no_speech >= NO_SPEECH_DROP_THRESHOLD and not looks_substantive:
            dropped_noise += 1
            log.info(
                "[%s] Noise drop (no_speech=%.2f logprob=%.2f words=%d): %r",
                speaker, no_speech, avg_logprob, word_count, text[:60],
            )
            continue
        if no_speech >= NO_SPEECH_DROP_THRESHOLD:
            log.info(
                "[%s] KEPT despite no_speech=%.2f (logprob=%.2f, %d words): %r",
                speaker, no_speech, avg_logprob, word_count, text[:60],
            )
        kept.append({
            "start": seg["start"],
            "end": seg["end"],
            "text": text,
            "speaker": speaker,
            "no_speech_prob": no_speech,
            "avg_logprob": avg_logprob,
        })

    # Glue together segments whose confidence is marginal — instead of dropping.
    merged = _merge_adjacent_low_conf(kept)

    log.info(
        "[%s] segments raw=%d kept=%d (hallucination_drops=%d noise_drops=%d) "
        "low_conf_merged=%d total=%.1fs",
        speaker, len(raw_segments), len(merged),
        dropped_hallu, dropped_noise, len(kept) - len(merged),
        time.monotonic() - t_total,
    )
    return merged


# ── Speaker display name ──────────────────────────────────────────────────────
def _display_name(speaker: str, recruiter_name: str | None, candidate_name: str | None) -> str:
    if speaker == "recruiter":
        return recruiter_name or "Interviewer"
    return candidate_name or "Candidate"


def _semantic_attribution_fix(
        segs: list[dict],
        candidate_name: str | None,
) -> list[dict]:
    """Content-based override for audio-attribution errors.

    When both mics capture both voices at similar quality, energy/spectral
    analysis can mis-label speakers. These rules catch high-confidence errors:
    - A "recruiter" segment that contains first-person technical self-description
      (only a candidate describes their own projects/experience) → flip to candidate.
    - A "candidate" segment that is clearly a recruiter question or closing phrase
      → flip to recruiter.
    """
    cname_first = ""
    if candidate_name:
        parts = candidate_name.strip().lower().split()
        cname_first = parts[0] if parts else ""

    # Phrases almost exclusively used by the candidate when describing themselves.
    _CANDIDATE_TELLS = [
        "my name is",
        "i've worked", "i have worked",
        "in my projects,", "in my project,",
        "i implemented", "i've implemented",
        "i built", "i've built",
        "i developed", "i've developed",
        "i designed", "i've designed",
        "i handled ", "i handle ",
        "i used ",        # "I used PostgreSQL/Docker/…" — tech usage by candidate
        "i use ",         # present-tense tech usage ("I use PostgreSQL…")
        "i try to stay",
        "my first priority",
        "i communicate clearly",
        "i communicate clear",
        "thank you for having me",
        "i appreciate the opportunity",
        "i look forward to",
        "i'm also very interested in database",
        "i'm also very interested in",
        "i focus on postmortem",
        "i focus on post-mortem",
        "i believe documentation",
        "i believe monitoring",
        "are essential in database",
    ]

    # Phrases that conclusively identify the RECRUITER even when a candidate
    # tell also appears in the same segment (anti-flip guard).
    _RECRUITER_OVERRIDES = [
        f"thank you, {cname_first}" if cname_first else "",
        f"good morning, {cname_first}" if cname_first else "",
        f"excellent. thank you" ,
        "well done",
    ]
    _RECRUITER_OVERRIDES = [p for p in _RECRUITER_OVERRIDES if p]

    # Phrases / patterns almost exclusively used by the recruiter.
    _RECRUITER_TELLS = [
        "thank you for joining us",
        "one final question",
        "that's all. thank you",
        "that's all, thank you",
        "from your projects",
        "i noticed from your",
        "looking at your",
    ]
    if cname_first:
        _RECRUITER_TELLS += [
            f"good morning, {cname_first}",
            f"thank you, {cname_first}",
            f"excellent. thank you, {cname_first}",
            f"thank you {cname_first}",
        ]

    # Recruiter question phrases — only flip if segment also contains "?".
    _RECRUITER_QUESTIONS = [
        "how do you handle", "how do you approach", "how do you manage",
        "can you tell me", "can you explain", "can you describe",
        "tell me about your", "tell me about a time",
        "what is your experience", "what experience do you have",
        "how did you ", "how did they ", "have you ever ", "could you ",
    ]

    flipped = 0
    for seg in segs:
        t = seg["text"].lower().strip()
        speaker = seg.get("speaker", "")

        if speaker == "recruiter":
            # Anti-flip guard: if the segment also contains a recruiter override
            # phrase (e.g. "Thank you, Zaina"), keep it as recruiter regardless.
            if any(ov in t for ov in _RECRUITER_OVERRIDES):
                continue
            if any(ind in t for ind in _CANDIDATE_TELLS):
                seg["speaker"] = "candidate"
                flipped += 1
                log.info(
                    "Semantic flip recruiter→candidate [@%.1f–%.1fs]: %r",
                    seg["start"], seg["end"], seg["text"][:70],
                )

        elif speaker == "candidate":
            is_q = "?" in seg["text"]
            if any(ind in t for ind in _RECRUITER_TELLS) or (
                is_q and any(q in t for q in _RECRUITER_QUESTIONS)
            ):
                seg["speaker"] = "recruiter"
                flipped += 1
                log.info(
                    "Semantic flip candidate→recruiter [@%.1f–%.1fs]: %r",
                    seg["start"], seg["end"], seg["text"][:70],
                )

    if flipped:
        log.info("Semantic attribution fixes: %d flip(s)", flipped)
    return segs


# Pronoun regex used to detect speaker-distinguishing markers in a fragment.
# If a fragment contains "I"/"my" it's almost certainly the candidate; if it
# contains "you"/"your" it's almost certainly the recruiter — so neither type
# is a safe continuation-absorption candidate.
_PRONOUN_RE = re.compile(
    r"\b(i|i've|i'm|i'll|i'd|my|you|your|you've|you're|you'll|you'd)\b",
    re.IGNORECASE,
)


def _absorb_continuation_fragments(segs: list[dict]) -> int:
    """Re-attribute mid-sentence continuation fragments to the previous speaker.

    Whisper occasionally splits a single utterance across two adjacent
    segments, and the energy-based attribution can land each half on a
    different speaker. When the previous segment ends mid-sentence (no
    terminal punctuation) and the next segment is short, free of first/second
    person pronouns, and not a question, it's almost certainly a continuation
    of the previous speaker — not a real speaker switch.

    Operates in-place on a list pre-sorted by start time. Returns the flip count.
    """
    if len(segs) < 2:
        return 0
    flipped = 0
    for i in range(1, len(segs)):
        seg = segs[i]
        prev = segs[i - 1]
        if seg.get("speaker") == prev.get("speaker"):
            continue
        gap = seg["start"] - prev["end"]
        if gap > 2.0:
            continue
        prev_text = prev["text"].rstrip()
        # Previous must end mid-sentence — terminal punctuation blocks absorption.
        if prev_text.endswith((".", "?", "!")):
            continue
        text = seg["text"].strip()
        # Cap on length: longer segments need stronger evidence than "no pronouns".
        if len(text.split()) > 15:
            continue
        # Questions identify the recruiter clearly — never absorb them.
        if "?" in text:
            continue
        # First/second person pronouns are strong speaker signals — skip.
        if _PRONOUN_RE.search(text):
            continue
        old_spk = seg["speaker"]
        seg["speaker"] = prev["speaker"]
        flipped += 1
        log.info(
            "Fragment absorb [@%.1f–%.1fs] %s→%s: %r (gap=%.1fs, prev='…%s')",
            seg["start"], seg["end"], old_spk, prev["speaker"],
            text[:60], gap, prev_text[-40:],
        )
    if flipped:
        log.info("Continuation fragment absorption: %d flip(s)", flipped)
    return flipped


def _trim_boundary_overlap(prev_text: str, next_text: str) -> str:
    """Trim a duplicate word-sequence from the end of prev_text when it
    repeats the start of next_text.

    Whisper's segment boundaries don't align between the two mic files, so
    when both files transcribe the same span of speech with slightly
    different start/end points and we end up keeping a segment from each,
    concatenating them produces a visible word-level duplication at the
    join (e.g. "...where I handled" + "where I handle schema design..." →
    "...where I handled where I handle schema design..."). This finds the
    longest exact or fuzzy (single tense-variant) overlap and trims it from
    prev_text so the merger emits one clean run.
    """
    prev_words = prev_text.split()
    next_words = next_text.split()
    if not prev_words or not next_words:
        return prev_text

    def _norm(w: str) -> str:
        return w.lower().strip('.,;:!?"\'')

    max_overlap = min(8, len(prev_words), len(next_words))
    for n in range(max_overlap, 0, -1):
        prev_tail = [_norm(w) for w in prev_words[-n:]]
        next_head = [_norm(w) for w in next_words[:n]]
        if prev_tail == next_head:
            return " ".join(prev_words[:-n])
        # Allow 1 tense-variant mismatch (e.g. "handled" vs "handle") when
        # both differing words share a 4-char stem — but only when there's
        # enough surrounding context (n >= 2) to make the match unambiguous.
        if n >= 2:
            mismatches = [(a, b) for a, b in zip(prev_tail, next_head) if a != b]
            if len(mismatches) == 1:
                a, b = mismatches[0]
                if len(a) >= 4 and len(b) >= 4 and a[:4] == b[:4]:
                    return " ".join(prev_words[:-n])
    return prev_text


# ── Merge / arbitration ───────────────────────────────────────────────────────
def _merge_segments(
        recruiter_segs: list[dict],
        candidate_segs: list[dict],
        recruiter_audio_path: Path,
        candidate_audio_path: Path,
        recruiter_name: str | None = None,
        candidate_name: str | None = None,
) -> tuple[str, dict]:
    """Spectral-arbitrate, interleave, and stringify.

    Returns (transcript_text, stats).
    """
    recruiter_clean, candidate_clean = arbitrate_by_energy(
        recruiter_segs, candidate_segs,
        recruiter_audio_path=recruiter_audio_path,
        candidate_audio_path=candidate_audio_path,
        min_energy_ratio=ENERGY_RATIO_THRESHOLD,
    )

    # Drop pre-interview noise: any segment that starts before the earliest
    # recruiter segment is almost certainly ambient audio captured before the
    # interviewer opened their mic. Use a 5-second grace window.
    # Use the ORIGINAL recruiter_segs (before energy arbitration) so that
    # candidate segments re-attributed to recruiter don't pull interview_start
    # earlier than the recruiter actually began speaking.
    interview_start = (min((s["start"] for s in recruiter_segs), default=0.0) - 5.0)
    recruiter_clean = [s for s in recruiter_clean if s["start"] >= interview_start]
    candidate_clean = [s for s in candidate_clean if s["start"] >= interview_start]

    # Content-based sanity check: fix obvious audio-attribution errors where
    # first-person technical descriptions ended up labeled as recruiter or
    # recruiter phrases ended up labeled as candidate.
    combined = _semantic_attribution_fix(
        recruiter_clean + candidate_clean, candidate_name
    )

    all_segs = sorted(combined, key=lambda s: s["start"])
    # After the semantic pass, sweep up mid-sentence continuation fragments
    # that the energy-based attribution split off to the wrong speaker.
    _absorb_continuation_fragments(all_segs)
    stats = {
        "recruiter_kept": len(recruiter_clean),
        "recruiter_total": len(recruiter_segs),
        "candidate_kept": len(candidate_clean),
        "candidate_total": len(candidate_segs),
    }
    if not all_segs:
        return "(no speech detected)", stats

    merged: list[dict] = []
    for seg in all_segs:
        if (merged
                and merged[-1]["speaker"] == seg["speaker"]
                and seg["start"] - merged[-1]["end"] < MERGE_GAP_SEC):
            trimmed = _trim_boundary_overlap(merged[-1]["text"], seg["text"])
            if trimmed != merged[-1]["text"]:
                log.info(
                    "Boundary trim: '…%s' + '%s…' → kept '%s…'",
                    merged[-1]["text"][-40:], seg["text"][:40], trimmed[-40:],
                )
            merged[-1]["text"] = (trimmed + " " + seg["text"]).strip() if trimmed else seg["text"]
            merged[-1]["end"]   = seg["end"]
        else:
            merged.append(dict(seg))

    # Post-process: fix Whisper's misspellings of technical proper nouns.
    corrections = correct_segments(merged)
    stats["corrections_applied"] = corrections
    log.info("Technical-term corrections applied: %d", corrections)

    lines = []
    for seg in merged:
        ts  = f"[{int(seg['start']//60):02d}:{int(seg['start']%60):02d}]"
        who = _display_name(seg["speaker"], recruiter_name, candidate_name)
        lines.append(f"{ts} {who}: {seg['text']}")
    return "\n".join(lines), stats


# ── LLM ───────────────────────────────────────────────────────────────────────
def _call_ollama(prompt: str) -> str:
    payload = {
        "model": OLLAMA_MODEL, "prompt": prompt, "stream": False, "format": "json",
        "options": {"temperature": 0.3, "num_predict": 2048, "num_ctx": 4096},
    }
    try:
        # 20-minute ceiling: a qwen2.5:7b generation on CPU can run several
        # minutes for a long prompt — the old 300s ceiling timed those out.
        with httpx.Client(timeout=1200) as client:
            r = client.post(f"{OLLAMA_BASE}/api/generate", json=payload)
            if r.status_code != 200:
                log.error("Ollama server error: %s", r.text)
                r.raise_for_status()
            return r.json()["response"]
    except Exception as e:
        log.error("Failed to reach Ollama: %s", e)
        raise


# ── Pre-interview scoring helpers ─────────────────────────────────────────────
# Map the GitHub enricher's categorical verdict to a numeric proxy used only
# when semantic-match's job_fit_score is unavailable. Tuned to land on
# recognisable letter-grade boundaries (see _letter_grade).
_GITHUB_SCORE_NUMERIC = {
    "STRONG":         85,
    "MODERATE":       60,
    "NO_PUBLIC_WORK": 25,
    "INACTIVE":       40,
    # RATE_LIMITED / None → no signal; fall back to skill-coverage heuristic.
}


def _derive_pre_interview_score(req: "AnalyseRequest") -> int:
    """The 0-100 baseline that the interview is going to *update*.

    Priority:
      1. Semantic-match job_fit_score (already a calibrated 0-100).
      2. GitHub score categorical mapping if semantic match hasn't run.
      3. Skill-coverage heuristic from `required_skills_matched/missing`.
      4. Neutral 50.
    """
    if req.job_fit_score is not None:
        return max(0, min(100, int(req.job_fit_score)))
    if req.github_score and req.github_score in _GITHUB_SCORE_NUMERIC:
        return _GITHUB_SCORE_NUMERIC[req.github_score]
    matched = len(req.required_skills_matched or [])
    missing = len(req.required_skills_missing or [])
    total = matched + missing
    if total > 0:
        return int(round(100 * matched / total))
    return 50


def _letter_grade(score: int) -> str:
    """Final ranking grade used by the UI's leaderboard column."""
    if score >= 90: return "A+"
    if score >= 80: return "A"
    if score >= 65: return "B"
    if score >= 50: return "C"
    return "D"


def _classify_verdict(delta: int) -> str:
    """How the interview shifted the pre-interview verdict.

    Used by the UI to label the result card. Caller passes "NEW" itself when
    there was no real pre-interview signal to shift.
    """
    if delta >= 5:  return "RAISED"
    if delta <= -5: return "LOWERED"
    return "CONFIRMED"


def _has_real_pre_signal(req: "AnalyseRequest") -> bool:
    """True when the pre-interview baseline is a genuine measurement rather
    than the neutral 50 fallback. When false, the interview is the primary
    verdict and its score is used directly (no calibration cap)."""
    return (req.job_fit_score is not None
            or bool(req.github_score)
            or bool(req.required_skills_matched)
            or bool(req.required_skills_missing))


# Dimension weights for the interview-alone score. A technical role weights
# demonstrated depth and reasoning highest, soft signals lowest. Must stay in
# sync with the frontend's copy in interview-evaluation.ts. Sums to 1.0.
_DIM_WEIGHTS = {
    "technical_depth":       0.24,
    "problem_solving":       0.20,
    "requirements_coverage": 0.18,
    "claim_verification":    0.16,
    "communication":         0.12,
    "motivation_fit":        0.10,
}

# The interview refines the pre-interview verdict but cannot swing it more
# than this many points — a single interview shouldn't fully erase or
# manufacture a CV+GitHub+semantic track record.
_INTERVIEW_DELTA_CAP = 25


def _build_context_block(req: AnalyseRequest) -> str:
    lines = []
    if req.candidate_name:    lines.append(f"Candidate name: {req.candidate_name}")
    if req.recruiter_name:    lines.append(f"Interviewer name: {req.recruiter_name}")
    if req.candidate_summary: lines.append(f"CV profile summary: {req.candidate_summary}")
    if req.candidate_skills:  lines.append(f"Technical skills on CV: {', '.join(req.candidate_skills)}")
    if req.github_score:      lines.append(f"GitHub profile strength: {req.github_score}")
    if req.github_frameworks:
        lines.append(f"Frameworks confirmed on GitHub (candidate genuinely uses these): "
                     f"{', '.join(req.github_frameworks)}")
    if req.cv_weaknesses:
        lines.append(f"Unverified / weak skills from CV (worth probing in interview): "
                     f"{', '.join(req.cv_weaknesses)}")
    # ── Pre-interview verdict — the LLM treats this as its baseline ────────
    if req.job_fit_score is not None:
        lines.append(f"PRE-INTERVIEW JOB FIT SCORE: {req.job_fit_score}/100 "
                     f"(produced by CV+GitHub+semantic-match analysis)")
    if req.pre_interview_recommendation:
        lines.append(f"PRE-INTERVIEW RECOMMENDATION: {req.pre_interview_recommendation}")
    if req.required_skills_matched:
        lines.append(f"Required skills the CV/GitHub already covered: "
                     f"{', '.join(req.required_skills_matched)}")
    if req.required_skills_missing:
        lines.append(f"Required skills the CV/GitHub did NOT cover (must probe in interview): "
                     f"{', '.join(req.required_skills_missing)}")
    if req.semantic_strengths:
        lines.append(f"Semantic-match strengths: {'; '.join(req.semantic_strengths)}")
    if req.semantic_weaknesses:
        lines.append(f"Semantic-match weaknesses: {'; '.join(req.semantic_weaknesses)}")
    if not lines:
        return ""
    return ("=== PRE-INTERVIEW CANDIDATE INTELLIGENCE ===\n"
            + "\n".join(f"  • {l}" for l in lines)
            + "\n============================================\n\n")


def _build_job_block(req: AnalyseRequest) -> str:
    parts = [f"Position: {req.job_title}"]
    if req.job_description:
        parts.append(f"Job description: {req.job_description[:600]}")
    if req.job_requirements:
        reqs = "\n".join(f"  - {r}" for r in req.job_requirements)
        parts.append(f"Job requirements:\n{reqs}")
    return "\n".join(parts)


def _analyse_with_llm(transcript: str, req: AnalyseRequest) -> dict:
    candidate_label = req.candidate_name or "the candidate"
    context_block   = _build_context_block(req)
    job_block       = _build_job_block(req)
    if len(transcript) > 8000:
        transcript = transcript[:4000] + "\n... [TRUNCATED] ...\n" + transcript[-4000:]

    pre_score = _derive_pre_interview_score(req)

    confirmed_note = ""
    if req.github_frameworks:
        confirmed_note = (
            f"\nNote: the skills {', '.join(req.github_frameworks)} are already "
            f"GitHub-confirmed. Assess whether the candidate demonstrated *depth* "
            f"on these topics, not just familiarity."
        )
    weakness_note = ""
    if req.cv_weaknesses:
        weakness_note = (
            f"\nNote: {', '.join(req.cv_weaknesses)} were flagged as unverified on the CV. "
            f"Pay special attention to whether the candidate addressed these convincingly."
        )
    missing_note = ""
    if req.required_skills_missing:
        missing_note = (
            f"\nNote: {', '.join(req.required_skills_missing)} are REQUIRED for this job "
            f"but were NOT covered by the CV or GitHub. Whether the candidate demonstrated "
            f"any of these in the interview should strongly affect requirements_coverage."
        )

    prompt = f"""You are a senior technical interviewer and HR analyst at VERMEG, \
a fintech company specialising in banking and insurance software platforms.

YOUR ROLE: This candidate has ALREADY been evaluated through CV parsing, GitHub
analysis, and semantic matching against the job description. That evaluation
produced the pre-interview baseline below. Your job is NOT to re-evaluate from
scratch — it is to decide what the interview REVEALED that should adjust that
baseline up or down. Think of yourself as the final stage in a multi-step
candidate-scoring pipeline.

{context_block}{job_block}
{confirmed_note}{weakness_note}{missing_note}

---
INTERVIEW TRANSCRIPT:
{transcript}
---

IMPORTANT: The transcript is labelled with speaker names. Each line is prefixed with the
speaker's name. Analyse ONLY {candidate_label}'s lines.

ATTRIBUTION NOTE: The recording used two separate microphones in the same room.
Occasionally a line may appear under the wrong speaker due to audio bleed-through.
If a line labelled as the candidate is actually a question TO the candidate
(e.g. "How do you handle…?") treat it as a mis-attributed interviewer question
and ignore it. If a line is technical gibberish, treat it as a transcription
artefact and do not quote it.

HOW TO SCORE — read carefully:
The CV, GitHub, and semantic match were ALREADY scored separately (baseline
{pre_score}/100). Your task is to score the INTERVIEW ITSELF. Judge ONLY what
{candidate_label} actually said in the transcript above — do not credit or
penalise them for anything that is only on their CV or GitHub.

Score each of the six dimensions on a precise 0-100 scale:
  88-100  Exceptional — expert-level, precise, with concrete specifics and nuance.
  72-87   Strong — solid and well-substantiated, clearly above average.
  55-71   Adequate — answers the question but stays generic, shallow, or has gaps.
  35-54   Weak — vague, partly incorrect, evasive, or thin.
  0-34    Poor — could not answer, fundamentally wrong, or ignored the question.

SCORING DISCIPLINE — this is the most important instruction:
  • Pick a PRECISE number (e.g. 63, 78, 49). NEVER default to round numbers like
    70/75/80 — the exact value must reflect the exact strength of the evidence.
  • Two answers of clearly different quality MUST get clearly different scores —
    a gap of 10 or more points. If your six dimensions all land within 8 points
    of each other, you are NOT discriminating; re-examine each against its
    evidence and spread them out.
  • Score ONLY on what the candidate concretely demonstrated in THIS transcript.
    A specific, detailed answer always outscores a vague or generic one.
  • "Adequate but generic" is 55-68 — NOT 75. Reserve 72+ only for answers that
    are genuinely substantive, specific, and well-reasoned.
  • If a dimension's topic genuinely never came up, score it 55 and say so.
  • Be exact: do not inflate to be kind, do not deflate to look rigorous.

The six dimensions — for EACH, write a 2-3 sentence assessment in `evidence`
that (a) names what the candidate concretely did, quoting a few of their words,
(b) names what was missing or weak, and (c) justifies the exact score:
  - technical_depth: depth and correctness on the technical topics discussed
  - problem_solving: analytical reasoning — how they break down and approach problems
  - requirements_coverage: how well their answers addressed THIS job's requirements
  - claim_verification: did they substantiate claimed skills with concrete detail?
  - communication: clarity, structure, and professionalism of their answers
  - motivation_fit: genuine interest in the role, growth mindset, and self-awareness

Respond with ONE valid JSON object — no markdown fences, no commentary. Every
<...> below is a PLACEHOLDER describing what to put there: replace each one with
your own value computed from THIS transcript. The placeholders contain NO real
data — there are deliberately no example numbers to copy.

{{
  "summary": "<detailed 4-6 sentence summary covering technical ability, problem-solving, communication, and overall fit>",
  "dimensional_scores": {{
    "technical_depth":      {{ "score": <precise integer 0-100>, "evidence": "<2-3 sentence assessment with a brief quote>" }},
    "problem_solving":      {{ "score": <precise integer 0-100>, "evidence": "<2-3 sentence assessment>" }},
    "requirements_coverage":{{ "score": <precise integer 0-100>, "evidence": "<2-3 sentence assessment>" }},
    "claim_verification":   {{ "score": <precise integer 0-100>, "evidence": "<2-3 sentence assessment>" }},
    "communication":        {{ "score": <precise integer 0-100>, "evidence": "<2-3 sentence assessment>" }},
    "motivation_fit":       {{ "score": <precise integer 0-100>, "evidence": "<2-3 sentence assessment>" }}
  }},
  "candidate_strengths": ["<concrete strength with a quote>", "<another strength>"],
  "candidate_weaknesses": ["<specific gap observed in the interview>", "<another gap>"],
  "suggested_questions": ["<follow-up targeting a remaining gap>", "<another question>"]
}}

Rules:
- Each dimensional score is a precise integer 0-100, computed INDEPENDENTLY for
  this candidate from their own answers. The six scores will differ between
  candidates — they describe THIS person, not a template.
- `evidence` is a 2-3 sentence assessment, not a single quote.
- strengths/weaknesses must quote or paraphrase what the candidate actually said.
- suggested_questions must target real remaining gaps, not topics already covered well.
"""
    log.info("LLM prompt (%d chars), pre_score=%d, first 500:\n%s",
             len(prompt), pre_score, prompt[:500])
    raw = _call_ollama(prompt)
    log.info("Raw LLM response: %s", raw[:500])
    return _parse_llm_json(raw)


def _finalize_scoring(req: AnalyseRequest, llm: dict) -> dict:
    """Turn the LLM's dimensional assessment into the unified phase-by-phase
    scores the UI consumes.

    The interview score is NOT a free-form guess — it is the weighted mean of
    the four evidence-backed dimensional scores (_DIM_WEIGHTS). The delta and
    final score are then derived deterministically from that, so the headline
    number always reflects the dimensional breakdown shown beneath it.

    Handles LLM-output drift defensively: bad/missing fields fall back to a
    neutral value rather than failing the whole analysis.
    """
    pre_score = _derive_pre_interview_score(req)
    has_pre   = _has_real_pre_signal(req)

    # ── Dimensional scores ────────────────────────────────────────────────
    # Clip into shape; a missing dimension defaults to the neutral 55 the
    # rubric tells the model to use for "topic never came up".
    _DIMS = ("technical_depth", "problem_solving", "requirements_coverage",
             "claim_verification", "communication", "motivation_fit")
    dim_raw = llm.get("dimensional_scores") or {}
    dimensional: dict[str, DimensionalScore] = {}
    for d in _DIMS:
        item = dim_raw.get(d) or {}
        try:
            s = int(item.get("score", 55))
        except (TypeError, ValueError):
            s = 55
        s = max(0, min(100, s))
        ev = str(item.get("evidence") or "").strip() or "(no specific evidence cited)"
        dimensional[d] = DimensionalScore(score=s, evidence=ev[:700])

    # ── Interview-alone score: weighted mean of the dimensions ────────────
    interview_score = round(sum(dimensional[d].score * w
                                for d, w in _DIM_WEIGHTS.items()))
    interview_score = max(0, min(100, interview_score))

    # ── Delta + final ─────────────────────────────────────────────────────
    if has_pre:
        # Calibration: the interview refines the pre-interview verdict but the
        # swing is capped, so one interview can't fully erase a track record.
        raw_delta = interview_score - pre_score
        delta = max(-_INTERVIEW_DELTA_CAP, min(_INTERVIEW_DELTA_CAP, raw_delta))
        final_score = max(0, min(100, pre_score + delta))
        verdict = _classify_verdict(delta)
    else:
        # No genuine pre-interview signal — the interview IS the verdict.
        delta = interview_score - pre_score
        final_score = interview_score
        verdict = "NEW"

    grade = _letter_grade(final_score)
    # Backwards-compat 1-10 score for any consumer still reading the old field.
    candidate_score_10 = max(1, min(10, round(final_score / 10)))

    # Recommendation is derived from the final score so it never contradicts it.
    rec = ("STRONG_YES" if final_score >= 85 else
           "YES"        if final_score >= 65 else
           "MAYBE"      if final_score >= 50 else
           "NO")

    log.info("Score finalised: pre=%d interview=%d (dims=%s) delta=%+d "
             "→ final=%d (%s, %s) | rec=%s",
             pre_score, interview_score,
             {d: dimensional[d].score for d in _DIMS},
             delta, final_score, grade, verdict, rec)

    return {
        "summary":               (llm.get("summary") or "").strip(),
        "candidate_score":       candidate_score_10,
        "candidate_strengths":   list(llm.get("candidate_strengths")  or []),
        "candidate_weaknesses":  list(llm.get("candidate_weaknesses") or []),
        "suggested_questions":   list(llm.get("suggested_questions")  or []),
        "hiring_recommendation": rec,
        "pre_interview_score":   pre_score,
        "interview_delta":       delta,
        "final_score":           final_score,
        "final_grade":           grade,
        "interview_verdict":     verdict,
        "dimensional_scores":    dimensional,
    }


# Deterministic safety net for the re-attribution LLM. These openers are
# unmistakably the interviewer addressing the candidate; the LLM occasionally
# mislabels them when they sit in a run of candidate lines.
_OBVIOUS_INTERVIEWER_OPENERS = (
    "can you ", "could you ", "how would you", "how familiar are you",
    "how do you", "how did you", "why are you", "tell me about",
    "walk me through", "what is your", "what are your", "what was your",
    "have you ever", "could you start", "thank you for joining",
)
# Unmistakable candidate self-introduction / closing phrases.
_OBVIOUS_CANDIDATE_TELLS = (
    "thank you for having me", "thank you for the opportunity",
    "i look forward to hearing", "i would describe myself",
)


def _obvious_role(text: str) -> "str | None":
    """Unmistakable speaker role from line content, or None when ambiguous.
    A deterministic backstop for the cases the re-attribution LLM slips on."""
    t = text.strip().lower()
    if any(t.startswith(p) for p in _OBVIOUS_INTERVIEWER_OPENERS):
        return "interviewer"
    if any(p in t for p in _OBVIOUS_CANDIDATE_TELLS):
        return "candidate"
    return None


def _correct_transcript_attribution(transcript: str, req: AnalyseRequest) -> str:
    """LLM-based speaker re-attribution.

    Dual-mic energy arbitration mislabels lines when both microphones capture
    both voices at similar levels — the energy/spectral heuristics and the
    phrase-based semantic fix don't generalise across recordings. The LLM
    decides who spoke each line from conversational logic far more reliably
    (questions → interviewer, first-person answers → candidate).

    It returns ONLY a per-line speaker label; we rebuild the transcript
    ourselves so the wording is never altered, dropped, or paraphrased. On any
    failure the original transcript is returned unchanged.
    """
    recruiter = req.recruiter_name or "Interviewer"
    candidate = req.candidate_name or "Candidate"
    cand_l = candidate.strip().lower()

    line_re = re.compile(r"^\s*(\[\d{1,2}:\d{2}\])\s*(.+?):\s*(.*)$")
    # rows: (timestamp, role, text). timestamp None → keep the line verbatim.
    rows: list[list] = []
    for ln in transcript.split("\n"):
        if not ln.strip():
            continue
        m = line_re.match(ln)
        if not m:
            rows.append([None, None, ln])
            continue
        ts, who, text = m.group(1), m.group(2).strip().lower(), m.group(3)
        role = "candidate" if cand_l and cand_l in who else "interviewer"
        rows.append([ts, role, text])

    speakable = [i for i, r in enumerate(rows) if r[0] is not None]
    if len(speakable) < 2:
        return transcript

    numbered = "\n".join(f"{i}. {rows[i][2]}" for i in speakable)
    prompt = f"""You are reconstructing the speaker labels of a job-interview transcript.
The audio came from two microphones in the same room, so the automatic labels
are unreliable. For EACH numbered line below decide who actually spoke it.

INTERVIEWER ({recruiter}) — asks questions, frames the role, says things like
"tell me about", "could you", "how familiar are you", "why are you interested",
"thank you for joining", refers to "your experience/profile", or greets the
candidate by name.

CANDIDATE ({candidate}) — answers questions, speaks in the first person about
their OWN experience and skills ("I worked on…", "my experience", "I would
describe myself", "I'm passionate about"), introduces themselves, or thanks the
interviewer for the opportunity.

The speakers are NOT strictly alternating — the same person often speaks
several consecutive lines. Judge each line by its content and the flow of the
conversation around it.

A line that starts with "Can you", "Could you", "How would you", "How familiar
are you", "Why are you", "Tell me about", or "Walk me through" is the
INTERVIEWER asking a question — even when it sits among candidate lines.

Lines:
{numbered}

Respond with ONE JSON object mapping every line number (as a string) to
exactly "interviewer" or "candidate". No other text:
{{"0":"interviewer","1":"candidate"}}"""

    try:
        raw = _call_ollama(prompt)
        mapping = _parse_llm_json(raw)
    except Exception:
        log.warning("Attribution-correction LLM call failed — keeping original labels")
        return transcript

    flips = 0
    for i in speakable:
        val = str(mapping.get(str(i), "")).strip().lower()
        if val in ("interviewer", "candidate") and val != rows[i][1]:
            rows[i][1] = val
            flips += 1
    log.info("LLM attribution correction: %d/%d line(s) relabelled",
             flips, len(speakable))

    # Deterministic backstop — override the unmistakable interviewer questions
    # and candidate self-introductions the LLM occasionally gets wrong.
    forced = 0
    for i in speakable:
        obvious = _obvious_role(rows[i][2])
        if obvious and obvious != rows[i][1]:
            rows[i][1] = obvious
            forced += 1
    if forced:
        log.info("Attribution backstop: %d obvious line(s) corrected", forced)

    # Rebuild — merge adjacent same-speaker lines into one for a clean read.
    merged: list[list] = []
    for ts, role, text in rows:
        if ts is not None and merged and merged[-1][0] is not None \
                and merged[-1][1] == role:
            merged[-1][2] = (merged[-1][2] + " " + text).strip()
        else:
            merged.append([ts, role, text])

    out = []
    for ts, role, text in merged:
        if ts is None:
            out.append(text)
        else:
            name = candidate if role == "candidate" else recruiter
            out.append(f"{ts} {name}: {text}")
    return "\n".join(out)


def _parse_llm_json(raw: str) -> dict:
    cleaned = raw.strip()
    cleaned = cleaned.replace('“', '"').replace('”', '"')
    cleaned = cleaned.replace('‘', "'").replace('’', "'")
    cleaned = ''.join(ch for ch in cleaned if ch.isprintable() or ch in '\n\r\t')
    cleaned = cleaned.removeprefix("```json").removeprefix("```").removesuffix("```").strip()
    start, end = cleaned.find('{'), cleaned.rfind('}')
    if start != -1 and end != -1:
        cleaned = cleaned[start:end + 1]
    cleaned = _sanitize_json_string(cleaned)
    try:
        return json.loads(cleaned)
    except json.JSONDecodeError as e:
        log.error("JSON parse failed: %s\n%s", e, cleaned[:300])
        raise


def _sanitize_json_string(raw: str) -> str:
    result, in_string, escape_next = [], False, False
    for ch in raw:
        if escape_next:
            result.append(ch); escape_next = False; continue
        if ch == '\\':
            escape_next = True; result.append(ch); continue
        if ch == '"':
            in_string = not in_string; result.append(ch); continue
        if in_string and ch in '\n\r\t':
            result.append(' '); continue
        result.append(ch)
    return ''.join(result)


# ── Endpoints ─────────────────────────────────────────────────────────────────
@app.post("/analyse", response_model=AnalyseResponse)
def analyse(req: AnalyseRequest):
    t_request = time.monotonic()
    log.info(
        "ANALYSE REQUEST: interview=%s job=%s candidate=%s github_score=%s "
        "frameworks=%s weaknesses=%s",
        req.interview_id, req.job_title, req.candidate_name,
        req.github_score, req.github_frameworks, req.cv_weaknesses,
    )
    base           = Path(RECORDINGS_DIR) / req.interview_id
    recruiter_path = base / "recruiter.webm"
    candidate_path = base / "candidate.webm"

    if not recruiter_path.exists():
        raise HTTPException(status_code=404, detail="recruiter.webm not found")
    if not candidate_path.exists():
        raise HTTPException(status_code=404, detail="candidate.webm not found")

    recruiter_prompt = _build_recruiter_prompt(req)
    candidate_prompt = _build_candidate_prompt(req)

    try:
        recruiter_segs = _transcribe(recruiter_path, "recruiter", recruiter_prompt)
        candidate_segs = _transcribe(candidate_path, "candidate", candidate_prompt)
    except Exception:
        log.error("Transcription failed:\n%s", traceback.format_exc())
        raise HTTPException(status_code=500, detail="Transcription failed")

    log.info(
        "Segments after per-file transcription — recruiter: %d, candidate: %d",
        len(recruiter_segs), len(candidate_segs),
    )

    try:
        transcript, merge_stats = _merge_segments(
            recruiter_segs, candidate_segs,
            recruiter_audio_path=recruiter_path,
            candidate_audio_path=candidate_path,
            recruiter_name=req.recruiter_name,
            candidate_name=req.candidate_name,
        )
    except Exception:
        log.error("Merge failed:\n%s", traceback.format_exc())
        raise HTTPException(status_code=500, detail="Transcript merge failed")

    log.info(
        "Final transcript: %d chars, %d lines, merge_stats=%s",
        len(transcript), transcript.count("\n") + 1, merge_stats,
    )

    # LLM-based speaker re-attribution — the energy/spectral arbitration above
    # mislabels lines on dual-mic recordings; the LLM corrects who spoke each
    # line from conversational logic before the transcript is scored.
    try:
        transcript = _correct_transcript_attribution(transcript, req)
    except Exception:
        log.warning("Attribution correction failed:\n%s", traceback.format_exc())

    log.debug("Transcript head:\n%s", transcript[:800])

    try:
        llm_result = _analyse_with_llm(transcript, req)
    except json.JSONDecodeError as e:
        log.error("LLM non-JSON: %s", e)
        raise HTTPException(status_code=500, detail="LLM returned invalid JSON")
    except Exception:
        log.error("LLM failed:\n%s", traceback.format_exc())
        raise HTTPException(status_code=500, detail="LLM analysis failed")

    # Combine LLM output with the CV+GitHub+semantic baseline into a single
    # phase-by-phase scoring response.
    finalised = _finalize_scoring(req, llm_result)
    log.info("ANALYSE COMPLETE in %.1fs", time.monotonic() - t_request)
    return AnalyseResponse(transcript=transcript, **finalised)


class QuestionGenRequest(BaseModel):
    job_title:          str
    job_description:    str | None = None
    candidate_name:     str | None = None
    candidate_skills:   list[str] | None = None
    candidate_summary:  str | None = None
    github_score:       str | None = None
    github_frameworks:  list[str] | None = None
    cv_weaknesses:      list[str] | None = None


class QuestionGenResponse(BaseModel):
    technical:   list[str]
    behavioral:  list[str]
    cv_specific: list[str]


@app.post("/generate-questions", response_model=QuestionGenResponse)
def generate_questions(req: QuestionGenRequest):
    context_parts = []
    if req.candidate_name:    context_parts.append(f"Candidate name: {req.candidate_name}")
    if req.candidate_summary: context_parts.append(f"CV summary: {req.candidate_summary}")
    if req.candidate_skills:  context_parts.append(f"Skills listed on CV: {', '.join(req.candidate_skills)}")
    if req.github_score:      context_parts.append(f"GitHub profile score: {req.github_score}")
    if req.github_frameworks: context_parts.append(f"Frameworks confirmed on GitHub: {', '.join(req.github_frameworks)}")
    if req.cv_weaknesses:     context_parts.append(f"CV gaps or weaknesses flagged: {', '.join(req.cv_weaknesses)}")
    context_block = (
        "Candidate profile:\n" + "\n".join(f"- {p}" for p in context_parts) + "\n\n"
        if context_parts else ""
    )
    prompt = f"""You are a senior technical interviewer at VERMEG, a fintech company \
specialising in banking and insurance software platforms.

Position: {req.job_title}
{f"Job description: {req.job_description}" if req.job_description else ""}

{context_block}Generate interview questions in exactly 3 categories.
Respond ONLY with a valid JSON object — no explanation, no markdown fences.

{{
  "technical": ["q1","q2","q3","q4","q5"],
  "behavioral": ["q1","q2","q3"],
  "cv_specific": ["q1","q2","q3"]
}}

Rules:
- technical: 5 deep questions specific to this job and confirmed skills. Test depth, not definitions.
- behavioral: 3 situational / soft-skill questions relevant to this role.
- cv_specific: 3 questions targeting CV gaps or unverified skills specifically.
- Each question must end with a question mark. No numbering.
"""
    raw = _call_ollama(prompt)
    try:
        data = _parse_llm_json(raw)
    except json.JSONDecodeError as e:
        log.error("Question gen JSON parse failed: %s", e)
        raise HTTPException(status_code=500, detail="LLM returned invalid JSON for questions")
    return QuestionGenResponse(
        technical=data.get("technical", []),
        behavioral=data.get("behavioral", []),
        cv_specific=data.get("cv_specific", []),
    )


@app.get("/vocab/categories")
def vocab_categories():
    return {"categories": list_categories()}


@app.get("/vocab/category/{category}")
def vocab_category(category: str):
    terms = get_category_vocab(category)
    if not terms:
        raise HTTPException(status_code=404, detail=f"Category '{category}' not found")
    return {"category": category, "term_count": len(terms), "terms": terms}


@app.get("/health")
def health():
    return {
        "status": "ok",
        "whisper_model": WHISPER_MODEL,
        "whisper_loaded": _whisper_model is not None,
        "ollama": OLLAMA_BASE,
    }
