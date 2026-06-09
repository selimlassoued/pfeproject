"""
Empirical threshold calibration for the skill catalog dedup cascade.

Loads a hand-labeled dataset of skill pairs, embeds each one via Ollama's
nomic-embed-text model, computes cosine similarity, and analyzes the score
distribution per category. Compares the actual numbers against the chosen
thresholds (AUTO_MERGE 0.95, LLM_TIEBREAK 0.90, REVIEW 0.85) and recommends
adjustments if the data shows mis-calibration.

Run:
    python threshold_calibration.py
"""

import json
import math
import sys
import time
import urllib.request
from collections import defaultdict
from statistics import mean, median, stdev

# Force UTF-8 stdout so the box-drawing characters print on Windows cp1252.
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

OLLAMA_URL = "http://localhost:11434/api/embeddings"
MODEL = "nomic-embed-text"

# ──────────────────────────────────────────────────────────────────────────────
# Test dataset - hand-labeled skill pairs.
#
# Categories:
#   SAME:    same logical skill - typo / variant / abbreviation / synonym
#   RELATED: distinct skills in the same domain - should NOT merge
#   UNREL:   skills from different domains - should NOT merge
#
# Expected action per category for the chosen thresholds (0.95 / 0.90 / 0.85):
#   SAME:    AUTO_MERGE (score >= 0.95) or LLM->merge (0.90-0.95) ideal
#   RELATED: NEW (< 0.85) or REVIEW (0.85-0.90) ideal
#   UNREL:   NEW (< 0.85) ideal
# ──────────────────────────────────────────────────────────────────────────────

