import re
import json
import logging
import os
from typing import Optional, List
from datetime import datetime, timezone
from urllib.request import urlopen, Request
from urllib.error import URLError, HTTPError

logger = logging.getLogger(__name__)

GITHUB_TOKEN       = os.getenv("GITHUB_TOKEN", "")  # optional — raises limit to 5000/hour

# Minimum number of own repos required to proceed to detailed analysis.
# Below this threshold → NO_PUBLIC_WORK (neutral, not penalized).
MIN_ASSESSABLE_REPOS = 3


# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────

def extract_github_username(github_url: Optional[str]) -> Optional[str]:
    """Extract username from https://github.com/username"""
    if not github_url:
        return None
    m = re.search(r"github\.com/([a-zA-Z0-9_\-]+)/?$", github_url.strip())
    return m.group(1) if m else None


def _get(url: str, timeout: int = 10) -> Optional[dict | list]:
    """GET request to GitHub API. Returns parsed JSON or None on error."""
    headers = {
        "User-Agent": "HireAI-CV-Parser/1.0",
        "Accept": "application/vnd.github+json",
    }
    if GITHUB_TOKEN:
        headers["Authorization"] = f"Bearer {GITHUB_TOKEN}"
    try:
        req = Request(url, headers=headers)
        with urlopen(req, timeout=timeout) as resp:
            remaining = resp.headers.get("X-RateLimit-Remaining", "?")
            if remaining != "?" and int(remaining) < 10:
                logger.warning(f"[GitHub] Rate limit low: {remaining} requests remaining")
            return json.loads(resp.read().decode())
    except HTTPError as e:
        if e.code == 404:
            logger.warning(f"[GitHub] 404 Not Found: {url}")
        elif e.code == 403:
            logger.warning(
                "[GitHub] Rate limit reached (60/hour unauthenticated). "
                "Add GITHUB_TOKEN env var to increase to 5000/hour."
            )
        else:
            logger.warning(f"[GitHub] HTTP {e.code}: {url}")
        return None
    except Exception as e:
        logger.warning(f"[GitHub] Request failed: {e}")
        return None


def _get_raw(url: str) -> str:
    """Fetch raw text content of a file URL. Returns empty string on error."""
    if not url:
        return ""
    headers = {"User-Agent": "HireAI-CV-Parser/1.0"}
    if GITHUB_TOKEN:
        headers["Authorization"] = f"Bearer {GITHUB_TOKEN}"
    try:
        req = Request(url, headers=headers)
        with urlopen(req, timeout=10) as resp:
            return resp.read().decode("utf-8", errors="ignore")
    except Exception:
        return ""


def _get_commit_count_and_ownership(username: str, repo_name: str) -> tuple[int, float]:
    """
    Get total commit count AND the candidate's ownership ratio (0.0–1.0).

    Uses the contributors endpoint:
    - Total commits = sum of all contributors' contributions
    - Ownership ratio = candidate's contributions / total contributions

    Returns (total_commits, ownership_ratio).
    Returns (0, 0.0) if not accessible.
    """
    data = _get(
        f"https://api.github.com/repos/{username}/{repo_name}"
        f"/contributors?per_page=10&anon=true"
    )
    if not data or not isinstance(data, list):
        return 0, 0.0

    total = sum(c.get("contributions", 0) for c in data if isinstance(c, dict))
    if total == 0:
        return 0, 0.0

    candidate_contributions = 0
    for c in data:
        if isinstance(c, dict):
            login = (c.get("login") or "").lower()
            if login == username.lower():
                candidate_contributions = c.get("contributions", 0)
                break

    ownership_ratio = round(candidate_contributions / total, 2) if total > 0 else 0.0
    return total, ownership_ratio


def _get_commit_count(username: str, repo_name: str) -> int:
    count, _ = _get_commit_count_and_ownership(username, repo_name)
    return count


def _get_branch_count(username: str, repo_name: str) -> int:
    data = _get(
        f"https://api.github.com/repos/{username}/{repo_name}"
        f"/branches?per_page=100"
    )
    if not data or not isinstance(data, list):
        return 0
    return len(data)


def _compute_commit_activity(
    commit_count: int,
    days_of_activity: int,
    last_pushed: str,
) -> dict:
    """
    Derive commit consistency from data already fetched — no extra API calls.
    is_consistent: worked 30+ days AND 10+ commits AND pushed in last 6 months.
    """
    from datetime import date as _date, datetime as _datetime

    days_since_push = 9999
    if last_pushed:
        try:
            pushed = _datetime.fromisoformat(last_pushed).date()
            days_since_push = (_date.today() - pushed).days
        except Exception:
            pass

    recently_active  = days_since_push < 180
    sustained_effort = days_of_activity >= 30 and commit_count >= 10

    return {
        "weekly_counts":       [],
        "active_weeks":        0,
        "recent_weeks_active": 1 if recently_active else 0,
        "longest_streak":      0,
        "is_consistent":       sustained_effort and recently_active,
        "recently_active":     recently_active,
        "days_since_push":     days_since_push,
    }


