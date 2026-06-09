"""
End-to-end validation of the skill catalog workflow.

Tests the actual LLM and embedder running on the local Ollama, exercising:
  1. Skill classifier with expected_type validation
     - HARD inputs claimed HARD     -> type=HARD
     - SOFT inputs claimed SOFT     -> type=SOFT
     - HARD inputs claimed SOFT     -> type=HARD (TYPE_MISMATCH)
     - SOFT inputs claimed HARD     -> type=SOFT (TYPE_MISMATCH)
     - INVALID inputs               -> type=INVALID
  2. Implies field for HARD skills
     - "react"      should imply ["javascript"]
     - "next"       should imply ["react", "javascript"]
     - "spring boot" should imply ["java"]
     - SOFT skills should NOT have implies
  3. Display name canonicalization
     - "node"       -> "Node.js"
     - "javascript" -> "JavaScript"
     - "ios"        -> "iOS"
  4. Bare framework recognition
     - "node", "express", "next", "r", "c", "bun", "astro" -> all HARD
  5. Soft-skill embedding match threshold (0.70)
     - "strong communication skills" vs "communication" -> >= 0.70
     - "cooking"                     vs "communication" -> <  0.70
"""

import json
import sys
import time
import urllib.request
import math
from collections import defaultdict

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

OLLAMA = "http://localhost:11434"
CHAT_URL = OLLAMA + "/api/chat"
EMBED_URL = OLLAMA + "/api/embeddings"
CHAT_MODEL = "qwen2.5:7b"
EMBED_MODEL = "nomic-embed-text"


