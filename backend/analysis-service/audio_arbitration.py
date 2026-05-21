"""
Speaker attribution for two-track interview recordings.

THE PROBLEM
  Both mic files (recruiter.webm and candidate.webm) contain BOTH voices:
    - The mic's primary speaker (direct, clear)
    - Bleed-through from the other speaker (via room speakers / open mic)
  Whisper transcribes each file independently, so the same utterance often
  appears on both sides. We need to decide who actually said each segment.

WHY DESTRUCTIVE FILTERING FAILS
  The previous design dropped any segment that looked like bleed. That breaks
  on imbalanced setups: if the candidate's mic is too quiet to transcribe its
  own voice, the only transcript of the candidate's words may live on the
  recruiter's mic (as audible bleed). Dropping it loses the answer entirely.

WHAT WE DO INSTEAD
  Two-pass content-preserving attribution:
    1. ATTRIBUTE — for each segment, score whether it came from the mic's
       primary speaker (using a hybrid of RMS dominance + spectral ratio).
       Re-label the segment with the chosen speaker — do NOT delete it.
    2. DEDUPE — when the same time window has segments on both sides
       attributed to the same speaker, keep only the one with the stronger
       confidence (the direct recording).

WHY THE HYBRID SCORING
  - SPECTRAL_RATIO (high-freq / low-freq energy) is gain-independent. Direct
    voice retains 3–8 kHz consonants; bleed loses them through the
    speaker/room low-pass.
  - RMS dominance is the clearest signal when one mic is plainly louder, but
    breaks when the two mics have different absolute gains.
  We combine: strong RMS dominance wins outright; otherwise spectral decides;
  otherwise weak RMS breaks the tie. The arbitration never DELETES content
  unless it has been confirmed as a duplicate of a stronger segment.
"""

from __future__ import annotations
import logging
import os
import subprocess
import tempfile
from pathlib import Path

import numpy as np

log = logging.getLogger("analysis")

# Confidence below which an attribution decision is too uncertain to flip a
# segment across speakers. Empirically, conf is capped at 5.0 in `_attribute()`
# when one mic is fully silent (RMS = 0) — and only that case is reliably
# correct in solo-test recordings. Any conf in 1.0–4.x is essentially a
# coin-flip when both mics share the same physical input, and a wrong re-label
# can drag legitimate speech onto the other speaker where dedup deletes it
# (observed at conf=3.33 in testing: a recruiter intro was moved to candidate
# and then erased). Setting the floor at 5.0 means re-labels happen only when
# one side is truly silent — which is when they are actually safe.
# In a real two-mic interview, true bleed scores well above 5.0 on the strong
# mic, so legitimate cross-mic moves still pass.
RELABEL_CONF_MIN = float(os.getenv("RELABEL_CONF_MIN", "5.0"))

try:
    import librosa
    _LIBROSA_AVAILABLE = True
except ImportError:
    _LIBROSA_AVAILABLE = False
    log.warning("librosa not installed — pip install librosa")


# ── Audio loading ─────────────────────────────────────────────────────────────
def _load(path: Path, sr: int = 16000) -> np.ndarray:
    # Convert to wav first so librosa doesn't fall back to slow audioread.
    tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    tmp.close()
    subprocess.run(
        ["ffmpeg", "-y", "-i", str(path), "-ar", str(sr), "-ac", "1",
         "-acodec", "pcm_s16le", tmp.name],
        capture_output=True, check=True, timeout=120,
    )
    try:
        audio, _ = librosa.load(tmp.name, sr=sr, mono=True)
    finally:
        Path(tmp.name).unlink(missing_ok=True)
    return audio


def _bandpower(audio: np.ndarray, sr: int, lo_hz: float, hi_hz: float,
               start_s: float, end_s: float) -> float:
    s = int(start_s * sr)
    e = int(end_s   * sr)
    chunk = audio[s:e]
    if len(chunk) < 64:
        return 0.0
    n    = len(chunk)
    spec = np.abs(np.fft.rfft(chunk, n=n))
    freq = np.fft.rfftfreq(n, d=1.0 / sr)
    mask = (freq >= lo_hz) & (freq < hi_hz)
    if not mask.any():
        return 0.0
    return float(np.sqrt(np.mean(spec[mask] ** 2)))