def _get_repo_languages(username: str, repo_name: str) -> List[str]:
    _NOISE_LANGUAGES = {
        "cmake", "makefile", "meson",
        "shell", "batchfile", "powershell",
        "swift", "objective-c", "objective-c++", "c",
        "dockerfile", "hcl", "nix",
        "go template", "smarty", "mustache", "blade",
        "twig", "jinja", "handlebars", "ejs", "erb",
        "scss", "sass", "less", "css",
        "xslt", "plpgsql", "liquid",
    }

    data = _get(
        f"https://api.github.com/repos/{username}/{repo_name}/languages"
    )
    if not data or not isinstance(data, dict):
        return []

    filtered = [
        lang for lang, _ in sorted(data.items(), key=lambda x: x[1], reverse=True)
        if lang.lower() not in _NOISE_LANGUAGES
    ]
    return filtered


def _compute_project_complexity(
    frameworks: List[str],
    all_languages: List[str],
    commit_count: int,
    branch_count: int,
    size_kb: int,
    has_description: bool,
    topics: List[str],
    ownership_ratio: float,
) -> dict:
    score = 0
    reasons = []

    tech_count = len(set(frameworks)) + max(0, len(all_languages) - len(frameworks))
    if tech_count >= 4:
        score += 3
        reasons.append(f"{tech_count} distinct technologies")
    elif tech_count >= 2:
        score += 2
        reasons.append(f"{tech_count} technologies")
    elif tech_count == 1:
        score += 1

    if len(frameworks) >= 2:
        score += 1
        reasons.append(f"{len(frameworks)} frameworks integrated")

    if commit_count >= 30:
        score += 2
        reasons.append(f"{commit_count} commits")
    elif commit_count >= 10:
        score += 1

    if branch_count >= 5:
        score += 1
        reasons.append("structured branching")

    if size_kb >= 5000:
        score += 1
        reasons.append(f"{size_kb}KB codebase")

    if has_description and topics:
        score += 1
        reasons.append("documented with topics")
    elif has_description:
        score += 1
        reasons.append("has description")

    if ownership_ratio >= 0.8:
        score += 1
        reasons.append(f"{int(ownership_ratio * 100)}% authored by candidate")

    label = "HIGH" if score >= 7 else "MEDIUM" if score >= 4 else "LOW"
    return {
        "complexity_score": score,
        "complexity_label": label,
        "complexity_reasons": reasons,
    }


def _get_repo_frameworks(username: str, repo_name: str) -> List[str]:
    import json as _json

    detected = set()
    frameworks = []

    def add(fw: str):
        if fw not in detected:
            detected.add(fw)
            frameworks.append(fw)

    def fetch_text(url: str) -> str:
        if not url:
            return ""
        try:
            from urllib.request import urlopen, Request as URequest
            req = URequest(url, headers={"User-Agent": "HireAI-CV-Parser/1.0"})
            with urlopen(req, timeout=10) as resp:
                return resp.read().decode("utf-8", errors="ignore")
        except Exception:
            return ""

    def scan_directory(items: list) -> None:
        if not items:
            return

        files = {
            item["name"].lower(): item.get("download_url")
            for item in items
            if isinstance(item, dict) and item.get("type") == "file"
        }
        folders = {
            item["name"].lower()
            for item in items
            if isinstance(item, dict) and item.get("type") == "dir"
        }

        if "pubspec.yaml" in files:
            add("Flutter")

        if "pom.xml" in files:
            content = fetch_text(files["pom.xml"]).lower()
            if "spring-boot" in content:
                add("Spring Boot")
            elif "quarkus" in content:
                add("Quarkus")
            elif "micronaut" in content:
                add("Micronaut")
            else:
                add("Java/Maven")

        gradle = next((f for f in files if "build.gradle" in f), None)
        if gradle and "Spring Boot" not in detected:
            content = fetch_text(files[gradle]).lower()
            if "spring-boot" in content:
                add("Spring Boot")
            elif "compose" in content and "Flutter" not in detected:
                add("Jetpack Compose")
            elif "android" in content and "Flutter" not in detected:
                add("Android")
            elif "multiplatform" in content:
                add("Kotlin Multiplatform")

        csproj_maui = next((f for f in files if f.endswith(".csproj")), None)
        if csproj_maui:
            csproj_content = fetch_text(files[csproj_maui]).lower()
            if "maui" in csproj_content:
                add(".NET MAUI")

        if "package.json" in files:
            content = fetch_text(files["package.json"])
            try:
                pkg = _json.loads(content)
                all_deps = {}
                all_deps.update(pkg.get("dependencies", {}))
                all_deps.update(pkg.get("devDependencies", {}))
                dep_names = {d.lower() for d in all_deps.keys()}

                if "@angular/core" in dep_names:
                    add("Angular")
                elif "react-native" in dep_names:
                    add("React Native")
                elif "@remix-run/react" in dep_names or "@remix-run/node" in dep_names:
                    add("Remix")
                elif "react" in dep_names:
                    if "next" in dep_names:
                        add("Next.js")
                    else:
                        add("React")
                elif "@sveltejs/kit" in dep_names:
                    add("SvelteKit")
                elif "svelte" in dep_names:
                    add("Svelte")
                elif "astro" in dep_names:
                    add("Astro")
                elif "vue" in dep_names:
                    if "nuxt" in dep_names:
                        add("Nuxt.js")
                    else:
                        add("Vue.js")
                elif "@nestjs/core" in dep_names:
                    add("NestJS")
                elif "fastify" in dep_names:
                    add("Fastify")
                elif "express" in dep_names:
                    add("Express.js")
                else:
                    add("Node.js")
            except Exception:
                if "angular.json" in files:
                    add("Angular")
                else:
                    add("Node.js")

        if "angular.json" in files and "Angular" not in detected:
            add("Angular")

        if "requirements.txt" in files:
            content = fetch_text(files["requirements.txt"]).lower()
            if "django" in content:        add("Django")
            elif "flask" in content:       add("Flask")
            elif "fastapi" in content:     add("FastAPI")
            elif "streamlit" in content:   add("Streamlit")
            else:                          add("Python")

        elif "pyproject.toml" in files:
            content = fetch_text(files["pyproject.toml"]).lower()
            if "django" in content:        add("Django")
            elif "fastapi" in content:     add("FastAPI")
            elif "flask" in content:       add("Flask")
            else:                          add("Python")

        csproj = next((f for f in files if f.endswith(".csproj")), None)
        if csproj:
            content = fetch_text(files[csproj]).lower()
            if "aspnetcore" in content or "microsoft.aspnetcore" in content:
                add("ASP.NET Core")
            else:
                add(".NET")

        if "composer.json" in files:
            content = fetch_text(files["composer.json"]).lower()
            if "laravel" in content:       add("Laravel")
            elif "symfony" in content:     add("Symfony")
            else:                          add("PHP")

        if "gemfile" in files:
            content = fetch_text(files["gemfile"]).lower()
            if "rails" in content:         add("Ruby on Rails")
            else:                          add("Ruby")

        if "docker-compose.yml" in files or "docker-compose.yaml" in files:
            add("Docker")
        elif "dockerfile" in files:
            add("Docker")

        if "k8s" in folders or "kubernetes" in folders:
            add("Kubernetes")

        if ".github" in folders:
            add("GitHub Actions")

    root_data = _get(
        f"https://api.github.com/repos/{username}/{repo_name}/contents"
    )
    if not root_data or not isinstance(root_data, list):
        return []

    scan_directory(root_data)

    _SKIP_FOLDERS = {
        ".github", "node_modules", "vendor", "test", "tests",
        "docs", "documentation", "assets", "resources", "public",
        "static", "images", "img", "dist", "build", "target",
        ".git", ".idea", ".vscode",
    }

    subfolders = [
        item for item in root_data
        if isinstance(item, dict)
        and item.get("type") == "dir"
        and item["name"].lower() not in _SKIP_FOLDERS
    ]

    for folder in subfolders[:8]:
        folder_data = _get(
            f"https://api.github.com/repos/{username}/{repo_name}"
            f"/contents/{folder['name']}"
        )
        if folder_data and isinstance(folder_data, list):
            scan_directory(folder_data)

    return frameworks


