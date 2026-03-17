import time
import json
import ollama

CV_TEXT = """
Zaina Al Darras, Software Engineer
+216 25600754 | zinadarras41@gmail.com | linkedin.com/in/zaina-darras-b72513297

PROFILE
Motivated and rigorous student with a solid foundation in software development.

PROFESSIONAL EXPERIENCE
Jan 2025 - Feb 2025 | Intern, Vermeg Factory | Lac 1, Tunis
Development of a full-stack web application using Spring Boot, Angular, and MySQL.

Jan 2024 - Feb 2024 | Intern, South Mediterranean University
Development of a Flutter mobile application for campus events.

EDUCATION
Sep 2023 - Present | Software Engineer: Information Systems Development
Institut Superieur des Etudes Technologiques de Nabeul - Valedictorian

TECHNICAL SKILLS
Angular, Spring Boot, Python, Flutter, MySQL, Docker, Git

LANGUAGES
Arabic Native | French Professional | English Advanced | Spanish Beginner
"""

PROMPT = f"""Extract CV info and return ONLY valid JSON with no explanation:
{{
  "candidate_name": "name or null",
  "email": "email or null",
  "location": "home city or null",
  "skills": ["skill1", "skill2"],
  "languages": [{{"name": "lang", "level": "level"}}],
  "education": [{{"degree": "degree", "institution": "school", "mention": "honors or null"}}],
  "work_experience": [{{"title": "title", "company": "company", "duration": "dates"}}],
  "soft_skills": ["skill1"],
  "awards": ["award1"]
}}

CV:
{CV_TEXT}

Return ONLY JSON, nothing else."""

MODELS = [
    "mistral",
    "mistral:7b-instruct-q4_0",
    "llama3.1:8b",
]

EXPECTED = {
    "candidate_name": "Zaina Al Darras",
    "email": "zinadarras41@gmail.com",
    "skills_count": 7,
    "languages_count": 4,
    "education_count": 1,
    "experience_count": 2,
}

def score_result(data: dict) -> int:
    """Score the result from 0 to 100 based on correctness."""
    score = 0
    if data.get("candidate_name") == EXPECTED["candidate_name"]:
        score += 25
    if data.get("email") == EXPECTED["email"]:
        score += 20
    if len(data.get("skills", [])) >= EXPECTED["skills_count"]:
        score += 20
    if len(data.get("languages", [])) >= EXPECTED["languages_count"]:
        score += 15
    if len(data.get("education", [])) >= EXPECTED["education_count"]:
        score += 10
    if len(data.get("work_experience", [])) >= EXPECTED["experience_count"]:
        score += 10
    return score

print("=" * 65)
print("       CV PARSER - MODEL BENCHMARK (3 models x 2 runs)")
print("=" * 65)

results = []

for model in MODELS:
    print(f"\n🔄 Testing: {model}")
    print("-" * 45)

    times = []
    scores = []
    success_count = 0
    last_result = None

    for run in range(2):
        start = time.time()
        try:
            response = ollama.chat(
                model=model,
                messages=[{"role": "user", "content": PROMPT}],
                options={"temperature": 0}
            )
            elapsed = time.time() - start
            times.append(elapsed)

            raw = response["message"]["content"].strip()
            raw = raw.replace("```json", "").replace("```", "").strip()
            data = json.loads(raw)
            accuracy = score_result(data)
            scores.append(accuracy)
            success_count += 1
            last_result = data
            print(f"  Run {run+1}: ✅ {elapsed:.1f}s | accuracy: {accuracy}/100")

        except json.JSONDecodeError as e:
            elapsed = time.time() - start
            times.append(elapsed)
            scores.append(0)
            print(f"  Run {run+1}: ❌ JSON error ({elapsed:.1f}s)")
        except Exception as e:
            elapsed = time.time() - start
            times.append(elapsed)
            scores.append(0)
            print(f"  Run {run+1}: ❌ {str(e)[:40]} ({elapsed:.1f}s)")

    avg_time = sum(times) / len(times)
    avg_score = sum(scores) / len(scores)

    results.append({
        "model": model,
        "avg_time": avg_time,
        "avg_score": avg_score,
        "success_rate": success_count,
        "result": last_result
    })

    print(f"\n  📊 {model}:")
    print(f"     Avg time  : {avg_time:.1f}s")
    print(f"     Accuracy  : {avg_score:.0f}/100")
    print(f"     Success   : {success_count}/2")

print("\n" + "=" * 65)
print("FINAL RESULTS")
print("=" * 65)
print(f"\n{'Model':<35} {'Time':>8} {'Accuracy':>10} {'Success':>8}")
print("-" * 65)

best_model = None
best_score = -1

for r in results:
    marker = ""
    combined = (r["avg_score"] * 0.6) + ((30 - min(r["avg_time"], 30)) * 0.4 * (100/30))
    if combined > best_score:
        best_score = combined
        best_model = r["model"]
    print(f"{r['model']:<35} {r['avg_time']:>7.1f}s {r['avg_score']:>9.0f}/100 {r['success_rate']:>7}/2")

print("\n" + "=" * 65)
print(f"🏆 RECOMMENDED MODEL: {best_model}")
print("=" * 65)