PAIRS = [
    # ── SAME: trivial variants / casing already lowercased by _normalize ─────
    ("SAME/variant", "react", "reactjs"),
    ("SAME/variant", "react", "react.js"),
    ("SAME/variant", "nodejs", "node.js"),
    ("SAME/variant", "nodejs", "node js"),
    ("SAME/variant", "vuejs", "vue.js"),
    ("SAME/variant", "nextjs", "next.js"),
    ("SAME/variant", "nuxtjs", "nuxt.js"),
    ("SAME/variant", "expressjs", "express.js"),
    ("SAME/variant", "spring boot", "springboot"),
    ("SAME/variant", "spring boot", "spring-boot"),
    ("SAME/variant", "asp.net", "aspnet"),
    ("SAME/variant", "c++", "cpp"),
    ("SAME/variant", "c#", "csharp"),
    ("SAME/variant", "f#", "fsharp"),
    ("SAME/variant", "objective-c", "objective c"),
    ("SAME/variant", "node.js", "nodejs"),
    ("SAME/variant", "tailwind css", "tailwindcss"),
    ("SAME/variant", "scikit-learn", "scikit learn"),
    ("SAME/variant", "scikit-learn", "sklearn"),

    # ── SAME: clear typos ──────────────────────────────────────────────────
    ("SAME/typo", "kubernetes", "kubernates"),
    ("SAME/typo", "kubernetes", "kuberentes"),
    ("SAME/typo", "kubernetes", "kubernates"),
    ("SAME/typo", "python", "phyton"),
    ("SAME/typo", "python", "pyhton"),
    ("SAME/typo", "javascript", "javascripr"),
    ("SAME/typo", "javascript", "javasctipt"),
    ("SAME/typo", "postgresql", "postgresqul"),
    ("SAME/typo", "mongodb", "mongdb"),
    ("SAME/typo", "mongodb", "mongoddb"),
    ("SAME/typo", "tensorflow", "tensorflwo"),
    ("SAME/typo", "tensorflow", "tensorlfow"),
    ("SAME/typo", "docker", "dockerr"),
    ("SAME/typo", "docker", "doker"),
    ("SAME/typo", "angular", "angluar"),
    ("SAME/typo", "django", "djnago"),
    ("SAME/typo", "hibernate", "hibrnate"),
    ("SAME/typo", "hibernate", "hibernat"),
    ("SAME/typo", "flutter", "fluter"),
    ("SAME/typo", "react native", "react nativve"),

    # ── SAME: common abbreviations recognized as the full skill ────────────
    ("SAME/abbrev", "k8s", "kubernetes"),
    ("SAME/abbrev", "js", "javascript"),
    ("SAME/abbrev", "ts", "typescript"),
    ("SAME/abbrev", "py", "python"),
    ("SAME/abbrev", "ml", "machine learning"),
    ("SAME/abbrev", "ai", "artificial intelligence"),
    ("SAME/abbrev", "nlp", "natural language processing"),
    ("SAME/abbrev", "cv", "computer vision"),
    ("SAME/abbrev", "dl", "deep learning"),
    ("SAME/abbrev", "ci/cd", "continuous integration continuous delivery"),
    ("SAME/abbrev", "oop", "object oriented programming"),
    ("SAME/abbrev", "tdd", "test driven development"),

    # ── SAME: official-vs-short forms / common aliases ──────────────────────
    ("SAME/synonym", "postgres", "postgresql"),
    ("SAME/synonym", "go", "golang"),
    ("SAME/synonym", "mysql", "my sql"),
    ("SAME/synonym", "ms sql", "sql server"),
    ("SAME/synonym", "ms sql", "microsoft sql server"),
    ("SAME/synonym", "amazon web services", "aws"),
    ("SAME/synonym", "google cloud platform", "gcp"),
    ("SAME/synonym", "microsoft azure", "azure"),
    ("SAME/synonym", "amazon ec2", "ec2"),
    ("SAME/synonym", "amazon s3", "s3"),
    ("SAME/synonym", "amazon dynamodb", "dynamodb"),
    ("SAME/synonym", "github actions", "gh actions"),
    ("SAME/synonym", "git hub", "github"),
    ("SAME/synonym", "git lab", "gitlab"),
    ("SAME/synonym", "vs code", "visual studio code"),
    ("SAME/synonym", "vscode", "visual studio code"),
    ("SAME/synonym", "intellij", "intellij idea"),
    ("SAME/synonym", "linux command line", "bash"),
    ("SAME/synonym", "node package manager", "npm"),

    # ── SAME: ecosystem alias (very close concept names) ────────────────────
    ("SAME/synonym", "redux", "redux toolkit"),
    ("SAME/synonym", "fastapi", "fast api"),

    # ── RELATED: distinct skills in the same domain - MUST NOT merge ────────
    ("RELATED/lang", "java", "javascript"),
    ("RELATED/lang", "java", "kotlin"),
    ("RELATED/lang", "java", "scala"),
    ("RELATED/lang", "java", "groovy"),
    ("RELATED/lang", "python", "ruby"),
    ("RELATED/lang", "python", "go"),
    ("RELATED/lang", "javascript", "typescript"),
    ("RELATED/lang", "c", "c++"),
    ("RELATED/lang", "c++", "rust"),
    ("RELATED/lang", "php", "perl"),

    ("RELATED/framework", "react", "react native"),
    ("RELATED/framework", "react", "vue"),
    ("RELATED/framework", "react", "angular"),
    ("RELATED/framework", "react", "svelte"),
    ("RELATED/framework", "react", "next.js"),
    ("RELATED/framework", "vue", "nuxt"),
    ("RELATED/framework", "spring", "spring boot"),
    ("RELATED/framework", "spring boot", "spring security"),
    ("RELATED/framework", "spring boot", "spring data"),
    ("RELATED/framework", "django", "flask"),
    ("RELATED/framework", "django", "fastapi"),
    ("RELATED/framework", "express", "nestjs"),
    ("RELATED/framework", "express", "fastify"),

    ("RELATED/cloud", "aws", "azure"),
    ("RELATED/cloud", "aws", "gcp"),
    ("RELATED/cloud", "kubernetes", "docker"),
    ("RELATED/cloud", "kubernetes", "openshift"),
    ("RELATED/cloud", "terraform", "ansible"),
    ("RELATED/cloud", "terraform", "pulumi"),
    ("RELATED/cloud", "jenkins", "gitlab ci"),
    ("RELATED/cloud", "jenkins", "github actions"),
    ("RELATED/cloud", "prometheus", "grafana"),
    ("RELATED/cloud", "elasticsearch", "kibana"),

    ("RELATED/db", "postgresql", "mysql"),
    ("RELATED/db", "postgresql", "oracle"),
    ("RELATED/db", "postgresql", "mongodb"),
    ("RELATED/db", "redis", "memcached"),
    ("RELATED/db", "mongodb", "cassandra"),
    ("RELATED/db", "mongodb", "couchdb"),
    ("RELATED/db", "elasticsearch", "solr"),
    ("RELATED/db", "kafka", "rabbitmq"),

    ("RELATED/ml", "tensorflow", "pytorch"),
    ("RELATED/ml", "keras", "pytorch"),
    ("RELATED/ml", "pandas", "numpy"),
    ("RELATED/ml", "scikit-learn", "xgboost"),
    ("RELATED/ml", "huggingface", "openai"),
    ("RELATED/ml", "langchain", "llamaindex"),

    # ── UNREL: skills from different domains - DEFINITELY NOT merge ────────
    ("UNREL", "java", "photoshop"),
    ("UNREL", "react", "excel"),
    ("UNREL", "python", "figma"),
    ("UNREL", "kubernetes", "after effects"),
    ("UNREL", "spring boot", "illustrator"),
    ("UNREL", "javascript", "autocad"),
    ("UNREL", "postgresql", "premiere pro"),
    ("UNREL", "docker", "blender"),
    ("UNREL", "react", "marketing"),
    ("UNREL", "python", "accounting"),
    ("UNREL", "java", "graphic design"),
    ("UNREL", "kubernetes", "copywriting"),
    ("UNREL", "spring boot", "project management"),
    ("UNREL", "tensorflow", "salesforce"),

    # ── Bonus: candidate vs requirement framework-stack pairs ───────────────
    # These test the "should declared skill X count as evidence of required Y?"
    # case. They are RELATED (different but linked), not SAME.
    ("RELATED/framework", "spring boot", "java"),
    ("RELATED/framework", "django", "python"),
    ("RELATED/framework", "rails", "ruby"),
    ("RELATED/framework", "express", "node.js"),
    ("RELATED/framework", "angular", "typescript"),
    ("RELATED/framework", "tensorflow", "python"),
]