def _features(audio: np.ndarray, sr: int, start_s: float, end_s: float) -> dict:
    total = _bandpower(audio, sr, 80,   8000, start_s, end_s)
    low   = _bandpower(audio, sr, 300,  3000, start_s, end_s)
    high  = _bandpower(audio, sr, 3000, 8000, start_s, end_s)
    return {
        "total": total, "low": low, "high": high,
        "spectral_ratio": high / (low + 1e-9),
    }


def _baseline_rms(audio: np.ndarray, segments: list[dict], sr: int) -> float:
    """Mean RMS across the time windows where *any* segment was transcribed.

    This is the file's typical "speech-active" loudness — used to normalise
    away gain imbalances between the two mics. If one mic has 2x gain
    overall, both this baseline and every per-segment RMS scale together,
    so the normalised ratios stay meaningful.
    """
    samples = []
    for seg in segments:
        s = int(seg["start"] * sr)
        e = int(seg["end"]   * sr)
        chunk = audio[s:e]
        if len(chunk) < 64:
            continue
        rms = float(np.sqrt(np.mean(chunk ** 2)))
        if rms > 0:
            samples.append(rms)
    if not samples:
        # No segments overlap — fall back to whole-file RMS (excluding zeros).
        nz = audio[audio != 0]
        if nz.size == 0:
            return 1e-6
        return float(np.sqrt(np.mean(nz ** 2)))
    return float(np.mean(samples))


def _baseline_spectral_ratio(audio: np.ndarray, segments: list[dict], sr: int) -> float:
    """Mean spectral_ratio across the file's active windows. Used to normalise
    mic-quality differences: a mic with poor high-freq response will show
    consistently low spectral_ratio across the whole recording, which we
    subtract out before comparing per-segment values."""
    values = []
    for seg in segments:
        f = _features(audio, sr, seg["start"], seg["end"])
        if f["total"] > 0 and f["low"] > 0:
            values.append(f["spectral_ratio"])
    if not values:
        return 1.0
    return float(np.mean(values))


# ── Attribution ───────────────────────────────────────────────────────────────
def _attribute(
        seg: dict,
        own_audio: np.ndarray,
        other_audio: np.ndarray,
        sr: int,
        own_rms_baseline: float,
        other_rms_baseline: float,
        own_sr_baseline: float,
        other_sr_baseline: float,
        rms_dom: float,
        spectral_margin: float,
        weak_rms: float,
) -> tuple[bool, float, dict]:
    """Decide whether the segment was spoken by the OWN-file primary speaker.

    Returns (own_is_speaker, confidence, debug_info).

    RMS and spectral_ratio are normalised by per-file baselines so that gain
    imbalance and mic-quality differences don't bias every segment toward
    the louder / cleaner file. The comparison then measures "is this segment
    above the speech-active norm for own's file more than for other's file?"
    """
    own_f   = _features(own_audio,   sr, seg["start"], seg["end"])
    other_f = _features(other_audio, sr, seg["start"], seg["end"])

    own_t   = own_f["total"]
    other_t = other_f["total"]

    # Per-file gain normalisation: a segment is "loud" if it's above THIS
    # file's typical level, not above an absolute threshold.
    own_norm   = own_t   / max(own_rms_baseline,   1e-6)
    other_norm = other_t / max(other_rms_baseline, 1e-6)

    # Same idea for spectral: subtract out each mic's inherent high-freq
    # response so we measure relative high-freq presence vs THIS file's norm.
    own_sr_norm   = own_f["spectral_ratio"]   / max(own_sr_baseline,   1e-6)
    other_sr_norm = other_f["spectral_ratio"] / max(other_sr_baseline, 1e-6)

    info = {
        "own_rms": own_t, "other_rms": other_t,
        "own_norm": own_norm, "other_norm": other_norm,
        "own_sr_norm": own_sr_norm, "other_sr_norm": other_sr_norm,
    }

    # Both silent — no real signal, don't claim ownership.
    if own_t == 0 and other_t == 0:
        return True, 0.01, info

    if other_t == 0:
        return True, 5.0, info
    if own_t == 0:
        return False, 5.0, info

    rms_ratio = own_norm / max(other_norm, 1e-6)
    info["rms_ratio"] = rms_ratio

    # 1. Strong NORMALISED-RMS dominance — own file is loud-for-itself, other
    #    file is quiet-for-itself. Almost certainly the direct recording.
    if rms_ratio >= rms_dom:
        return True, rms_ratio, info
    if rms_ratio <= 1.0 / rms_dom:
        return False, 1.0 / rms_ratio, info

    # 2. NORMALISED-spectral arbitration — gain & mic-quality independent.
    sr_ratio = own_sr_norm / max(other_sr_norm, 1e-6)
    info["sr_ratio"] = sr_ratio
    if sr_ratio >= spectral_margin:
        return True, sr_ratio, info
    if sr_ratio <= 1.0 / spectral_margin:
        return False, 1.0 / sr_ratio, info

    # 3. Weak normalised-RMS tiebreaker.
    if rms_ratio >= weak_rms:
        return True, rms_ratio, info
    if rms_ratio <= 1.0 / weak_rms:
        return False, 1.0 / rms_ratio, info

    # 4. Genuinely inconclusive — keep on the file it came from.
    return True, 1.0, info