# ─────────────────────────────────────────────────────────────────────────────
# Repo scoring
# ─────────────────────────────────────────────────────────────────────────────

def _days_between(date_str1: Optional[str], date_str2: Optional[str]) -> int:
    if not date_str1 or not date_str2:
        return 0
    try:
        d1 = datetime.fromisoformat(date_str1.replace("Z", "+00:00"))
        d2 = datetime.fromisoformat(date_str2.replace("Z", "+00:00"))
        return abs((d2 - d1).days)
    except Exception:
        return 0


def _score_repo(
    repo: dict,
    commit_count: int = 0,
    branch_count: int = 0,
    repo_languages: Optional[List[str]] = None,
    frameworks: Optional[List[str]] = None,
    ownership_ratio: float = 0.0,
    commit_activity: Optional[dict] = None,
) -> dict:
    score = 0
    reasons = []

    is_fork     = repo.get("fork", False)
    size_kb     = repo.get("size", 0)
    has_desc    = bool((repo.get("description") or "").strip())
    days_active = _days_between(repo.get("created_at"), repo.get("pushed_at"))
    stars       = repo.get("stargazers_count", 0)
    topics      = repo.get("topics") or []

    if not is_fork:
        score += 3
        reasons.append("own work")

    if commit_count >= 10:
        score += 2
        reasons.append(f"{commit_count} commits")
    if commit_count >= 30:
        score += 1

    if size_kb >= 100:
        score += 2
        reasons.append(f"{size_kb}KB of code")
    if size_kb >= 1000:
        score += 1

    if has_desc:
        score += 1
        reasons.append("has description")

    if days_active >= 7:
        score += 2
        reasons.append(f"{days_active} days active")
    if days_active >= 30:
        score += 1

    if branch_count >= 2:
        score += 1
        reasons.append(f"{branch_count} branches")
    if branch_count >= 5:
        score += 1
        reasons.append("advanced branching")

    if stars >= 1:
        score += 1
        reasons.append(f"{stars} stars")

    if topics:
        score += 1
        reasons.append("has topics")

    complexity = _compute_project_complexity(
        frameworks=frameworks or [],
        all_languages=repo_languages or ([repo.get("language")] if repo.get("language") else []),
        commit_count=commit_count,
        branch_count=branch_count,
        size_kb=size_kb,
        has_description=has_desc,
        topics=topics,
        ownership_ratio=ownership_ratio,
    )

    return {
        "name":                repo.get("name"),
        "description":         repo.get("description"),
        "language":            repo.get("language"),
        "all_languages":       repo_languages or ([repo.get("language")] if repo.get("language") else []),
        "frameworks":          frameworks or [],
        "technologies":        [],
        "stars":               stars,
        "url":                 repo.get("html_url"),
        "is_fork":             is_fork,
        "size_kb":             size_kb,
        "commit_count":        commit_count,
        "branch_count":        branch_count,
        "days_of_activity":    days_active,
        "has_description":     has_desc,
        "last_pushed":         (repo.get("pushed_at") or "")[:10],
        "topics":              topics,
        "score":               score,
        "is_real":             score >= 5,
        "score_reasons":       reasons,
        "ownership_ratio":     ownership_ratio,
        "commit_activity":     commit_activity or {
            "weekly_counts": [], "active_weeks": 0,
            "recent_weeks_active": 0, "longest_streak": 0,
            "is_consistent": False, "recently_active": False, "days_since_push": 9999,
        },
        "complexity_score":    complexity["complexity_score"],
        "complexity_label":    complexity["complexity_label"],
        "complexity_reasons":  complexity["complexity_reasons"],
    }


