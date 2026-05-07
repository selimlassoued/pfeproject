import logging
import requests
from bs4 import BeautifulSoup
from typing import Optional, Dict, List
import json

# Importation des modèles et utilitaires
from app.models import LinkedInEnrichment, CareerInsights, ExtracurricularInsights, SkillEvidence, EthicalAnalysis
from app.utils import analyze_career_stability

logger = logging.getLogger(__name__)

def enrich_from_linkedin(profile_url: str, cv_text: Optional[str] = None, profile_data: Optional[Dict] = None) -> Optional[dict]:
    """
    Enrichit le profil d'un candidat en extrayant des preuves factuelles depuis LinkedIn.
    Utilise qwen2.5:7b via la fonction _call_model du projet.
    """
    if not profile_url or "linkedin.com" not in profile_url:
        return None

    try:
        from app.nlp_parser import _call_model
        
        logger.info(f"Starting LinkedIn evidence extraction: {profile_url}")
        
        # 1. Scraping des posts
        posts = _scrape_linkedin_posts(profile_url)
        if not posts:
            posts = _get_fallback_posts()

        # 2. Analyse éthique (Extraction de faits via qwen2.5)
        ethical_facts = _analyze_ethical_facts(posts, _call_model)
        ethical_analysis = EthicalAnalysis(
            status=ethical_facts.get("status", "SAFE"),
            reason=ethical_facts.get("reason", ""),
            activity_level=ethical_facts.get("activity_level", "UNKNOWN"),
            top_topics=ethical_facts.get("top_topics", [])
        )

        # 3. Analyse croisée CV + LinkedIn pour extraction de preuves (via qwen2.5)
        cross_analysis = None
        if cv_text:
            linkedin_context = {
                "headline": profile_data.get("headline") if profile_data else "Non spécifié",
                "about": profile_data.get("about") if profile_data else "Non spécifié",
                "posts": posts,
                "certifications": profile_data.get("certifications", []) if profile_data else [],
                "experiences": profile_data.get("experiences", []) if profile_data else []
            }
            cross_analysis = _extract_evidence_with_qwen(cv_text, linkedin_context, _call_model)

        # 4. Construction de l'objet final compatible avec nlp_parser.py
        enrichment = LinkedInEnrichment(
            profile_url=profile_url,
            headline=profile_data.get("headline") if profile_data else None,
            about_section=profile_data.get("about") if profile_data else None,
            latest_posts=posts,
            certifications=profile_data.get("certifications", []) if profile_data else [],
            career_insights=cross_analysis.career_insights if cross_analysis else None,
            extracurricular_insights=cross_analysis.extracurricular_insights if cross_analysis else None,
            skill_validation=cross_analysis.skill_validation if cross_analysis else [],
            ethical_status=ethical_analysis.status,
            ethical_analysis=ethical_analysis
        )

        return enrichment.dict()

    except Exception as e:
        logger.error(f"LinkedIn enrichment failed: {e}")
        return None


def _extract_evidence_with_qwen(cv_text: str, linkedin_data: Dict, call_model_func) -> LinkedInEnrichment:
    """
    Utilise qwen2.5:7b pour extraire des preuves factuelles sans faire de scoring.
    """
    prompt = f"""
Tu es un expert en extraction de preuves factuelles pour le recrutement technique. 
Analyse le CV et les données LinkedIn pour identifier des preuves concrètes. 
NE FAIS AUCUN SCORING.

### DONNÉES :
CV: {cv_text}
LinkedIn: {json.dumps(linkedin_data, ensure_ascii=False)}

### MISSION :
1. **Career Insights** : Détecte factuellement le job hopping (>3 postes en 2 ans), identifie la plus longue durée (en mois) et résume la trajectoire.
2. **Extracurricular** : Identifie les hackathons, rôles de leadership (ex: IEEE) et l'impact communautaire.
3. **Skill Validation** : Pour chaque compétence du CV, cherche une preuve concrète sur LinkedIn (posts, certifications, projets).

### FORMAT JSON ATTENDU :
{{
    "career_insights": {{
        "job_hopping_flag": bool,
        "longest_tenure_months": int,
        "seniority_growth_summary": "string",
        "industry_loyalty_percentage": float
    }},
    "extracurricular_insights": {{
        "hackathon_enthusiast": bool,
        "leadership_roles": ["string"],
        "community_impact": "string"
    }},
    "skill_validation": [
        {{
            "skill": "string",
            "evidence_source": "string",
            "description": "string",
            "confidence_level": "High|Medium|Low"
        }}
    ]
}}
Réponds uniquement avec le JSON.
"""
    try:
        # Utilisation de la fonction call_model du projet
        result_json = call_model_func("LINKEDIN_EVIDENCE", prompt)
        
        if not isinstance(result_json, dict):
            logger.error(f"Unexpected response format from qwen2.5: {result_json}")
            return LinkedInEnrichment()

        # Affinement factuel pour la carrière via utils.py
        if linkedin_data.get('experiences'):
            career_analysis = analyze_career_stability(linkedin_data['experiences'])
            result_json['career_insights']['job_hopping_flag'] = career_analysis['job_hopping_flag']
            result_json['career_insights']['longest_tenure_months'] = career_analysis['longest_tenure_months']

        return LinkedInEnrichment(
            career_insights=CareerInsights(**result_json['career_insights']),
            extracurricular_insights=ExtracurricularInsights(**result_json['extracurricular_insights']),
            skill_validation=[SkillEvidence(**s) for s in result_json.get('skill_validation', [])]
        )
    except Exception as e:
        logger.error(f"Qwen evidence extraction failed: {e}")
        return LinkedInEnrichment()


def _analyze_ethical_facts(posts: list, call_model_func) -> dict:
    """
    Analyse éthique factuelle via qwen2.5:7b.
    """
    try:
        posts_text = "\n".join(posts[:5]) if posts else "No posts"
        prompt = f"""Analyze these posts for recruiting ethics. Extract facts ONLY.
Posts: {posts_text}

JSON format:
{{
    "risk_assessment": {{"status": "SAFE or FLAGGED", "reason": "string"}},
    "activity_insights": {{"level": "Low or High", "main_topics": ["string"]}}
}}"""
        
        result = call_model_func("LINKEDIN_AUDIT", prompt)
        if isinstance(result, dict):
            return {
                "status": result.get("risk_assessment", {}).get("status", "SAFE"),
                "reason": result.get("risk_assessment", {}).get("reason", ""),
                "activity_level": result.get("activity_insights", {}).get("level", "UNKNOWN"),
                "top_topics": result.get("activity_insights", {}).get("main_topics", [])
            }
        return {}
    except Exception as e:
        logger.error(f"Ethical analysis failed: {e}")
        return {}

def _scrape_linkedin_posts(profile_url: str) -> list:
    """Scraping basique de posts."""
    try:
        headers = {"User-Agent": "Mozilla/5.0"}
        response = requests.get(profile_url, headers=headers, timeout=5)
        soup = BeautifulSoup(response.content, 'html.parser')
        return [p.get_text(strip=True) for p in soup.find_all('p') if len(p.get_text()) > 30][:5]
    except:
        return []

def _get_fallback_posts() -> list:
    return ["Active in tech community.", "Sharing insights on development."]