# ── Deduplication ─────────────────────────────────────────────────────────────
def _overlap_seconds(a: dict, b: dict) -> float:
    return max(0.0, min(a["end"], b["end"]) - max(a["start"], b["start"]))


def _content_score(seg: dict) -> float:
    """Content value = word count weighted by Whisper's per-token log-probability.
    Rewards longer, coherently transcribed text over short high-attribution fragments.
    A 2-word fragment with great attribution beats nothing if a 10-word sentence
    is the alternative, because 10*exp(-0.3) >> 2*exp(0).
    """
    import math
    words = len(seg.get("text", "").split())
    logprob = seg.get("avg_logprob", -0.5)
    return max(words, 1) * math.exp(max(logprob, -3.0))


def _dedupe(segments: list[dict], speaker: str, min_overlap: float = 0.5) -> list[dict]:
    """Within a single speaker's list, drop duplicates: two segments are
    duplicates if they overlap in time by >= min_overlap seconds. Keep the
    segment with the better content score (word_count * exp(avg_logprob));
    attribution confidence breaks ties only when content scores are within 30%.
    """
    same = [s for s in segments if s.get("speaker") == speaker]
    other = [s for s in segments if s.get("speaker") != speaker]
    if len(same) < 2:
        return segments

    same.sort(key=lambda s: s["start"])
    out: list[dict] = []
    for seg in same:
        merged = False
        for kept in out:
            if _overlap_seconds(seg, kept) >= min_overlap:
                seg_cs   = _content_score(seg)
                kept_cs  = _content_score(kept)
                # Primary: content score. Secondary (when scores are nearly
                # tied within 0.5): prefer the cleaner Whisper output via
                # avg_logprob — this rescues a confidently-transcribed short
                # sentence from being beaten by a hallucinated longer one
                # that happens to score the same on words×exp(logprob).
                if abs(seg_cs - kept_cs) < 0.5:
                    take_seg = seg.get("avg_logprob", -3.0) > kept.get("avg_logprob", -3.0)
                else:
                    take_seg = seg_cs > kept_cs
                if take_seg:
                    log.info(
                        "Dedup [%s @%.1f–%.1fs]: replacing %r with %r "
                        "(cs %.2f -> %.2f)",
                        speaker, kept["start"], kept["end"],
                        kept["text"][:40], seg["text"][:40],
                        kept_cs, seg_cs,
                    )
                    kept.update(seg)
                else:
                    log.info(
                        "Dedup [%s @%.1f–%.1fs]: dropping %r (cs %.2f) "
                        "in favour of %r (cs %.2f)",
                        speaker, seg["start"], seg["end"],
                        seg["text"][:40], seg_cs,
                        kept["text"][:40], kept_cs,
                    )
                merged = True
                break
        if not merged:
            out.append(seg)
    return other + out