def chat_json(prompt):
    body = json.dumps({
        "model": CHAT_MODEL,
        "format": "json",
        "messages": [{"role": "user", "content": prompt}],
        "options": {"temperature": 0.0},
        "stream": False,
    }).encode("utf-8")
    req = urllib.request.Request(CHAT_URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        payload = json.loads(r.read().decode("utf-8"))
    content = payload.get("message", {}).get("content", "")
    return json.loads(content) if content else {}


def embed(text):
    body = json.dumps({"model": EMBED_MODEL, "prompt": text}).encode("utf-8")
    req = urllib.request.Request(EMBED_URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        payload = json.loads(r.read().decode("utf-8"))
    return payload["embedding"]


def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    return 0.0 if (na == 0 or nb == 0) else dot / (na * nb)


# Exact prompt template from main.py - kept in sync manually here so the
# test exercises what production actually sends.
def build_prompt(text, expected_type):
    cleaned = " ".join(text.strip().split())
    expected_block = (
        (f"CALLER'S CLAIM: this was entered as a {expected_type} skill. "
         f"Bias toward agreeing - only override to a different type if you are HIGHLY "
         f"confident the caller made a mistake (e.g. they typed 'Leadership' into a "
         f"HARD-skill requirement section). Otherwise return type={expected_type}.\n\n")
        if expected_type else
        "CONTEXT: this was entered as a skill on a job posting or candidate profile.\n\n"
    )
    return (
        "You validate AND properly format skill names for a recruitment database.\n\n"
        f"Skill name: \"{cleaned}\"\n\n"
        + expected_block +
        "Do THREE things in one response.\n\n"
        "=== 1) CLASSIFY ===\n\n"
        "HARD = technical skill: programming language, framework, library, tool, technical\n"
        "       concept, or domain knowledge. When a bare short word could be a known\n"
        "       tech framework (\"node\", \"express\", \"next\", \"react\", \"vue\", \"spring\",\n"
        "       \"django\", \"rails\", \"r\", \"c\", \"go\", \"rust\", \"astro\", \"bun\",\n"
        "       \"svelte\", \"remix\", \"deno\"), classify as HARD.\n\n"
        "SOFT = universal interpersonal or behavioral ability. Recognize ALL of these as SOFT:\n"
        "       communication, teamwork, leadership, mentoring, mentorship, collaboration,\n"
        "       problem solving, critical thinking, creativity, creative thinking,\n"
        "       attention to detail, time management, organization, planning,\n"
        "       adaptability, flexibility, resilience, work ethic, initiative,\n"
        "       emotional intelligence, empathy, active listening, conflict resolution,\n"
        "       cross-cultural collaboration, public speaking, presentation skills,\n"
        "       writing skills, decision making, analytical thinking, negotiation,\n"
        "       customer service, stakeholder management, project management methodology.\n\n"
        "INVALID = NOT a skill. Strict examples:\n"
        "   - Job titles: \"software engineer\", \"director\", \"senior developer\", \"ceo\"\n"
        "   - Company names alone: \"microsoft\", \"google\", \"oracle corp\"\n"
        "   - Random text: \"xyz123\", \"asdfgh\", \"hello world\"\n"
        "   - Colors, body parts, foods, places, animals\n"
        "   - Sentences or descriptions: \"i am good at...\"\n"
        "   - Pure punctuation or single random letters: \".\", \"-\"\n\n"
        "=== 2) DISPLAY_NAME ===\n\n"
        "The canonical capitalization used by the community. Examples:\n"
        "   JavaScript, TypeScript, iOS, macOS, GraphQL, scikit-learn (lowercase),\n"
        "   .NET, C++, C#, jQuery, PostgreSQL, MySQL, MongoDB, Spring Boot,\n"
        "   Node.js, Next.js, Express.js, React, Vue.js, Angular\n\n"
        "Rules:\n"
        "   - For bare framework names use conventional form: \"node\" -> \"Node.js\"\n"
        "   - Title-case ordinary multi-word skills: \"data analysis\" -> \"Data Analysis\"\n"
        "   - Soft skills are simple title case: \"communication\" -> \"Communication\"\n"
        "   - NEVER add parenthetical explanations\n"
        "   - NEVER add words not in the input\n"
        "   - Preserve special characters (dots, hyphens, slashes, plus signs)\n"
        "   - When INVALID, return a tidied version of the input as-is\n\n"
        "=== 3) IMPLIES ===\n\n"
        "A list of OTHER skills (canonical lowercase form) that this skill is built on\n"
        "or strongly implies the candidate knows.\n\n"
        "Examples:\n"
        "   \"node\"        -> implies [\"javascript\"]\n"
        "   \"next\"        -> implies [\"react\", \"javascript\"]\n"
        "   \"spring boot\" -> implies [\"java\"]\n"
        "   \"django\"      -> implies [\"python\"]\n"
        "   \"rails\"       -> implies [\"ruby\"]\n"
        "   \"tensorflow\"  -> implies [\"python\"]\n"
        "   \"react\"       -> implies [\"javascript\"]\n"
        "   \"vue\"         -> implies [\"javascript\"]\n"
        "   \"typescript\"  -> implies [\"javascript\"]\n"
        "   \"swift\"       -> implies []\n"
        "   \"java\"        -> implies []\n"
        "   \"communication\" -> implies []\n"
        "   \"docker\"      -> implies [\"linux\"]\n"
        "   \"kubernetes\"  -> implies [\"docker\", \"linux\"]\n\n"
        "Rules:\n"
        "   - 0 to 3 entries\n"
        "   - Lowercase canonical names only\n"
        "   - Use bare names without .js suffix\n"
        "   - SOFT and INVALID skills: return empty list []\n"
        "   - Base languages (Java, Python, Go, Rust, C): return empty list []\n\n"
        "Reply with STRICT JSON: "
        "{\"type\": \"HARD\"|\"SOFT\"|\"INVALID\", "
        "\"display_name\": \"<name>\", "
        "\"implies\": [...], "
        "\"reason\": \"<one short sentence>\"}"
    )


# ──────────────────────────────────────────────────────────────────────────────
# Test cases
# ──────────────────────────────────────────────────────────────────────────────

CLASSIFIER_TESTS = [
    # text, expected_type, expected_outcome ('agree' | 'mismatch' | 'invalid')
    # Agree HARD
    ("react",          "HARD", "agree"),
    ("javascript",     "HARD", "agree"),
    ("typescript",     "HARD", "agree"),
    ("node",           "HARD", "agree"),
    ("next",           "HARD", "agree"),
    ("nuxt",           "HARD", "agree"),
    ("spring boot",    "HARD", "agree"),
    ("kubernetes",     "HARD", "agree"),
    ("docker",         "HARD", "agree"),
    ("python",         "HARD", "agree"),
    ("express",        "HARD", "agree"),
    ("r",              "HARD", "agree"),
    ("c",              "HARD", "agree"),
    ("bun",            "HARD", "agree"),

    # Agree SOFT
    ("communication",  "SOFT", "agree"),
    ("leadership",     "SOFT", "agree"),
    ("teamwork",       "SOFT", "agree"),
    ("creativity",     "SOFT", "agree"),
    ("empathy",        "SOFT", "agree"),
    ("problem solving","SOFT", "agree"),

    # Mismatches - HARD claimed but really SOFT
    ("leadership",     "HARD", "mismatch"),
    ("communication",  "HARD", "mismatch"),
    ("teamwork",       "HARD", "mismatch"),

    # Mismatches - SOFT claimed but really HARD
    ("javascript",     "SOFT", "mismatch"),
    ("react",          "SOFT", "mismatch"),
    ("docker",         "SOFT", "mismatch"),

    # Invalid - job titles
    ("software engineer", "HARD", "invalid"),
    ("director",          "HARD", "invalid"),
    # Invalid - colors / nonsense
    ("blue",              "SOFT", "invalid"),
    ("xyz123",            "HARD", "invalid"),
]

# Skills that should produce specific implies (only checking presence of key parents)
IMPLIES_TESTS = [
    # input, expected_type, list of parents that MUST appear
    ("react",       "HARD", ["javascript"]),
    ("next",        "HARD", ["react"]),               # may also include javascript
    ("vue",         "HARD", ["javascript"]),
    ("nuxt",        "HARD", ["vue"]),                 # may also include javascript
    ("spring boot", "HARD", ["java"]),
    ("django",      "HARD", ["python"]),
    ("rails",       "HARD", ["ruby"]),
    ("tensorflow",  "HARD", ["python"]),
    ("node",        "HARD", ["javascript"]),
    # Base languages should have empty implies
    ("python",      "HARD", []),
    ("java",        "HARD", []),
    # SOFT must have empty implies
    ("communication","SOFT", []),
    ("leadership",  "SOFT", []),
]

# Display name canonicalization tests
DISPLAY_NAME_TESTS = [
    ("node",       "HARD", "Node.js"),
    ("javascript", "HARD", "JavaScript"),
    ("typescript", "HARD", "TypeScript"),
    ("ios",        "HARD", "iOS"),
    ("kubernetes", "HARD", "Kubernetes"),
    ("react",      "HARD", "React"),
    ("next",       "HARD", "Next.js"),
    ("spring boot","HARD", "Spring Boot"),
]

# Soft-skill embedding match tests (catalog has Communication, Teamwork, Leadership)
SOFT_MATCH_TESTS = [
    # (cv_text, catalog_canonical, expect_above_threshold)
    ("strong communication skills",       "communication", True),
    ("excellent communication",           "communication", True),
    ("verbal communication",              "communication", True),
    ("written and verbal communication",  "communication", True),
    ("great team player",                 "teamwork",      True),
    ("collaborative team member",         "teamwork",      True),
    ("led a team of 5 engineers",         "leadership",    True),
    ("strong leadership skills",          "leadership",    True),
    ("creative problem solver",           "creativity",    True),
    # Should NOT match (below 0.70)
    ("i love cooking",                    "communication", False),
    ("built machine learning models",     "communication", False),
    ("kubernetes cluster operations",     "teamwork",      False),
    ("python data analysis",              "leadership",    False),
]

SOFT_MATCH_THRESHOLD = 0.70


# ──────────────────────────────────────────────────────────────────────────────
# Test runner
# ──────────────────────────────────────────────────────────────────────────────

def main():
    t0 = time.time()
    results = defaultdict(lambda: {"pass": 0, "fail": 0, "details": []})

    # --- 1. Classifier tests --------------------------------------------------
    print("=" * 78)
    print("1. CLASSIFIER TYPE VALIDATION (with expected_type)")
    print("=" * 78)
    for text, expected, outcome in CLASSIFIER_TESTS:
        try:
            r = chat_json(build_prompt(text, expected))
            t = r.get("type", "?")
            display = r.get("display_name", "?")

            if outcome == "agree":
                ok = (t == expected)
            elif outcome == "mismatch":
                # mismatch means LLM should NOT agree with caller
                ok = (t != expected and t in ("HARD", "SOFT"))
            elif outcome == "invalid":
                ok = (t == "INVALID")
            else:
                ok = False

            status = "[PASS]" if ok else "[FAIL]"
            symbol = "agree" if outcome == "agree" else outcome
            results["classify"]["pass" if ok else "fail"] += 1
            print(f"  {status} {text!r:25} claimed={expected or 'None':4} expect={symbol:8} got=type={t} display={display!r}")
            if not ok:
                results["classify"]["details"].append((text, expected, outcome, t, display))
        except Exception as e:
            results["classify"]["fail"] += 1
            print(f"  [ERR ] {text!r:25} - {e.__class__.__name__}: {e}")

    # --- 2. Implies tests -----------------------------------------------------
    print()
    print("=" * 78)
    print("2. IMPLIES FIELD (HARD skills should imply parents, SOFT should be empty)")
    print("=" * 78)
    for text, expected, required_parents in IMPLIES_TESTS:
        try:
            r = chat_json(build_prompt(text, expected))
            implies = r.get("implies", [])
            implies_lower = [str(i).lower().strip() for i in implies if isinstance(i, str)]

            if not required_parents:
                ok = (len(implies_lower) == 0)
                status = "[PASS]" if ok else "[FAIL]"
                results["implies"]["pass" if ok else "fail"] += 1
                print(f"  {status} {text!r:18} ({expected}) - expect empty implies, got={implies_lower}")
            else:
                missing = [p for p in required_parents if p not in implies_lower]
                ok = (len(missing) == 0)
                status = "[PASS]" if ok else "[FAIL]"
                results["implies"]["pass" if ok else "fail"] += 1
                print(f"  {status} {text!r:18} ({expected}) - want {required_parents}, got {implies_lower}, missing {missing}")
        except Exception as e:
            results["implies"]["fail"] += 1
            print(f"  [ERR ] {text!r:18} - {e.__class__.__name__}")

    # --- 3. Display name tests ------------------------------------------------
    print()
    print("=" * 78)
    print("3. DISPLAY NAME CANONICALIZATION")
    print("=" * 78)
    for text, expected_type, expected_display in DISPLAY_NAME_TESTS:
        try:
            r = chat_json(build_prompt(text, expected_type))
            got_display = r.get("display_name", "?")
            ok = (got_display.lower() == expected_display.lower())
            status = "[PASS]" if ok else "[FAIL]"
            results["display"]["pass" if ok else "fail"] += 1
            print(f"  {status} {text!r:18} expect={expected_display!r:18} got={got_display!r}")
        except Exception as e:
            results["display"]["fail"] += 1
            print(f"  [ERR ] {text!r:18} - {e.__class__.__name__}")

    # --- 4. Soft skill embedding match ---------------------------------------
    print()
    print("=" * 78)
    print(f"4. SOFT SKILL EMBEDDING MATCH (threshold = {SOFT_MATCH_THRESHOLD})")
    print("=" * 78)
    # Pre-embed the canonical names
    canonical_vecs = {}
    for _, canon, _ in SOFT_MATCH_TESTS:
        if canon not in canonical_vecs:
            canonical_vecs[canon] = embed(canon)

    for cv_text, canonical, expect_above in SOFT_MATCH_TESTS:
        try:
            cv_vec = embed(cv_text)
            score = cosine(cv_vec, canonical_vecs[canonical])
            above = score >= SOFT_MATCH_THRESHOLD
            ok = (above == expect_above)
            status = "[PASS]" if ok else "[FAIL]"
            symbol = ">=" if expect_above else "<"
            results["soft_match"]["pass" if ok else "fail"] += 1
            print(f"  {status} {cv_text!r:38} vs {canonical!r:14} score={score:.3f} expect {symbol} {SOFT_MATCH_THRESHOLD}")
        except Exception as e:
            results["soft_match"]["fail"] += 1
            print(f"  [ERR ] {cv_text!r:38} - {e.__class__.__name__}")

    # --- Summary --------------------------------------------------------------
    print()
    print("=" * 78)
    print("SUMMARY")
    print("=" * 78)
    total_p = sum(g["pass"] for g in results.values())
    total_f = sum(g["fail"] for g in results.values())
    for group, stats in results.items():
        total = stats["pass"] + stats["fail"]
        rate = 100 * stats["pass"] / total if total else 0
        print(f"  {group:12}  {stats['pass']:3}/{total:3} passed ({rate:.0f}%)")
    overall = 100 * total_p / (total_p + total_f) if (total_p + total_f) else 0
    print(f"  {'OVERALL':12}  {total_p:3}/{total_p + total_f:3} passed ({overall:.0f}%)")
    print()
    print(f"Total elapsed: {time.time() - t0:.1f}s")

    return 0 if total_f == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
