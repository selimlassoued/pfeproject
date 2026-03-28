from app.evaluator import detect_spelling_warnings, _spell_fr, _spell_en
from app.models import CvAnalysisResult, CvEvaluation

# CV minimal pour le test
cv = CvAnalysisResult(
    application_id="test",
    skills=["Angular", "Spring Boot"],
    soft_skills=[],
    languages=[],
    certifications=[],
    work_experience=[],
    education=[],
    projects=[],
    hackathons=[],
    volunteer_work=[],
    awards=[]
)

ev = CvEvaluation()

raw_text = """
Developpement d'une application web
Managment de projet agile
Stage de perfectionnement
Angular Spring Boot Python
"""

detect_spelling_warnings(raw_text, cv, ev)

print(f"Fautes trouvées: {ev.likely_typos_count}")
for w in ev.spelling_warnings:
    print(f"  → {w}")