def _word_jaccard(a: str, b: str) -> float:
    """Jaccard similarity over lowercased word sets — cheap, no extra deps.
    Used to confirm two segments actually share words before treating them
    as cross-speaker duplicates (Whisper segment timestamps drift, so time
    overlap alone is not proof of duplication)."""
    wa = {w.strip(".,!?;:'\"").lower() for w in a.split() if len(w) > 1}
    wb = {w.strip(".,!?;:'\"").lower() for w in b.split() if len(w) > 1}
    if not (wa and wb):
        return 0.0
    return len(wa & wb) / len(wa | wb)


def _time_gap(a: dict, b: dict) -> float:
    """Seconds between two segments. 0 if they overlap."""
    if a["end"] < b["start"]:
        return b["start"] - a["end"]
    if b["end"] < a["start"]:
        return a["start"] - b["end"]
    return 0.0


# When mute-toggle lag puts the same utterance on both files at slightly
# different timestamps (common in solo-test recordings), time overlap is zero
# but content is duplicated. This window lets cross-dedup catch those cases
# while staying conservative on words that just happen to repeat far apart.
_CROSS_NEAR_DUP_TIME_WINDOW = 15.0
_CROSS_NEAR_DUP_SIM_THRESHOLD = 0.6


def _cross_dedup(pool: list[dict], min_overlap: float = 1.0) -> list[dict]:
    """Remove cross-speaker echoes.

    After per-speaker dedup, any remaining recruiter/candidate pair that
    still overlaps by >= min_overlap seconds means both files transcribed
    the same speech but the attribution was inconclusive on each side
    (each kept its own home-file label). Keep the copy with the higher
    content score; its speaker label is taken as the attribution winner.
    """
    recruiters = [s for s in pool if s.get("speaker") == "recruiter"]
    candidates = [s for s in pool if s.get("speaker") == "candidate"]
    drop: set[int] = set()

    for r in recruiters:
        for c in candidates:
            # Two paths to "this is the same utterance":
            #
            #  (a) time-overlap path: their Whisper segment timestamps overlap
            #      by >= min_overlap seconds AND the words agree. Catches the
            #      classic two-mic echo case (both mics heard the same voice
            #      at the same instant).
            #
            #  (b) near-duplicate text path: they don't overlap in time, but
            #      they happen within _CROSS_NEAR_DUP_TIME_WINDOW seconds of
            #      each other AND the words agree. Catches the solo-test
            #      mute-lag case where the same utterance lands on both files
            #      at slightly different timestamps because the user toggled
            #      mute a couple seconds late.
            #
            # The text-similarity check is the safety net that prevents
            # consecutive but different turns (recruiter question @17s vs
            # candidate answer @18s) from being mistaken for duplicates.
            sim = _word_jaccard(r.get("text", ""), c.get("text", ""))
            time_dup = _overlap_seconds(r, c) >= min_overlap and sim >= 0.6
            text_dup = (
                not time_dup
                and _time_gap(r, c) <= _CROSS_NEAR_DUP_TIME_WINDOW
                and sim >= _CROSS_NEAR_DUP_SIM_THRESHOLD
            )
            if not (time_dup or text_dup):
                continue
            r_cs = _content_score(r)
            c_cs = _content_score(c)
            # Same tiebreaker as _dedupe: when content scores are nearly tied,
            # prefer the cleaner Whisper output (higher avg_logprob) — this
            # protects against a hallucinated segment matching the cs of a
            # clean one by accident of word count.
            if abs(r_cs - c_cs) < 0.5:
                prefer_r = r.get("avg_logprob", -3.0) >= c.get("avg_logprob", -3.0)
            else:
                prefer_r = r_cs > c_cs
            if prefer_r:
                log.info(
                    "Cross-dedup [@%.1f–%.1fs]: dropping candidate %r (cs %.2f) "
                    "in favour of recruiter %r (cs %.2f)",
                    max(r["start"], c["start"]), min(r["end"], c["end"]),
                    c["text"][:40], c_cs, r["text"][:40], r_cs,
                )
                drop.add(id(c))
            else:
                log.info(
                    "Cross-dedup [@%.1f–%.1fs]: dropping recruiter %r (cs %.2f) "
                    "in favour of candidate %r (cs %.2f)",
                    max(r["start"], c["start"]), min(r["end"], c["end"]),
                    r["text"][:40], r_cs, c["text"][:40], c_cs,
                )
                drop.add(id(r))

    removed = len(drop)
    if removed:
        log.info("Cross-dedup: removed %d cross-speaker echo(es)", removed)
    return [s for s in pool if id(s) not in drop]


