"""LLM-assisted skill classifier.

For each skill name, asks the LLM to rate:
  - volatility (1-10): how fast the tech evolves
  - implies (list[str]): strictly required base technologies

Results are cached to a JSON file keyed by lowercase skill name so repeated
skills across CVs cost zero LLM calls. The prompt is fully generic — it
takes only skill names, no CV content — so it works for any framework or
language, including ones unknown to the codebase.
"""

from __future__ import annotations

import json
import logging
import os
import re
from pathlib import Path
from typing import Iterable

import ollama

logger = logging.getLogger(__name__)

OLLAMA_HOST    = os.getenv("OLLAMA_HOST", "http://localhost:11434")
ANALYSIS_MODEL = os.getenv("SEMANTIC_MATCH_ANALYSIS_MODEL", "qwen2.5:7b")

# Cache file path — kept inside container; mount a volume for persistence.
CACHE_PATH = Path(os.getenv("SKILL_INTEL_CACHE", "/app/skill_intel_cache.json"))

# Hard limit per batch to keep the LLM call bounded and the JSON parse robust.
_BATCH_SIZE = 25

_client = ollama.Client(host=OLLAMA_HOST)


# ── Cache I/O ─────────────────────────────────────────────────────────────────

def _load_cache() -> dict[str, dict]:
    try:
        if CACHE_PATH.exists():
            with CACHE_PATH.open("r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict):
                return data
    except Exception as e:
        logger.warning(f"[skill_intel] Cache load failed: {e}")
    return {}


def _save_cache(cache: dict[str, dict]) -> None:
    try:
        CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
        with CACHE_PATH.open("w", encoding="utf-8") as f:
            json.dump(cache, f, ensure_ascii=False, indent=2, sort_keys=True)
    except Exception as e:
        logger.warning(f"[skill_intel] Cache save failed: {e}")


# ── Normalization (must match semantic_matcher._normalize behavior for keys) ─

_NORM_RE = re.compile(r"[^a-z0-9+#.\-/ ]+")
_WS_RE   = re.compile(r"\s+")

def _norm(s: str) -> str:
    if not s:
        return ""
    s = s.strip().lower()
    s = s.replace("nodejs", "node.js").replace("node js", "node.js")
    s = s.replace("reactjs", "react").replace("vuejs", "vue")
    s = s.replace("angularjs", "angular").replace("springboot", "spring boot")
    s = re.sub(r"\s+v?\d[\d+.*x-]*", "", s)   # strip "Angular 16+" → "angular"
    s = _NORM_RE.sub(" ", s)
    s = _WS_RE.sub(" ", s)
    return s.strip()


# ── LLM call ──────────────────────────────────────────────────────────────────

_PROMPT_TEMPLATE = """You are a technical skills classifier. For each skill below, return two properties.
The skill list can contain ANYTHING — any language, framework, library, database,
tool, platform, or concept from any ecosystem, including ones released recently.
Apply the rules below by reasoning from your own knowledge of how each skill works.
The examples are ILLUSTRATIVE ONLY — never treat them as a closed list.

(1) volatility (integer 1-10):
    How fast does the tech evolve before old code feels outdated?
    Calibration scale (use these as reference points to place ANY skill):
      1  = Stable APIs, 10+ year old code still runs        (illustrative: HTML, SQL, Bash, C, Git)
      4  = LTS lifecycle, backward-compatible major versions (illustrative: Java, Python, Docker)
      7  = Breaking changes every 2-3 years                  (illustrative: most modern UI/ML frameworks)
      10 = Paradigm shifts roughly every year                (illustrative: cutting-edge AI tooling)
    For a skill unlike any example, interpolate on this scale using its real release history.

(2) implies (list of lowercase strings):
    Other technologies that USING this skill in production code STRICTLY REQUIRES.
    Rules (apply to every skill, not just the examples):
    - A framework implies the host language / runtime it cannot execute without.
    - A plain language, database, tool, or concept implies nothing → return [].
    - Include ONLY strict, unavoidable requirements — never optional or merely-related techs.
    Illustrative examples (the same reasoning applies to any other framework):
      "angular"      -> ["typescript", "javascript", "html", "css"]
      "spring boot"  -> ["java"]
      "django"       -> ["python"]
      "react"        -> ["javascript", "html", "css"]   (typescript is optional -> exclude)
      "java"         -> []          (a language implies nothing)
      "docker"       -> []          (standalone tool)

Return STRICT JSON. No prose. No markdown fences.
Schema:
{"skills":[{"name":"<input>","volatility":<int>,"implies":["<lowercase>",...]}]}

Skills to classify:
%s
"""


def _llm_classify(skills: list[str]) -> dict[str, dict]:
    """Send a batch of skills to the LLM and parse the JSON response.
    Returns {skill_normalized: {volatility, implies}}. Empty dict on failure."""
    if not skills:
        return {}

    skill_list = "\n".join(f"- {s}" for s in skills)
    prompt = _PROMPT_TEMPLATE % skill_list

    try:
        resp = _client.chat(
            model=ANALYSIS_MODEL,
            messages=[{"role": "user", "content": prompt}],
            options={"temperature": 0.1},
            format="json",
        )
        content = (((resp or {}).get("message") or {}).get("content") or "").strip()
        parsed  = json.loads(content) if content else {}
        items   = parsed.get("skills") if isinstance(parsed, dict) else None
        if not isinstance(items, list):
            return {}

        result: dict[str, dict] = {}
        for item in items:
            if not isinstance(item, dict):
                continue
            name = _norm(str(item.get("name") or ""))
            if not name:
                continue
            try:
                vol = max(1, min(10, int(item.get("volatility") or 5)))
            except (ValueError, TypeError):
                vol = 5
            implies_raw = item.get("implies") or []
            implies = sorted({
                _norm(str(x))
                for x in implies_raw
                if isinstance(x, str) and _norm(str(x))
            })
            result[name] = {"volatility": vol, "implies": implies}
        return result
    except Exception as e:
        logger.warning(f"[skill_intel] LLM call failed: {e}")
        return {}


# ── Public API ────────────────────────────────────────────────────────────────

def get_skill_intelligence(skills: Iterable[str]) -> dict[str, dict]:
    """Return {skill_normalized: {volatility:int, implies:list[str]}} for the given skills.

    Uses a JSON cache to avoid repeat LLM calls. Cache misses are batched into
    one or more LLM calls (bounded by _BATCH_SIZE). New entries are persisted
    back to the cache file.

    Generic — same prompt for every CV. Safe to call with an empty or large
    list. Never raises; on LLM failure, missing entries are simply omitted."""
    skill_norms: list[str] = []
    seen: set[str] = set()
    for s in skills:
        n = _norm(s) if isinstance(s, str) else ""
        if n and n not in seen:
            seen.add(n)
            skill_norms.append(n)

    if not skill_norms:
        return {}

    cache = _load_cache()
    missing = [s for s in skill_norms if s not in cache]

    if missing:
        logger.info(f"[skill_intel] Cache miss for {len(missing)} skills, "
                    f"hit for {len(skill_norms) - len(missing)}.")
        new_entries: dict[str, dict] = {}
        for i in range(0, len(missing), _BATCH_SIZE):
            batch = missing[i:i + _BATCH_SIZE]
            batch_result = _llm_classify(batch)
            new_entries.update(batch_result)
        if new_entries:
            cache.update(new_entries)
            _save_cache(cache)
    else:
        logger.info(f"[skill_intel] All {len(skill_norms)} skills in cache.")

    return {s: cache[s] for s in skill_norms if s in cache}