def embed(text: str) -> list[float]:
    """Call Ollama and return the 768-dim vector."""
    body = json.dumps({"model": MODEL, "prompt": text}).encode("utf-8")
    req = urllib.request.Request(
        OLLAMA_URL, data=body,
        headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        payload = json.loads(r.read().decode("utf-8"))
    return payload["embedding"]


def cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def normalize(s: str) -> str:
    """Match the catalog's normalization: lowercase + strip whitespace."""
    return s.strip().lower()


# ──────────────────────────────────────────────────────────────────────────────
# Threshold simulation - apply the cascade decision per pair, count errors.
# ──────────────────────────────────────────────────────────────────────────────

def classify_with_thresholds(score, auto_merge, llm_tiebreak, review):
    """Return the action the cascade would take for a given score."""
    if score >= auto_merge:
        return "AUTO_MERGE"
    if score >= llm_tiebreak:
        return "LLM"  # decision deferred to Qwen
    if score >= review:
        return "REVIEW"
    return "NEW"


def evaluate(results, auto_merge, llm_tiebreak, review):
    """
    Score the threshold triple against the labeled data.
    Returns counts of correct/incorrect outcomes assuming:
      - SAME    should end up AUTO_MERGE (LLM is a correct path too,
                we assume Qwen agrees ~95% of the time for genuine same-pairs)
      - RELATED should end up NEW or REVIEW (REVIEW is acceptable, asks user)
      - UNREL   should end up NEW (REVIEW is wasted user time, AUTO_MERGE is bad)
    """
    counts = defaultdict(int)
    # Error types we care about
    same_lost = 0       # SAME pair landed as NEW (false negative)
    diff_merged = 0     # RELATED/UNREL landed as AUTO_MERGE (false positive)
    unrel_review = 0    # UNREL pair landed as REVIEW (user wasted)
    same_review = 0    # SAME pair landed as REVIEW (user has to confirm a true match)

    for cat, a, b, score in results:
        action = classify_with_thresholds(score, auto_merge, llm_tiebreak, review)
        counts[(cat.split("/")[0], action)] += 1

        base = cat.split("/")[0]
        if base == "SAME":
            if action == "NEW":
                same_lost += 1
            elif action == "REVIEW":
                same_review += 1
        elif base in ("RELATED", "UNREL"):
            if action == "AUTO_MERGE":
                diff_merged += 1
            if base == "UNREL" and action == "REVIEW":
                unrel_review += 1
    return counts, same_lost, diff_merged, unrel_review, same_review


CACHE_PATH = "threshold_calibration_cache.json"


def load_cache():
    try:
        with open(CACHE_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_cache(cache):
    with open(CACHE_PATH, "w", encoding="utf-8") as f:
        json.dump(cache, f)


def main():
    cache = load_cache()
    print(f"Embedding {len(PAIRS)} pairs via Ollama / {MODEL} "
          f"(cached: {len(cache)}) ...")
    t0 = time.time()
    results = []
    new_embeds = 0
    for i, (cat, a, b) in enumerate(PAIRS):
        na, nb = normalize(a), normalize(b)
        if na not in cache:
            cache[na] = embed(na)
            new_embeds += 1
        if nb not in cache:
            cache[nb] = embed(nb)
            new_embeds += 1
        score = cosine(cache[na], cache[nb])
        results.append((cat, a, b, score))
        if (i + 1) % 25 == 0:
            print(f"  {i+1}/{len(PAIRS)} done ({time.time()-t0:.1f}s, "
                  f"new embeds: {new_embeds})")
    save_cache(cache)
    print(f"All pairs done in {time.time()-t0:.1f}s  "
          f"({new_embeds} new embeds, cache now {len(cache)} terms)")
    print()

    # ── Distribution by category ────────────────────────────────────────────
    by_cat = defaultdict(list)
    for cat, a, b, score in results:
        by_cat[cat.split("/")[0]].append((cat, a, b, score))

    print("=" * 78)
    print("SCORE DISTRIBUTION PER CATEGORY")
    print("=" * 78)
    for base in ("SAME", "RELATED", "UNREL"):
        scores = [s for _, _, _, s in by_cat[base]]
        if not scores:
            continue
        scores_sorted = sorted(scores)
        print(f"\n{base}  (n={len(scores)})")
        print(f"  min    = {min(scores):.4f}")
        print(f"  p10    = {scores_sorted[len(scores)//10]:.4f}")
        print(f"  p25    = {scores_sorted[len(scores)//4]:.4f}")
        print(f"  median = {median(scores):.4f}")
        print(f"  p75    = {scores_sorted[3*len(scores)//4]:.4f}")
        print(f"  p90    = {scores_sorted[9*len(scores)//10]:.4f}")
        print(f"  max    = {max(scores):.4f}")
        print(f"  mean   = {mean(scores):.4f}   stdev = {stdev(scores):.4f}")

    # ── Sorted listing per category (for eyeballing) ────────────────────────
    print()
    print("=" * 78)
    print("ALL PAIRS SORTED BY SCORE (per category)")
    print("=" * 78)
    for base in ("SAME", "RELATED", "UNREL"):
        rows = sorted(by_cat[base], key=lambda r: -r[3])
        print(f"\n── {base}: {len(rows)} pairs ──")
        for cat, a, b, s in rows:
            sub = cat.split("/", 1)[1] if "/" in cat else "-"
            print(f"  {s:.4f}  [{sub:>10}]  {a:<25}  ⇔  {b}")

    # ── Evaluate the current thresholds ─────────────────────────────────────
    print()
    print("=" * 78)
    print("CURRENT THRESHOLDS: AUTO_MERGE=0.95  LLM=0.90  REVIEW=0.85")
    print("=" * 78)
    counts, same_lost, diff_merged, unrel_review, same_review = evaluate(
        results, 0.95, 0.90, 0.85
    )
    n_same = len(by_cat["SAME"])
    n_related = len(by_cat["RELATED"])
    n_unrel = len(by_cat["UNREL"])

    print()
    print(f"{'':>10}{'AUTO_MERGE':>12}{'LLM':>6}{'REVIEW':>8}{'NEW':>6}")
    for base in ("SAME", "RELATED", "UNREL"):
        row = f"{base:>10}"
        for action in ("AUTO_MERGE", "LLM", "REVIEW", "NEW"):
            row += f"{counts.get((base, action), 0):>12}" if action == "AUTO_MERGE" else f"{counts.get((base, action), 0):>6}"
        print(row)

    print()
    print(f"SAME false-negative (skill lost as NEW):           {same_lost} / {n_same}  = {100*same_lost/max(n_same,1):.1f}%")
    print(f"SAME going through REVIEW (user must confirm):     {same_review} / {n_same}  = {100*same_review/max(n_same,1):.1f}%")
    print(f"DIFFERENT false-positive (auto-merged):            {diff_merged} / {n_related+n_unrel}  = {100*diff_merged/max(n_related+n_unrel,1):.1f}%")
    print(f"UNREL pairs forced into REVIEW (UX noise):         {unrel_review} / {n_unrel}  = {100*unrel_review/max(n_unrel,1):.1f}%")

    # ── Try alternative thresholds ──────────────────────────────────────────
    print()
    print("=" * 78)
    print("SWEEP - trying alternative threshold triples")
    print("=" * 78)
    print(f"{'AM':>5}{'LLM':>7}{'REV':>7}{'SAME→NEW':>11}{'SAME→REV':>10}{'DIFF→AM':>10}{'UNREL→REV':>11}")
    candidates = []
    for am in (0.93, 0.94, 0.95, 0.96, 0.97):
        for llm in (0.85, 0.87, 0.88, 0.89, 0.90, 0.92):
            for rev in (0.75, 0.78, 0.80, 0.82, 0.85):
                if not (rev < llm < am):
                    continue
                _, sl, dm, ur, sr = evaluate(results, am, llm, rev)
                # Penalize same loss heavily, diff-merge moderately,
                # unrel-review lightly (it's just UX noise).
                cost = sl * 10 + dm * 8 + ur * 1 + sr * 2
                candidates.append((cost, am, llm, rev, sl, sr, dm, ur))

    candidates.sort()
    print()
    print("TOP 10 BEST-SCORING THRESHOLD TRIPLES:")
    print(f"{'cost':>6}{'AM':>7}{'LLM':>7}{'REV':>7}{'SAME→NEW':>11}{'SAME→REV':>10}{'DIFF→AM':>10}{'UNREL→REV':>11}")
    for c, am, llm, rev, sl, sr, dm, ur in candidates[:10]:
        print(f"{c:>6}  {am:.2f}   {llm:.2f}   {rev:.2f}   {sl:>9}   {sr:>8}   {dm:>8}   {ur:>9}")

    # ── Recommendation ──────────────────────────────────────────────────────
    print()
    print("=" * 78)
    print("RECOMMENDATION")
    print("=" * 78)
    best = candidates[0]
    _, bm, blm, brv, bsl, bsr, bdm, bur = best
    current_cost = None
    for c, am, llm, rev, *_ in candidates:
        if (am, llm, rev) == (0.95, 0.90, 0.85):
            current_cost = c
            break
    if current_cost is not None:
        print(f"Current (0.95/0.90/0.85) cost: {current_cost}")
        print(f"Best alternative cost:         {best[0]}  at  AM={bm} LLM={blm} REV={brv}")
        if best[0] >= current_cost - 1:
            print("\n→ Current thresholds are very close to optimal. NO CHANGE NEEDED.")
        else:
            print(f"\n→ Consider switching to AM={bm} LLM={blm} REV={brv}")
            print(f"  Savings: {current_cost - best[0]} cost-units (same/diff errors weighted)")

    print()
    print("Done.")


if __name__ == "__main__":
    main()
