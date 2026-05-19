"""
Post-transcription technical-term correction.

Whisper consistently mangles a handful of technical proper nouns even with
vocabulary boosting in the initial_prompt (e.g. "Spring Boot" -> "Springboard",
"PostgreSQL" -> "Postgre SQL"). The initial_prompt biases the language model
but does not guarantee the right surface form, so we apply a regex pass over
the final transcript.

Rules:
  - Whole-word, case-insensitive matching with \\b boundaries.
  - Preserve sentence flow — only touch the misspelled span.
  - Order matters: longer / more-specific phrases first so they win over
    shorter ones (e.g. "Springboard framework" before "Springboard").
  - Conservative on short tokens to avoid false positives in regular speech.
"""

import re
import logging

log = logging.getLogger("analysis")

# (pattern, replacement) — wrapped with \b…\b at compile time.
# Order matters: most specific first.
_RAW_RULES: list[tuple[str, str]] = [
    # ── Spring ecosystem ──────────────────────────────────────────────────────
    (r"spring\s*board\s+framework",           "Spring Boot framework"),
    (r"spring\s*boards?",                     "Spring Boot"),
    (r"spring\s+board",                       "Spring Boot"),
    (r"spring\s*boots",                       "Spring Boot"),
    (r"sprint\s*boot",                        "Spring Boot"),
    (r"spring\s+security",                    "Spring Security"),
    (r"spring\s+data\s+jpa",                  "Spring Data JPA"),
    (r"spring\s+cloud",                       "Spring Cloud"),
    (r"spring\s+mvc",                         "Spring MVC"),
    (r"spring\s+framework",                   "Spring Framework"),

    # ── Databases ─────────────────────────────────────────────────────────────
    (r"postgre\s*sql",                        "PostgreSQL"),
    (r"post\s*gres\s*sql",                    "PostgreSQL"),
    (r"post\s*gres",                          "PostgreSQL"),
    (r"posgree\s*sql",                        "PostgreSQL"),
    (r"pos\s*gree\s*sql",                     "PostgreSQL"),
    (r"my\s*sequel",                          "MySQL"),
    (r"mongo\s*db",                           "MongoDB"),
    (r"mongo\s+the\s+b",                      "MongoDB"),

    # ── Schema migration ──────────────────────────────────────────────────────
    (r"fly\s*weight",                         "Flyway"),
    (r"fly\s+way",                            "Flyway"),
    (r"liquid\s*base",                        "Liquibase"),

    # ── Containers / orchestration ────────────────────────────────────────────
    (r"docker\s+founders",                    "Docker containers"),
    (r"docker\s+founder",                     "Docker container"),
    (r"doctor\s+containers",                  "Docker containers"),
    (r"doctor\s+container",                   "Docker container"),
    (r"docker\s+composer",                    "Docker Compose"),
    (r"docker\s+swam",                        "Docker Swarm"),
    (r"cuber\s*net[ie]s",                     "Kubernetes"),
    (r"cuban\s*et[ie]s",                      "Kubernetes"),
    (r"kuber\s*net[ie]s",                     "Kubernetes"),

    # ── Microservices ─────────────────────────────────────────────────────────
    (r"micro\s+services",                     "microservices"),
    (r"micro\s+service",                      "microservice"),

    # ── Auth / security ───────────────────────────────────────────────────────
    (r"key\s*clock",                          "Keycloak"),
    (r"key\s+cloak",                          "Keycloak"),
    (r"keep\s*cloak",                         "Keycloak"),
    (r"key\s+plot",                           "Keycloak"),
    (r"o\s*auth\s+two",                       "OAuth2"),

    # ── Build tools ───────────────────────────────────────────────────────────
    (r"may\s*ven",                            "Maven"),
    (r"may\s+vin",                            "Maven"),

    # ── Messaging ─────────────────────────────────────────────────────────────
    (r"rabbit\s+mq",                          "RabbitMQ"),
    (r"rabbit\s*em\s*queue",                  "RabbitMQ"),
    (r"caf\s*ka",                             "Kafka"),

    # ── Caches / search ───────────────────────────────────────────────────────
    (r"elastic\s+search",                     "Elasticsearch"),

    # ── CI / CD ───────────────────────────────────────────────────────────────
    (r"ci\s*/\s*cd",                          "CI/CD"),
    (r"ci\s+cd",                              "CI/CD"),
    (r"git\s+hub",                            "GitHub"),
    (r"git\s+lab",                            "GitLab"),

    # ── Languages / runtimes ──────────────────────────────────────────────────
    (r"java\s+script",                        "JavaScript"),
    (r"type\s+script",                        "TypeScript"),
    (r"node\s+js",                            "Node.js"),
    (r"react\s+js",                           "React"),
    (r"angular\s+js",                         "Angular"),

    # ── ORMs / persistence ────────────────────────────────────────────────────
    (r"hibern[ae]te",                         "Hibernate"),
    (r"hibern\s*ation\s+orm",                 "Hibernate ORM"),

    # ── Database integrity ────────────────────────────────────────────────────
    (r"preferential\s+integrity",             "referential integrity"),

    # ── API / REST ────────────────────────────────────────────────────────────
    (r"rest\s+full\s+api",                    "RESTful API"),
    (r"rest\s+full",                          "RESTful"),
    (r"graph\s+q\s*l",                        "GraphQL"),
    (r"open\s+api",                           "OpenAPI"),
    (r"swag+er",                              "Swagger"),

    # ── Domain ────────────────────────────────────────────────────────────────
    (r"ver\s*meg",                            "VERMEG"),
    (r"vermag",                               "VERMEG"),
    (r"ver\s*mac",                            "VERMEG"),
    (r"fin\s+tech",                           "fintech"),
]


def _compile_rules(raw: list[tuple[str, str]]) -> list[tuple[re.Pattern, str]]:
    compiled = []
    for pat, repl in raw:
        # Word boundary at start if the pattern begins with a word character.
        prefix = r"\b" if pat[:1].isalnum() else ""
        suffix = r"\b" if pat[-1:].isalnum() else ""
        compiled.append((re.compile(prefix + pat + suffix, re.IGNORECASE), repl))
    return compiled


_RULES = _compile_rules(_RAW_RULES)


def correct_text(text: str) -> tuple[str, int]:
    """Apply technical-term corrections. Returns (new_text, replacement_count)."""
    if not text:
        return text, 0
    total = 0
    out = text
    for pat, repl in _RULES:
        out, n = pat.subn(repl, out)
        total += n
    return out, total


def correct_segments(segments: list[dict]) -> int:
    """Mutate segments in place. Returns total replacements applied."""
    total = 0
    for seg in segments:
        new_text, n = correct_text(seg.get("text", ""))
        if n:
            seg["text"] = new_text
            total += n
    return total
