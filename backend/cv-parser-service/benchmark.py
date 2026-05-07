import time
import json
import ollama

# ── Multiple CV fixtures of increasing difficulty ─────────────────────────

CV_FIXTURES = [

    # Fixture 1: Clean simple CV (your current one)
    {
        "id": "simple",
        "text": """
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
""",
        "expected": {
            "candidate_name": "Zaina Al Darras",
            "email": "zinadarras41@gmail.com",
            "skills_count": 7,
            "languages_count": 4,
            "education_count": 1,
            "experience_count": 2,
        }
    },

    # Fixture 2: French CV with messy formatting
    {
        "id": "french_messy",
        "text": """
A M I N E   B E N   S A L A H
Ingénieur Logiciel | amine.bensalah@outlook.fr | +216 98 123 456
linkedin.com/in/amine-bensalah | github.com/aminebensalah

PROFIL
Ingénieur passionné par le développement backend et les architectures microservices.
Rigoureux, autonome et orienté résultats.

EXPÉRIENCES PROFESSIONNELLES

Ingénieur Développeur – Vermeg, Tunis
Mars 2023 – Présent
- Développement de microservices avec Spring Boot et Kafka
- Mise en place de pipelines CI/CD avec Jenkins et Docker
- Optimisation des requêtes PostgreSQL (gain de 40% sur les temps de réponse)

Stagiaire Développeur Full Stack – Sofrecom, Tunis
Juin 2022 – Août 2022
- Développement d'une application React / Node.js pour la gestion RH
- Intégration d'une API REST avec authentification JWT

FORMATION
2019 – 2023 | Diplôme d'ingénieur en Génie Logiciel
École Nationale d'Ingénieurs de Tunis (ENIT)

COMPÉTENCES TECHNIQUES
Java, Spring Boot, Kafka, React, Node.js, PostgreSQL, Docker, Kubernetes, Jenkins, Git

LANGUES
Arabe : Langue maternelle
Français : Courant
Anglais : Avancé
""",
        "expected": {
            "candidate_name": "Amine Ben Salah",
            "email": "amine.bensalah@outlook.fr",
            "skills_count": 10,
            "languages_count": 3,
            "education_count": 1,
            "experience_count": 2,
        }
    },

    # Fixture 3: Tricky CV — company embedded in title, split dates, missing fields
    {
        "id": "tricky",
        "text": """
Mohamed Trabelsi
mohamedtrabelsi99@gmail.com

Software Engineer - Google (Remote)
January 2024 - Present
Built distributed data pipelines using Apache Spark and Scala.
Reduced processing time by 30% through query optimization.

Junior Developer - Telnet Holding
02/2022 - 12/2023
Developed REST APIs in Python/FastAPI. Managed deployments on AWS EC2.

EDUCATION
Master in Computer Science, University of Carthage, 2022, Mention: Très Bien
Bachelor in Computer Science, Faculty of Sciences of Tunis, 2020

Skills: Python, FastAPI, Scala, Apache Spark, AWS, Docker, PostgreSQL, Redis

Languages: English - Fluent, French - Native, Arabic - Native
""",
        "expected": {
            "candidate_name": "Mohamed Trabelsi",
            "email": "mohamedtrabelsi99@gmail.com",
            "skills_count": 8,
            "languages_count": 3,
            "education_count": 2,
            "experience_count": 2,
        }
    },
]

PROMPT_TEMPLATE = """Extract CV info and return ONLY valid JSON with no explanation:
{{
  "candidate_name": "full name or null",
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
{cv_text}

Return ONLY JSON, nothing else."""

MODELS = [
    "qwen2.5:7b"
]

def score_result(data: dict, expected: dict) -> dict:
    """Returns per-criterion scores for detailed breakdown."""
    scores = {}
    scores["name"]       = 25 if data.get("candidate_name") == expected["candidate_name"] else 0
    scores["email"]      = 20 if data.get("email") == expected["email"] else 0
    scores["skills"]     = 20 if len(data.get("skills", [])) >= expected["skills_count"] else round(20 * len(data.get("skills", [])) / expected["skills_count"])
    scores["languages"]  = 15 if len(data.get("languages", [])) >= expected["languages_count"] else round(15 * len(data.get("languages", [])) / expected["languages_count"])
    scores["education"]  = 10 if len(data.get("education", [])) >= expected["education_count"] else 0
    scores["experience"] = 10 if len(data.get("work_experience", [])) >= expected["experience_count"] else round(10 * len(data.get("work_experience", [])) / expected["experience_count"])
    scores["total"]      = sum(v for k, v in scores.items() if k != "total")
    return scores