# ─────────────────────────────────────────────────────────────────────────────
# Overall GitHub score
# ─────────────────────────────────────────────────────────────────────────────

def _compute_github_score(real_repos_count: int, own_repos_count: int) -> str:
    """
    Called only after the early-exit check passes (own_repos >= MIN_ASSESSABLE_REPOS).

    NO_PUBLIC_WORK → passed the count check but zero repos pass quality threshold.
                     Neutral — treated same as no GitHub provided, no penalty.
    STRONG         → 3+ real repos (score >= 5)
    MODERATE       → 1-2 real repos

    RATE_LIMITED is returned before this function is called (repos API failed).
    WEAK and INACTIVE are eliminated — subsumed by NO_PUBLIC_WORK.
    """
    if real_repos_count == 0:
        return "NO_PUBLIC_WORK"
    if real_repos_count >= 3:   return "STRONG"
    else:                       return "MODERATE"


# ─────────────────────────────────────────────────────────────────────────────
# Collaboration signals
# ─────────────────────────────────────────────────────────────────────────────

def _compute_collaboration_signals(username: str, forked_repos: List[dict]) -> dict:
    non_empty_forks = [
        r for r in forked_repos
        if r.get("size", 0) > 0
    ][:5]

    collaborated = []
    for repo in non_empty_forks:
        repo_name = repo.get("name", "")
        if not repo_name:
            continue

        contributors = _get(
            f"https://api.github.com/repos/{username}/{repo_name}"
            f"/contributors?per_page=10&anon=true"
        )
        if not contributors or not isinstance(contributors, list):
            continue

        total = sum(c.get("contributions", 0) for c in contributors if isinstance(c, dict))
        candidate_contribs = 0
        for c in contributors:
            if isinstance(c, dict) and (c.get("login") or "").lower() == username.lower():
                candidate_contribs = c.get("contributions", 0)
                break

        other_contribs = total - candidate_contribs
        if candidate_contribs >= 1 and other_contribs >= 1:
            collaborated.append(repo_name)

    return {
        "active_forks_count": len(collaborated),
        "collaborated_repos": collaborated,
        "has_collaboration": len(collaborated) > 0,
    }


# ─────────────────────────────────────────────────────────────────────────────
# Neutral profile — returned when account cannot be assessed
# ─────────────────────────────────────────────────────────────────────────────

def _neutral_profile(username: str, profile: dict, account_age_days: int,
                     own_repos_count: int, forked_repos_count: int,
                     github_score: str) -> dict:
    """
    Build a profile dict with identity fields populated but all scoring
    fields neutral. Used for NO_PUBLIC_WORK and RATE_LIMITED cases.
    verification_skipped=True signals to evaluator and Angular to show
    a neutral message instead of penalizing the candidate.
    """
    return {
        "username":              username,
        "account_url":           f"https://github.com/{username}",
        "name":                  profile.get("name"),
        "bio":                   profile.get("bio"),
        "location":              profile.get("location"),
        "public_repos_count":    profile.get("public_repos", 0),
        "own_repos_count":       own_repos_count,
        "forked_repos_count":    forked_repos_count,
        "account_age_days":      account_age_days,
        "followers":             profile.get("followers", 0),
        "last_active":           None,
        "all_technologies":      [],
        "all_repo_frameworks":   [],
        "total_stars":           0,
        "real_repos_count":      0,
        "scored_repos":          [],
        "github_score":          github_score,
        # Skills verification — all empty, don't penalize
        "cv_skills_confirmed":   [],
        "cv_skills_likely":      [],
        "cv_skills_no_evidence": [],
        # Flag consumed by evaluator and Angular
        "verification_skipped":  True,
        # Profile-level activity — all neutral
        "consistent_repos":      [],
        "recently_active_repos": 0,
        "avg_ownership_ratio":   0.0,
        "collaboration": {
            "active_forks_count": 0,
            "collaborated_repos": [],
            "has_collaboration":  False,
        },
        # Internal verification helpers — empty
        "confirmed_lower":       [],
        "top_langs_lower":       [],
        "lang_implies_fw":       {},
    }


# ─────────────────────────────────────────────────────────────────────────────
# Main enrichment entry point
# ─────────────────────────────────────────────────────────────────────────────

