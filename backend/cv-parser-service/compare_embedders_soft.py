"""A/B test: nomic-embed-text vs mxbai-embed-large on soft-skill matching.

Runs inside the cv-parser-service container against the local Ollama. For
each (canonical soft skill, CV prose) pair we embed both sides with EACH
model, compute cosine similarity, and report:

  1. Side-by-side cosine table
  2. How many pairs clear the 0.55 evidence-pool threshold under each model
  3. Average lift per skill
  4. Overall recall numbers

This is the empirical evidence for whether the mxbai swap improves
soft-skill scoring on the CV-vs-job critical path.
"""

import json
import math
import sys
import time
import urllib.request


OLLAMA_URL = "http://ollama:11434/api/embeddings"

# Realistic test corpus: 6 canonical soft skills paired with 4-6 CV prose
# snippets each. The prose mimics what actually appears in CV summary /
# experience / project fields - verbose, descriptive, almost never uses
# the literal canonical word.
TEST_CASES = {
    "Leadership": [
        "Led a team of 5 engineers across 3 time zones",
        "Directed engineering teams toward quarterly delivery goals",
        "Managed people across functions including QA and product",
        "Served as tech lead on the payments platform rewrite",
        "Owned the delivery of the next-gen mobile app",
        "Mentored 8 junior developers over two release cycles",
    ],
    "Communication": [
        "Presented quarterly OKRs to executives",
        "Wrote and reviewed technical RFCs across the org",
        "Facilitated bi-weekly cross-team sync meetings",
        "Liaised with external auditors during SOC 2 prep",
        "Coordinated requirements gathering with 4 stakeholder groups",
    ],
    "Teamwork": [
        "Collaborated daily with designers and product managers",
        "Worked in tight pair-programming rotation for 6 months",
        "Contributed to cross-functional initiatives on observability",
        "Helped onboard 3 new hires through code reviews",
    ],
    "Problem Solving": [
        "Diagnosed and resolved a production latency regression in 4 hours",
        "Built a heuristic to detect duplicate orders before charging",
        "Designed an algorithm to deduplicate noisy event logs",
        "Reverse-engineered an undocumented vendor API",
    ],
    "Initiative": [
        "Proposed and led adoption of a new CI pipeline",
        "Drove unprompted refactor of the legacy auth module",
        "Initiated weekly tech debt review sessions",
        "Volunteered to own the on-call rotation tooling",
    ],
    "Adaptability": [
        "Pivoted from React Native to Flutter mid-project",
        "Shipped 4 different MVPs in response to changing requirements",
        "Adjusted to fully remote workflow during the merger",
        "Rotated between backend, infra, and frontend over 18 months",
    ],
}


def embed(model: str, text: str) -> list[float]:
    body = json.dumps({"model": model, "prompt": text}).encode()
    req = urllib.request.Request(OLLAMA_URL, data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())["embedding"]


def cosine(a: list[float], b: list[float]) -> float:
    if len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    return dot / (na * nb) if na and nb else 0.0


def main():
    threshold = 0.55  # Same as the matcher's evidence-pool threshold
    print(f"\nA/B test: nomic-embed-text vs mxbai-embed-large")
    print(f"Threshold for credit: cosine >= {threshold}")
    print(f"Test corpus: {len(TEST_CASES)} skills, "
          f"{sum(len(v) for v in TEST_CASES.values())} CV phrases")
    print()

    # Counters for the global summary
    nomic_hits = 0
    mxbai_hits = 0
    total_pairs = 0
    per_skill = []

    for canonical, phrases in TEST_CASES.items():
        # Embed the canonical ONCE per model
        try:
            q_nomic = embed("nomic-embed-text", canonical)
            q_mxbai = embed("mxbai-embed-large", canonical)
        except Exception as e:
            print(f"!! Embedding failed for canonical '{canonical}': {e}")
            return

        print(f"━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        print(f"  Canonical: {canonical}")
        print(f"━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        print(f"  {'CV phrase':<55} {'nomic':>8} {'mxbai':>8} {'lift':>8}")

        skill_nomic_hits = 0
        skill_mxbai_hits = 0
        skill_lifts = []

        for phrase in phrases:
            try:
                p_nomic = embed("nomic-embed-text", phrase)
                p_mxbai = embed("mxbai-embed-large", phrase)
            except Exception as e:
                print(f"  !! {phrase[:45]} -> embed failed: {e}")
                continue

            c_nomic = cosine(q_nomic, p_nomic)
            c_mxbai = cosine(q_mxbai, p_mxbai)
            lift = c_mxbai - c_nomic
            skill_lifts.append(lift)

            n_flag = "✓" if c_nomic >= threshold else "."
            m_flag = "✓" if c_mxbai >= threshold else "."
            if c_nomic >= threshold:
                skill_nomic_hits += 1
                nomic_hits += 1
            if c_mxbai >= threshold:
                skill_mxbai_hits += 1
                mxbai_hits += 1
            total_pairs += 1

            print(f"  {phrase[:55]:<55} "
                  f"{c_nomic:>7.3f}{n_flag} "
                  f"{c_mxbai:>7.3f}{m_flag} "
                  f"{lift:>+7.3f}")

        n_total = len(phrases)
        avg_lift = sum(skill_lifts) / len(skill_lifts) if skill_lifts else 0
        print(f"\n  → {canonical}: nomic {skill_nomic_hits}/{n_total} | "
              f"mxbai {skill_mxbai_hits}/{n_total} | "
              f"avg lift {avg_lift:+.3f}\n")
        per_skill.append((canonical, skill_nomic_hits, skill_mxbai_hits, n_total, avg_lift))

    # Final summary
    print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    print(f"  OVERALL")
    print("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    print(f"  Pairs tested:         {total_pairs}")
    print(f"  Nomic hits >= 0.55:   {nomic_hits}/{total_pairs}  ({100*nomic_hits/total_pairs:.1f}%)")
    print(f"  Mxbai hits >= 0.55:   {mxbai_hits}/{total_pairs}  ({100*mxbai_hits/total_pairs:.1f}%)")
    print(f"  Recall lift:          {100*(mxbai_hits-nomic_hits)/total_pairs:+.1f} percentage points")
    print()
    print(f"  Per-skill summary:")
    print(f"  {'skill':<22} {'nomic hits':>12} {'mxbai hits':>12} {'avg lift':>10}")
    for name, nh, mh, t, lift in per_skill:
        print(f"  {name:<22} {nh:>5}/{t:<6} {mh:>5}/{t:<6} {lift:>+9.3f}")
    print()


if __name__ == "__main__":
    main()