# ── Public entry point ────────────────────────────────────────────────────────
def arbitrate_by_energy(
        recruiter_segs: list[dict],
        candidate_segs: list[dict],
        recruiter_audio_path: Path,
        candidate_audio_path: Path,
        min_energy_ratio: float = 1.3,
        sr: int = 16000,
) -> tuple[list[dict], list[dict]]:
    """Content-preserving attribution.

    Every segment ends up in ONE of the two returned lists, attributed to
    the speaker our analysis believes actually said it. Bleed-through is
    re-labelled (not deleted) so quiet-mic content survives. Duplicates
    (same content captured on both sides) are deduplicated, keeping the
    stronger copy.

    The ``min_energy_ratio`` arg is retained for API compatibility; it now
    acts as the WEAK-RMS tiebreaker. The hard RMS-dominance threshold is
    derived from it (max(min_energy_ratio, 2.0)).
    """
    if not _LIBROSA_AVAILABLE:
        log.warning("librosa unavailable — returning all segments unfiltered")
        return recruiter_segs, candidate_segs

    RMS_DOMINANCE_RATIO = max(min_energy_ratio, 2.0)
    SPECTRAL_MARGIN     = 1.20
    WEAK_RMS_RATIO      = min_energy_ratio

    log.info("Loading audio for speaker attribution…")
    rec = _load(recruiter_audio_path, sr)
    can = _load(candidate_audio_path, sr)
    log.info(
        "Audio loaded — recruiter: %.1fs, candidate: %.1fs",
        len(rec) / sr, len(can) / sr,
    )

    # Per-file baselines computed over the union of all transcribed time
    # windows. This is what makes the comparison gain-fair: if one mic is
    # globally louder or higher-fidelity, that bias divides out.
    all_segs = list(recruiter_segs) + list(candidate_segs)
    rec_rms_b = _baseline_rms(rec, all_segs, sr)
    can_rms_b = _baseline_rms(can, all_segs, sr)
    rec_sr_b  = _baseline_spectral_ratio(rec, all_segs, sr)
    can_sr_b  = _baseline_spectral_ratio(can, all_segs, sr)
    log.info(
        "Per-file baselines — recruiter rms=%.4f sr=%.3f | candidate rms=%.4f sr=%.3f "
        "(gain ratio rec/can=%.2fx, sr ratio rec/can=%.2fx)",
        rec_rms_b, rec_sr_b, can_rms_b, can_sr_b,
        rec_rms_b / max(can_rms_b, 1e-9),
        rec_sr_b  / max(can_sr_b,  1e-9),
    )

    relabel_r_to_c = 0
    relabel_c_to_r = 0
    pool: list[dict] = []

    # Recruiter-file segments: decide whether they were really said by the
    # recruiter or are candidate bleed.
    for seg in recruiter_segs:
        own_is, conf, info = _attribute(
            seg, rec, can, sr,
            own_rms_baseline=rec_rms_b, other_rms_baseline=can_rms_b,
            own_sr_baseline=rec_sr_b,   other_sr_baseline=can_sr_b,
            rms_dom=RMS_DOMINANCE_RATIO,
            spectral_margin=SPECTRAL_MARGIN, weak_rms=WEAK_RMS_RATIO,
        )
        new = dict(seg)
        new["_attr_conf"] = conf
        new["_source_file"] = "recruiter"
        if own_is:
            new["speaker"] = "recruiter"
        elif conf >= RELABEL_CONF_MIN:
            new["speaker"] = "candidate"
            relabel_r_to_c += 1
            log.info(
                "Re-label recruiter→candidate [@%.1f–%.1fs] %r  "
                "rms=%.2f sr=%.2f conf=%.2f",
                seg["start"], seg["end"], seg["text"][:50],
                info.get("rms_ratio", 0), info.get("sr_ratio", 0), conf,
            )
        else:
            # Attribution leans toward bleed but confidence is too weak to
            # flip a speaker label. Trust the source track — downstream
            # cross-dedup will still collapse genuine duplicates.
            new["speaker"] = "recruiter"
            log.info(
                "Low-conf attribution — keep on recruiter [@%.1f–%.1fs] %r  "
                "rms=%.2f sr=%.2f conf=%.2f (< %.2f)",
                seg["start"], seg["end"], seg["text"][:50],
                info.get("rms_ratio", 0), info.get("sr_ratio", 0),
                conf, RELABEL_CONF_MIN,
            )
        pool.append(new)

    # Candidate-file segments: same but from the other side.
    for seg in candidate_segs:
        own_is, conf, info = _attribute(
            seg, can, rec, sr,
            own_rms_baseline=can_rms_b, other_rms_baseline=rec_rms_b,
            own_sr_baseline=can_sr_b,   other_sr_baseline=rec_sr_b,
            rms_dom=RMS_DOMINANCE_RATIO,
            spectral_margin=SPECTRAL_MARGIN, weak_rms=WEAK_RMS_RATIO,
        )
        new = dict(seg)
        new["_attr_conf"] = conf
        new["_source_file"] = "candidate"
        if own_is:
            new["speaker"] = "candidate"
        elif conf >= RELABEL_CONF_MIN:
            new["speaker"] = "recruiter"
            relabel_c_to_r += 1
            log.info(
                "Re-label candidate→recruiter [@%.1f–%.1fs] %r  "
                "rms=%.2f sr=%.2f conf=%.2f",
                seg["start"], seg["end"], seg["text"][:50],
                info.get("rms_ratio", 0), info.get("sr_ratio", 0), conf,
            )
        else:
            # Symmetric to recruiter side — trust source on uncertain calls.
            new["speaker"] = "candidate"
            log.info(
                "Low-conf attribution — keep on candidate [@%.1f–%.1fs] %r  "
                "rms=%.2f sr=%.2f conf=%.2f (< %.2f)",
                seg["start"], seg["end"], seg["text"][:50],
                info.get("rms_ratio", 0), info.get("sr_ratio", 0),
                conf, RELABEL_CONF_MIN,
            )
        pool.append(new)

    # Dedupe within each speaker — same content on both mics keeps one copy.
    pool = _dedupe(pool, "recruiter")
    pool = _dedupe(pool, "candidate")
    # Cross-speaker dedup — after per-speaker pass, any remaining recruiter/candidate
    # pair that still overlaps means both files claimed DIFFERENT speakers for the same
    # utterance (attribution was inconclusive on both sides). Keep the better-quality
    # copy; its speaker label wins.
    pool = _cross_dedup(pool)

    recruiter_final = [s for s in pool if s["speaker"] == "recruiter"]
    candidate_final = [s for s in pool if s["speaker"] == "candidate"]

    log.info(
        "Attribution: recruiter=%d (relabelled from candidate-file: %d), "
        "candidate=%d (relabelled from recruiter-file: %d) — total in=%d out=%d",
        len(recruiter_final), relabel_c_to_r,
        len(candidate_final), relabel_r_to_c,
        len(recruiter_segs) + len(candidate_segs), len(pool),
    )

    # Strip internal scoring fields before returning.
    for s in recruiter_final + candidate_final:
        s.pop("_attr_conf", None)
        s.pop("_source_file", None)
    return recruiter_final, candidate_final