def enrich_from_github(github_url: Optional[str]) -> Optional[dict]:
    username = extract_github_username(github_url)
    if not username:
        logger.warning(f"[GitHub] Could not extract username from: {github_url}")
        return None

    logger.info(f"[GitHub] Starting enrichment for: {username}")

    # ── API Call 1: User profile ──────────────────────────────────────────────
    profile = _get(f"https://api.github.com/users/{username}")
    if not profile or not isinstance(profile, dict):
        logger.warning(f"[GitHub] User not found: {username}")
        return None

    account_age_days = _days_between(
        profile.get("created_at"),
        datetime.now(timezone.utc).isoformat()
    )

    # ── API Call 2: Repository list ───────────────────────────────────────────
    repos_raw = _get(
        f"https://api.github.com/users/{username}/repos"
        f"?sort=updated&per_page=100"
    )
    repos = repos_raw if isinstance(repos_raw, list) else []

    # Rate limited — return neutral profile, not a penalty
    if not repos and repos_raw is None:
        logger.warning(f"[GitHub] Repos call failed for {username} — rate limit likely hit")
        return _neutral_profile(
            username, profile, account_age_days,
            own_repos_count=0,
            forked_repos_count=0,
            github_score="RATE_LIMITED",
        )

    own_repos    = [r for r in repos if not r.get("fork", False)]
    forked_repos = [r for r in repos if r.get("fork", False)]

    # ── Early exit: not enough public repos to assess ─────────────────────────
    # Covers: new accounts, private-only developers, workshop-only profiles.
    # All treated identically — unassessable, no penalty.
    if len(own_repos) < MIN_ASSESSABLE_REPOS:
        logger.info(
            f"[GitHub] {username} has {len(own_repos)} own repo(s) — "
            f"below MIN_ASSESSABLE_REPOS={MIN_ASSESSABLE_REPOS}. "
            f"Returning NO_PUBLIC_WORK (neutral, no penalty)."
        )
        return _neutral_profile(
            username, profile, account_age_days,
            own_repos_count=len(own_repos),
            forked_repos_count=len(forked_repos),
            github_score="NO_PUBLIC_WORK",
        )

    # ── API Calls 3+: Detailed analysis for top 3 repos ──────────────────────
    repos_to_check = sorted(
        [r for r in own_repos if r.get("size", 0) > 0],
        key=lambda r: (
            r.get("size", 0) +
            _days_between(r.get("created_at"), r.get("pushed_at")) * 500
        ),
        reverse=True
    )[:3]

    repos_to_check_names = {r["name"] for r in repos_to_check}
    logger.info(f"[GitHub] Detailed check for: {list(repos_to_check_names)}")

    scored_repos = []
    for repo in own_repos:
        commit_count    = 0
        ownership_ratio = 0.0
        branch_count    = 0
        repo_languages  = None
        repo_frameworks = None
        commit_activity = None

        if repo["name"] in repos_to_check_names:
            commit_count, ownership_ratio = _get_commit_count_and_ownership(username, repo["name"])
            branch_count    = _get_branch_count(username, repo["name"])
            repo_languages  = _get_repo_languages(username, repo["name"])
            repo_frameworks = _get_repo_frameworks(username, repo["name"])
            commit_activity = _compute_commit_activity(
                commit_count=commit_count,
                days_of_activity=_days_between(repo.get("created_at"), repo.get("pushed_at")),
                last_pushed=(repo.get("pushed_at") or "")[:10],
            )
            logger.info(
                f"[GitHub] {repo['name']}: "
                f"{commit_count} commits, ownership={ownership_ratio:.0%}, "
                f"{branch_count} branches, languages={repo_languages[:3]}, "
                f"frameworks={repo_frameworks}, "
                f"consistent={commit_activity.get('is_consistent', False)}"
            )

        scored_repos.append(_score_repo(
            repo,
            commit_count=commit_count,
            branch_count=branch_count,
            repo_languages=repo_languages,
            frameworks=repo_frameworks,
            ownership_ratio=ownership_ratio,
            commit_activity=commit_activity,
        ))

    # ── Collaboration signals ─────────────────────────────────────────────────
    collaboration = _compute_collaboration_signals(username, forked_repos)
    logger.info(
        f"[GitHub] Collaboration: active_forks={collaboration['active_forks_count']}, "
        f"repos={collaboration['collaborated_repos']}"
    )

    # ── Framework scan of ALL repos ───────────────────────────────────────────
    logger.info(f"[GitHub] Scanning all {len(own_repos)} repos for frameworks...")
    all_repo_frameworks: set[str] = set()

    for repo in scored_repos:
        for fw in repo.get("frameworks", []):
            if fw:
                all_repo_frameworks.add(fw)

    _LANG_IMPLIES_FRAMEWORK = {
        "blade":  "Laravel",
        "ruby":   "Ruby on Rails",
        "dart":   "Flutter",
        "swift":  "iOS",
    }
    for repo in own_repos:
        if repo["name"] in repos_to_check_names:
            continue
        lang = (repo.get("language") or "").lower()
        if lang in _LANG_IMPLIES_FRAMEWORK:
            all_repo_frameworks.add(_LANG_IMPLIES_FRAMEWORK[lang])

    import time as _time
    _scan_start = _time.time()
    _SCAN_BUDGET = 18

    for repo in own_repos:
        if _time.time() - _scan_start > _SCAN_BUDGET:
            logger.info("[GitHub] All-repo scan budget exhausted — stopping early")
            break

        if repo.get("size", 0) == 0:
            continue
        if repo["name"] in repos_to_check_names:
            continue

        lang = (repo.get("language") or "").lower()
        if lang in _LANG_IMPLIES_FRAMEWORK:
            continue

        root_data = _get(
            f"https://api.github.com/repos/{username}/{repo['name']}/contents"
        )
        if not root_data or not isinstance(root_data, list):
            continue

        root_files = {
            item["name"].lower(): item.get("download_url")
            for item in root_data
            if isinstance(item, dict) and item.get("type") == "file"
        }
        root_folders = {
            item["name"].lower()
            for item in root_data
            if isinstance(item, dict) and item.get("type") == "dir"
        }

        if "pubspec.yaml" in root_files:    all_repo_frameworks.add("Flutter")
        if "angular.json" in root_files:    all_repo_frameworks.add("Angular")
        if "manage.py" in root_files:       all_repo_frameworks.add("Django")

        if "pom.xml" in root_files:
            pom = _get_raw(root_files["pom.xml"]).lower()
            if "spring-boot"  in pom: all_repo_frameworks.add("Spring Boot")
            elif "quarkus"    in pom: all_repo_frameworks.add("Quarkus")
            elif "micronaut"  in pom: all_repo_frameworks.add("Micronaut")
            else:                     all_repo_frameworks.add("Java/Maven")

        if "requirements.txt" in root_files:
            req = _get_raw(root_files["requirements.txt"]).lower()
            if "django"      in req: all_repo_frameworks.add("Django")
            elif "fastapi"   in req: all_repo_frameworks.add("FastAPI")
            elif "flask"     in req: all_repo_frameworks.add("Flask")
            elif "streamlit" in req: all_repo_frameworks.add("Streamlit")
            else:                    all_repo_frameworks.add("Python")

        if "package.json" in root_files:
            try:
                import json as _json2
                pkg = _json2.loads(_get_raw(root_files["package.json"]))
                deps = set()
                deps.update(pkg.get("dependencies", {}).keys())
                deps.update(pkg.get("devDependencies", {}).keys())
                deps_lower = {d.lower() for d in deps}
                if "@angular/core"      in deps_lower: all_repo_frameworks.add("Angular")
                elif "react-native"     in deps_lower: all_repo_frameworks.add("React Native")
                elif "@remix-run/react" in deps_lower: all_repo_frameworks.add("Remix")
                elif "react"            in deps_lower:
                    all_repo_frameworks.add("Next.js" if "next" in deps_lower else "React")
                elif "@sveltejs/kit"    in deps_lower: all_repo_frameworks.add("SvelteKit")
                elif "svelte"           in deps_lower: all_repo_frameworks.add("Svelte")
                elif "astro"            in deps_lower: all_repo_frameworks.add("Astro")
                elif "vue"              in deps_lower:
                    all_repo_frameworks.add("Nuxt.js" if "nuxt" in deps_lower else "Vue.js")
                elif "@nestjs/core"     in deps_lower: all_repo_frameworks.add("NestJS")
                elif "fastify"          in deps_lower: all_repo_frameworks.add("Fastify")
                elif "express"          in deps_lower: all_repo_frameworks.add("Express.js")
                else:                                  all_repo_frameworks.add("Node.js")
            except Exception:
                pass

        if "composer.json" in root_files:
            comp = _get_raw(root_files["composer.json"]).lower()
            if "laravel"   in comp: all_repo_frameworks.add("Laravel")
            elif "symfony" in comp: all_repo_frameworks.add("Symfony")
            else:                   all_repo_frameworks.add("PHP")

        if "gemfile" in root_files:
            gem = _get_raw(root_files["gemfile"]).lower()
            all_repo_frameworks.add("Ruby on Rails" if "rails" in gem else "Ruby")

        csproj = next((f for f in root_files if f.endswith(".csproj")), None)
        if csproj:
            cs = _get_raw(root_files[csproj]).lower()
            if "maui"         in cs: all_repo_frameworks.add(".NET MAUI")
            elif "aspnetcore" in cs: all_repo_frameworks.add("ASP.NET Core")
            else:                    all_repo_frameworks.add(".NET")

        _LANG_TO_CONFIG = {
            "php":        "composer.json",
            "java":       "pom.xml",
            "kotlin":     "pom.xml",
            "python":     "requirements.txt",
            "dart":       "pubspec.yaml",
            "typescript": "package.json",
            "javascript": "package.json",
        }

        repo_lang = (repo.get("language") or "").lower()
        target_config = _LANG_TO_CONFIG.get(repo_lang)

        if target_config and root_files:
            _SKIP = {".github","node_modules","vendor","test","tests",
                     "docs","assets","dist","build","target",".git",".idea"}
            project_folders = [f for f in root_folders if f not in _SKIP][:3]

            for folder in project_folders:
                sub = _get(
                    f"https://api.github.com/repos/{username}/{repo['name']}"
                    f"/contents/{folder}",
                    timeout=5,
                )
                if not sub or not isinstance(sub, list):
                    continue
                sub_files = {
                    item["name"].lower(): item.get("download_url")
                    for item in sub
                    if isinstance(item, dict) and item.get("type") == "file"
                }
                if target_config not in sub_files:
                    continue

                frameworks_before = len(all_repo_frameworks)

                if target_config == "composer.json":
                    comp = _get_raw(sub_files["composer.json"]).lower()
                    if "laravel"   in comp: all_repo_frameworks.add("Laravel")
                    elif "symfony" in comp: all_repo_frameworks.add("Symfony")
                    else:                   all_repo_frameworks.add("PHP")
                elif target_config == "pom.xml":
                    pom = _get_raw(sub_files["pom.xml"]).lower()
                    if "spring-boot" in pom: all_repo_frameworks.add("Spring Boot")
                    elif "quarkus"   in pom: all_repo_frameworks.add("Quarkus")
                    else:                    all_repo_frameworks.add("Java/Maven")
                elif target_config == "requirements.txt":
                    req = _get_raw(sub_files["requirements.txt"]).lower()
                    if "django"    in req: all_repo_frameworks.add("Django")
                    elif "fastapi" in req: all_repo_frameworks.add("FastAPI")
                    elif "flask"   in req: all_repo_frameworks.add("Flask")
                    else:                  all_repo_frameworks.add("Python")
                elif target_config == "pubspec.yaml":
                    all_repo_frameworks.add("Flutter")
                elif target_config == "package.json":
                    try:
                        import json as _json3
                        pkg = _json3.loads(_get_raw(sub_files["package.json"]))
                        deps = set()
                        deps.update(pkg.get("dependencies", {}).keys())
                        deps.update(pkg.get("devDependencies", {}).keys())
                        dep_names = {d.lower() for d in deps}
                        if "@angular/core"  in dep_names: all_repo_frameworks.add("Angular")
                        elif "react-native" in dep_names: all_repo_frameworks.add("React Native")
                        elif "react"        in dep_names:
                            all_repo_frameworks.add("Next.js" if "next" in dep_names else "React")
                        elif "vue"          in dep_names: all_repo_frameworks.add("Vue.js")
                        elif "@nestjs/core" in dep_names: all_repo_frameworks.add("NestJS")
                        elif "express"      in dep_names: all_repo_frameworks.add("Express.js")
                    except Exception:
                        pass

                if len(all_repo_frameworks) > frameworks_before:
                    break

    logger.info(f"[GitHub] All-repo framework scan complete: {all_repo_frameworks}")

    # ── Framework → implied languages mapping ─────────────────────────────────
    _FRAMEWORK_IMPLIES: dict[str, set[str]] = {
        "Spring Boot":          {"java"},
        "Quarkus":              {"java"},
        "Micronaut":            {"java"},
        "Java/Maven":           {"java"},
        "Angular":              {"typescript", "javascript", "html", "css", "scss", "sass"},
        "React":                {"typescript", "javascript", "html", "css", "scss"},
        "Next.js":              {"typescript", "javascript", "html", "css"},
        "Remix":                {"typescript", "javascript", "html", "css"},
        "Vue.js":               {"typescript", "javascript", "html", "css"},
        "Nuxt.js":              {"typescript", "javascript", "html", "css"},
        "Svelte":               {"typescript", "javascript", "html", "css"},
        "SvelteKit":            {"typescript", "javascript", "html", "css"},
        "Astro":                {"typescript", "javascript", "html", "css"},
        "NestJS":               {"typescript", "javascript"},
        "Fastify":              {"typescript", "javascript"},
        "Express.js":           {"javascript"},
        "Node.js":              {"javascript"},
        "React Native":         {"typescript", "javascript"},
        "Flutter":              {"dart", "c++", "cmake", "c", "swift",
                                 "objective-c", "kotlin", "html", "css"},
        "Android":              {"kotlin", "java"},
        "Jetpack Compose":      {"kotlin"},
        "iOS":                  {"swift", "objective-c"},
        "Kotlin Multiplatform": {"kotlin"},
        ".NET MAUI":            {"c#", "xaml"},
        "Django":               {"python"},
        "Flask":                {"python"},
        "FastAPI":              {"python"},
        "Streamlit":            {"python"},
        "Laravel":              {"php"},
        "Symfony":              {"php"},
        "Ruby on Rails":        {"ruby"},
        "ASP.NET Core":         {"c#"},
        ".NET":                 {"c#"},
        "Gin":                  {"go"},
        "Echo":                 {"go"},
        "Fiber":                {"go"},
        "Actix":                {"rust"},
        "Axum":                 {"rust"},
    }

    def _compute_technologies(frameworks: List[str], all_langs: List[str]) -> List[str]:
        implied = {
            lang
            for fw in frameworks
            for lang in _FRAMEWORK_IMPLIES.get(fw, set())
        }
        remaining_langs = [
            lang for lang in all_langs
            if lang.lower() not in implied
        ]
        return frameworks + remaining_langs

    for repo in scored_repos:
        repo["technologies"] = _compute_technologies(
            repo.get("frameworks", []),
            repo.get("all_languages", []),
        )

    # ── Top languages ─────────────────────────────────────────────────────────
    _NOISE_LANGS = {
        "cmake", "makefile", "meson", "batchfile", "dockerfile",
        "hcl", "nix", "go template", "smarty", "mustache",
        "blade", "twig", "jinja", "handlebars", "ejs", "erb", "liquid",
        "scss", "sass", "less",
        "xslt", "plpgsql",
    }
    lang_count_all: dict[str, int] = {}
    for repo in own_repos:
        lang = repo.get("language")
        if lang and lang.lower() not in _NOISE_LANGS:
            lang_count_all[lang] = lang_count_all.get(lang, 0) + 1
    top_languages = sorted(
        lang_count_all, key=lambda k: lang_count_all[k], reverse=True
    )[:8]

    total_stars  = sum(r.get("stargazers_count", 0) for r in own_repos)
    push_dates   = [r.get("pushed_at", "") for r in repos if r.get("pushed_at")]
    last_active  = max(push_dates)[:10] if push_dates else None
    real_repos   = [r for r in scored_repos if r["is_real"]]

    # ── Final github_score ────────────────────────────────────────────────────
    # own_repos count already passed the early-exit above.
    # This call only decides STRONG / MODERATE / NO_PUBLIC_WORK (all failed quality).
    github_score = _compute_github_score(
        real_repos_count=len(real_repos),
        own_repos_count=len(own_repos),
    )

    # All repos failed quality check → neutral, no penalty
    if github_score == "NO_PUBLIC_WORK":
        logger.info(
            f"[GitHub] {username} has {len(own_repos)} own repo(s) but "
            f"real_repos=0 — all failed quality check. "
            f"Returning NO_PUBLIC_WORK (neutral, no penalty)."
        )
        return _neutral_profile(
            username, profile, account_age_days,
            own_repos_count=len(own_repos),
            forked_repos_count=len(forked_repos),
            github_score="NO_PUBLIC_WORK",
        )

    top_scored = sorted(
        [r for r in scored_repos if not r["is_fork"]],
        key=lambda r: (
            r["commit_count"],
            r["branch_count"],
            r["days_of_activity"],
            r["size_kb"],
        ),
        reverse=True
    )[:5]

    # ── All technologies ──────────────────────────────────────────────────────
    _TECH_NOISE = {
        "blade", "shell", "scss", "sass", "less", "twig",
        "jinja", "handlebars", "ejs", "erb", "liquid",
        "cmake", "makefile", "batchfile", "dockerfile",
        "go template", "xslt", "plpgsql", "mustache",
    }
    all_frameworks_found = {
        fw
        for repo in scored_repos
        for fw in repo.get("frameworks", [])
        if fw
    } | all_repo_frameworks

    _all_implied = {
        lang
        for fw in all_frameworks_found
        for lang in _FRAMEWORK_IMPLIES.get(fw, set())
    }

    _raw_techs = {
        tech
        for repo in scored_repos
        for tech in repo.get("technologies", [])
        if tech and tech.lower() not in _TECH_NOISE
    } | {
        fw for fw in all_repo_frameworks
        if fw and fw.lower() not in _TECH_NOISE
    }

    all_technologies = list({
        tech for tech in _raw_techs
        if tech.lower() not in _all_implied
    })

    # ── CV skills verification helpers (consumed by nlp_parser) ──────────────
    _LANG_IMPLIES_FW: dict[str, set[str]] = {
        "java":       {"spring boot", "java/maven", "android", "quarkus", "micronaut"},
        "kotlin":     {"android", "jetpack compose", "kotlin multiplatform"},
        "dart":       {"flutter"},
        "swift":      {"ios"},
        "python":     {"django", "flask", "fastapi", "streamlit"},
        "php":        {"laravel", "symfony"},
        "ruby":       {"ruby on rails"},
        "go":         {"gin", "echo", "fiber"},
        "rust":       {"actix", "axum"},
        "c#":         {"asp.net core", ".net", ".net maui"},
        "typescript": {"angular", "react", "next.js", "nestjs", "vue.js",
                       "svelte", "sveltekit", "remix", "nuxt.js"},
        "javascript": {"react", "express.js", "node.js", "vue.js"},
        "c++":        {"flutter"},
    }

    confirmed_lower = (
        {t.lower() for t in all_technologies}
        | {f.lower() for f in all_repo_frameworks}
        | _all_implied
    )
    top_langs_lower = {l.lower() for l in top_languages}

    # ── Profile-level activity aggregation ───────────────────────────────────
    top_repos_detail = [r for r in scored_repos if r["name"] in repos_to_check_names]
    consistent_repos = [r["name"] for r in top_repos_detail if r["commit_activity"]["is_consistent"]]
    total_weight     = sum(r["complexity_score"] for r in top_repos_detail)
    avg_ownership    = (
        sum(r["ownership_ratio"] * r["complexity_score"] for r in top_repos_detail) / total_weight
        if total_weight > 0 else 0.0
    )

    result = {
        "username":              username,
        "account_url":           f"https://github.com/{username}",
        "name":                  profile.get("name"),
        "bio":                   profile.get("bio"),
        "location":              profile.get("location"),
        "public_repos_count":    profile.get("public_repos", 0),
        "own_repos_count":       len(own_repos),
        "forked_repos_count":    len(forked_repos),
        "account_age_days":      account_age_days,
        "followers":             profile.get("followers", 0),
        "last_active":           last_active,
        "all_technologies":      all_technologies,
        "all_repo_frameworks":   sorted(all_repo_frameworks),
        "total_stars":           total_stars,
        "real_repos_count":      len(real_repos),
        "scored_repos":          top_scored,
        "github_score":          github_score,
        "verification_skipped":  False,
        "collaboration":         collaboration,
        "consistent_repos":      consistent_repos,
        "recently_active_repos": sum(1 for r in top_repos_detail
                                     if r["commit_activity"].get("recently_active", False)),
        "avg_ownership_ratio":   round(avg_ownership, 2),
        # Internal helpers for nlp_parser skill verification
        "confirmed_lower":       list(confirmed_lower),
        "top_langs_lower":       list(top_langs_lower),
        "lang_implies_fw":       {k: list(v) for k, v in _LANG_IMPLIES_FW.items()},
    }

    logger.info(
        f"[GitHub] {username}: score={github_score}, "
        f"real_repos={len(real_repos)}/{len(own_repos)}, "
        f"stars={total_stars}, "
        f"collaboration={collaboration['has_collaboration']}, "
        f"consistent_repos={consistent_repos}, "
        f"avg_ownership={avg_ownership:.0%}, "
        f"verification_skipped=False"
    )
    return result