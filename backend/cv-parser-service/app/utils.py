from datetime import datetime
from typing import List, Dict

def calculate_tenure(start_date: str, end_date: str = None) -> int:
    """
    Calcule la durée en mois entre deux dates au format 'YYYY-MM'.
    """
    fmt = "%Y-%m"
    try:
        start = datetime.strptime(start_date, fmt)
        end = datetime.strptime(end_date, fmt) if end_date else datetime.now()
        return (end.year - start.year) * 12 + (end.month - start.month)
    except:
        return 0

def analyze_career_stability(experiences: List[Dict]) -> Dict:
    """
    Analyse factuelle de la stabilité (job hopping et durée max).
    Le scoring final est laissé à nlp_parser.py.
    """
    total_tenure = 0
    max_tenure = 0
    recent_jobs_count = 0
    now = datetime.now()
    
    for exp in experiences:
        if not exp.get('start_date'): continue
        
        tenure = calculate_tenure(exp['start_date'], exp.get('end_date'))
        total_tenure += tenure
        if tenure > max_tenure:
            max_tenure = tenure
            
        # Vérification factuelle du job hopping (dernières 24 mois)
        try:
            start = datetime.strptime(exp['start_date'], "%Y-%m")
            if (now.year - start.year) * 12 + (now.month - start.month) <= 24:
                recent_jobs_count += 1
        except:
            continue
            
    return {
        "job_hopping_flag": recent_jobs_count > 3,
        "longest_tenure_months": max_tenure,
        "average_tenure": total_tenure / len(experiences) if experiences else 0
    }