# ── Run benchmark ─────────────────────────────────────────────────────────

all_results = {model: [] for model in MODELS}

for fixture in CV_FIXTURES:
    print(f"\n{'='*65}")
    print(f"  FIXTURE: {fixture['id'].upper()}")
    print(f"{'='*65}")

    prompt = PROMPT_TEMPLATE.format(cv_text=fixture["text"])

    for model in MODELS:
        print(f"\n  🔄 {model}")
        times, scores = [], []
        success = 0

        for run in range(2):
            start = time.time()
            try:
                response = ollama.chat(
                    model=model,
                    messages=[{"role": "user", "content": prompt}],
                    options={"temperature": 0}
                )
                elapsed = time.time() - start
                times.append(elapsed)
                raw = response["message"]["content"].strip()
                raw = raw.replace("```json", "").replace("```", "").strip()
                data = json.loads(raw)
                breakdown = score_result(data, fixture["expected"])
                scores.append(breakdown["total"])
                success += 1
                print(f"     Run {run+1}: ✅ {elapsed:.1f}s | {breakdown['total']}/100 "
                      f"(name:{breakdown['name']} email:{breakdown['email']} "
                      f"skills:{breakdown['skills']} langs:{breakdown['languages']} "
                      f"edu:{breakdown['education']} exp:{breakdown['experience']})")
            except json.JSONDecodeError:
                elapsed = time.time() - start
                times.append(elapsed)
                scores.append(0)
                print(f"     Run {run+1}: ❌ JSON parse error ({elapsed:.1f}s)")
            except Exception as e:
                elapsed = time.time() - start
                times.append(elapsed)
                scores.append(0)
                print(f"     Run {run+1}: ❌ {str(e)[:50]} ({elapsed:.1f}s)")

        avg_time  = sum(times) / len(times)
        avg_score = sum(scores) / len(scores)
        all_results[model].append({
            "fixture": fixture["id"],
            "avg_time": avg_time,
            "avg_score": avg_score,
            "success": success
        })

# ── Final summary ─────────────────────────────────────────────────────────

print(f"\n\n{'='*75}")
print("FINAL SUMMARY — averaged across all fixtures")
print(f"{'='*75}")
print(f"\n{'Model':<35} {'Avg Time':>10} {'Avg Accuracy':>14} {'JSON Success':>13}")
print("-" * 75)

best_model   = None
best_combined = -1

for model in MODELS:
    runs       = all_results[model]
    avg_time   = sum(r["avg_time"]  for r in runs) / len(runs)
    avg_acc    = sum(r["avg_score"] for r in runs) / len(runs)
    total_succ = sum(r["success"]   for r in runs)
    total_runs = len(runs) * 2

    # Fixed combined score: accuracy 60%, speed 40%
    # Speed: normalize so fastest gets 100, slowest gets 0
    # We collect all times first then normalize below
    all_results[model].append({"__summary__": True, "avg_time": avg_time, "avg_acc": avg_acc, "succ": total_succ, "runs": total_runs})

summaries = {}
for model in MODELS:
    s = next(r for r in all_results[model] if r.get("__summary__"))
    summaries[model] = s

all_times = [s["avg_time"] for s in summaries.values()]
min_t, max_t = min(all_times), max(all_times)

for model in MODELS:
    s = summaries[model]
    speed_score = 100 * (max_t - s["avg_time"]) / (max_t - min_t) if max_t != min_t else 100
    combined    = s["avg_acc"] * 0.6 + speed_score * 0.4
    s["combined"] = combined
    marker = " 🏆" if combined > best_combined else ""
    if combined > best_combined:
        best_combined = combined
        best_model    = model
    print(f"{model:<35} {s['avg_time']:>9.1f}s {s['avg_acc']:>13.1f}/100 {s['succ']:>8}/{s['runs']}")

print(f"\n{'='*75}")
print(f"🏆 RECOMMENDED MODEL: {best_model}")
print(f"{'='*75}")
print("\nPer-fixture breakdown:")
for model in MODELS:
    print(f"\n  {model}")
    for r in all_results[model]:
        if not r.get("__summary__"):
            print(f"    {r['fixture']:<20} {r['avg_time']:>7.1f}s  {r['avg_score']:>5.1f}/100  ✅{r['success']}/2")