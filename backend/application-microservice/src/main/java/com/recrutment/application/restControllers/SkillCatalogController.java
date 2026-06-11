package com.recrutment.application.restControllers;

import com.recrutment.application.dto.NearestSkillRequest;
import com.recrutment.application.dto.NearestSkillResponse;
import com.recrutment.application.dto.SkillCatalogDto;
import com.recrutment.application.dto.SkillIntelDto;
import com.recrutment.application.dto.SkillResolveRequest;
import com.recrutment.application.dto.SkillResolveResponse;
import com.recrutment.application.entities.SkillCatalogEntry;
import com.recrutment.application.repos.SkillCatalogRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Manages the skill catalog — the universe of skills candidates can select on
 * their Preferences page. Has three responsibilities:
 *
 *   1. **Manual entries** — recruiters add skills they anticipate needing,
 *      even before posting a job. Stored with source=MANUAL.
 *   2. **Tombstones** — when a recruiter removes a skill (typo cleanup,
 *      retiring a tech), we save a row with removed=true. The candidate-side
 *      merging code uses these tombstones to hide skills regardless of whether
 *      they're still mentioned in jobs (so auto-extraction can't resurrect a
 *      cleaned-up typo).
 *   3. **Restoration** — undo a deletion. Sets removed back to false.
 *
 * Auto-extracted skills (from job requirements) are NOT stored here — they
 * live implicitly in the jobs themselves and are surfaced via the
 * cv-parser-service. The frontend merges both sources. This controller only
 * owns the manual layer.
 *
 * Access: any authenticated user can GET (candidate Preferences uses it).
 * Mutations are gated on the frontend by role — there's no server-side
 * @PreAuthorize because this codebase doesn't use it elsewhere either.
 */
@RestController
@RequestMapping("/api/applications/skill-catalog")
@Slf4j
public class SkillCatalogController {

    private final SkillCatalogRepo repo;
    /**
     * Multi-vector retrieval store for SOFT skill phrases (canonical + each
     * synonym). The /match-soft-skill Layer 1 query joins it for max-score
     * aggregation per skill. See SkillSynonymEmbedding javadoc for the
     * empirical motivation.
     */
    private final com.recrutment.application.repos.SkillSynonymEmbeddingRepo phraseRepo;
    /** Non-load-balanced RestTemplate for talking to cv-parser-service. The
     *  default @LoadBalanced templates resolve hostnames as Eureka service IDs;
     *  cv-parser-service isn't in Eureka so they throw "No instances
     *  available". This bean uses the raw docker-network hostname directly.
     *
     *  NOTE: explicit constructor (no @RequiredArgsConstructor) so the
     *  @Qualifier annotation actually reaches the constructor parameter -
     *  Lombok doesn't propagate @Qualifier from field to constructor arg by
     *  default, which silently wires the load-balanced bean and breaks the
     *  cv-parser passthrough.
     */
    private final RestTemplate plainRestTemplate;

    @Value("${cv.parser.url:http://cv-parser-service:8085}")
    private String cvParserUrl;

    public SkillCatalogController(SkillCatalogRepo repo,
                                  com.recrutment.application.repos.SkillSynonymEmbeddingRepo phraseRepo,
                                  @Qualifier("directRestTemplate") RestTemplate plainRestTemplate) {
        this.repo = repo;
        this.phraseRepo = phraseRepo;
        this.plainRestTemplate = plainRestTemplate;
    }

    /**
     * Returns manual catalog entries.
     *
     * @param includeRemoved when false (default), tombstoned rows are filtered
     *                       out — candidate Preferences uses this mode. When
     *                       true, all rows including tombstones are returned —
     *                       the recruiter admin page uses this so it can show
     *                       a "Removed skills" section with a Restore button.
     */
    @GetMapping
    public ResponseEntity<List<SkillCatalogDto>> list(
            @RequestParam(name = "includeRemoved", defaultValue = "false") boolean includeRemoved) {

        List<SkillCatalogEntry> rows = includeRemoved
                ? repo.findAllByOrderByFirstSeenAtDesc()
                : repo.findByRemovedFalseOrderByFirstSeenAtDesc();

        // currentDemandCount is null at this layer — populated by the frontend
        // when it merges this list with the auto-extracted skills from
        // cv-parser-service (which knows how many PUBLISHED jobs mention each).
        return ResponseEntity.ok(rows.stream().map(e -> new SkillCatalogDto(
                e.getName(),
                e.getDisplayName() != null ? e.getDisplayName() : e.getName(),
                e.getFirstSeenAt(),
                null,
                e.getSource(),
                e.isRemoved(),
                e.getType() != null ? e.getType() : "HARD",
                e.getDomains() != null ? e.getDomains() : new ArrayList<>(),
                e.getSynonyms() != null ? e.getSynonyms() : new ArrayList<>()
        )).toList());
    }

    /**
     * Browser-facing passthrough to cv-parser-service /extract-catalog.
     *
     * cv-parser-service is a Python/FastAPI sidecar that doesn't register with
     * Eureka and doesn't expose its port to the host, so the gateway can't
     * `lb://` it. Rather than carve out a special direct-hostname route in the
     * gateway (breaking the "everything is lb://" pattern), we proxy the call
     * here: the frontend hits /api/applications/skill-catalog/extract through
     * the gateway like any other call, this controller forwards the same
     * payload to cv-parser-service over the Docker private network, and the
     * response is streamed back unchanged. Same security rules as the rest of
     * /api/applications/skill-catalog/** (authenticated read), no new gateway
     * routes, no exposed ports.
     */
    @PostMapping("/extract")
    public ResponseEntity<Map<String, Object>> extractCatalog(@RequestBody Map<String, Object> jobsPayload) {
        String url = cvParserUrl + "/api/cv-parser/extract-catalog";
        try {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = plainRestTemplate.postForEntity(url, jobsPayload, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody() != null ? response.getBody() : Map.of();
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("[SkillCatalogController] extract-catalog passthrough failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "cv-parser-service unreachable: " + e.getMessage());
        }
    }

    /**
     * Ask Qwen (via cv-parser-service) for candidate synonyms for a soft-skill
     * canonical name. The admin UI calls this when the recruiter clicks
     * "Suggest synonyms" on the add form; the recruiter then accepts / edits /
     * rejects them before saving. We do NOT auto-attach the suggestions —
     * the recruiter is always the final author of what enters the catalog.
     *
     * Returns: { "synonyms": ["team lead", "people management", ...] }
     * Empty list on LLM hiccup so the UI can still save without synonyms.
     */
    @PostMapping("/suggest-synonyms")
    public ResponseEntity<Map<String, Object>> suggestSynonyms(@RequestBody Map<String, Object> body) {
        Object rawName = body == null ? null : body.get("name");
        if (!(rawName instanceof String name) || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        String type = body.get("type") instanceof String t ? t : "SOFT";

        String url = cvParserUrl + "/api/cv-parser/skills/suggest-synonyms";
        try {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = plainRestTemplate.postForEntity(
                    url, Map.of("name", name.trim(), "type", type), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = response.getBody() != null
                    ? response.getBody() : Map.of("synonyms", List.of());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.warn("[suggest-synonyms] LLM call failed for '{}': {}", name, e.getMessage());
            return ResponseEntity.ok(Map.of("synonyms", List.of()));
        }
    }

    /**
     * Normalize a synonyms payload from JSON into the storage shape:
     * trimmed, lowercased, whitespace-collapsed, deduped, no empties. Used by
     * POST and PATCH so the storage invariant is set in ONE place — the
     * /match-soft-skill short-circuit relies on it.
     */
    @SuppressWarnings("unchecked")
    private static List<String> parseSynonymsList(Object raw) {
        if (raw == null) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        java.util.function.Consumer<String> add = s -> {
            if (s == null) return;
            String norm = s.trim().toLowerCase().replaceAll("\\s+", " ");
            if (!norm.isEmpty() && seen.add(norm)) result.add(norm);
        };
        if (raw instanceof List<?> list) {
            for (Object o : list) if (o instanceof String s) add.accept(s);
        } else if (raw instanceof String s) {
            for (String part : s.split(",")) add.accept(part);
        }
        return result;
    }

    /**
     * Adds a skill manually. Idempotent: if a row already exists (active OR
     * removed) for the same name, we just clear the `removed` flag and refresh
     * the timestamp instead of erroring — this is what "Restore" hits too.
     */
    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<SkillCatalogDto> add(@RequestBody Map<String, Object> body) {
        Object rawName = body.get("name");
        String displayName = rawName != null ? rawName.toString().trim() : "";
        if (displayName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill name is required.");
        }
        String key = displayName.toLowerCase();

        // Type — HARD or SOFT, defaults to HARD when unspecified (caller is
        // implicitly adding a technical skill).
        String type = "HARD";
        if (body.get("type") instanceof String t && !t.isBlank()) {
            String normalized = t.trim().toUpperCase();
            if (normalized.equals("HARD") || normalized.equals("SOFT")) type = normalized;
        }

        // Domains — recruiter-chosen list. Empty/null means universal.
        List<String> domains = new ArrayList<>();
        Object rawDomains = body.get("domains");
        if (rawDomains instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) domains.add(s.trim());
            }
        } else if (rawDomains instanceof String s && !s.isBlank()) {
            for (String d : s.split(",")) if (!d.isBlank()) domains.add(d.trim());
        }

        // Synonyms — recruiter-curated alternative phrasings (primary use:
        // SOFT skills). Normalized to lowercase + collapsed whitespace at
        // write time so /match-soft-skill can do a cheap case-insensitive
        // exact lookup without per-request normalization.
        List<String> synonyms = parseSynonymsList(body.get("synonyms"));

        Optional<SkillCatalogEntry> existing = repo.findById(key);
        SkillCatalogEntry entry = existing.orElseGet(() -> SkillCatalogEntry.builder()
                .name(key)
                .displayName(displayName)
                .firstSeenAt(Instant.now())
                .source("MANUAL")
                .removed(false)
                .type("HARD")
                .domains(new ArrayList<>())
                .build());

        if (entry.isRemoved()) {
            entry.setRemoved(false);
            entry.setRemovedAt(null);
        }
        if (entry.getDisplayName() == null || !entry.getDisplayName().equals(displayName)) {
            entry.setDisplayName(displayName);
        }
        // Type explicitly set on every save — recruiter may be flipping
        // an existing skill from HARD to SOFT or vice versa.
        entry.setType(type);
        // Domains: union with whatever was there (so auto-extraction tags
        // accumulate over time). Use LinkedHashSet to keep insertion order
        // stable and deduplicate.
        Set<String> merged = new LinkedHashSet<>(entry.getDomains() == null ? new ArrayList<>() : entry.getDomains());
        merged.addAll(domains);
        entry.setDomains(new ArrayList<>(merged));

        // Synonyms: union with what was there (so a recruiter who edits the
        // skill later doesn't accidentally wipe prior synonyms by not
        // re-supplying them). PATCH /{name} below is the explicit REPLACE
        // semantics when the recruiter wants to drop synonyms.
        if (!synonyms.isEmpty()) {
            Set<String> mergedSyn = new LinkedHashSet<>(
                    entry.getSynonyms() == null ? new ArrayList<>() : entry.getSynonyms());
            mergedSyn.addAll(synonyms);
            entry.setSynonyms(new ArrayList<>(mergedSyn));
        }

        SkillCatalogEntry saved = repo.save(entry);

        // Eagerly embed the new (or restored) row's canonical name so the
        // matcher's Layer 1 (pgvector cosine NN) can find it immediately.
        // Without this, the row exists but stays invisible to embedding-
        // based search until /backfill-embeddings is called manually -
        // surfaced during integration testing as "Layer 1 returns 204 for
        // everything because most rows have NULL embeddings".
        //
        // Fails soft: an Ollama hiccup leaves the row with a NULL embedding
        // (recoverable via /backfill-embeddings later). The catalog row
        // itself is still safe to save, so we don't roll back on embed
        // failure - it's a degraded-but-still-useful state, not a corrupt
        // one. Logged so it's visible in the application-microservice logs.
        if (saved.getEmbedding() == null) {
            try {
                List<Float> vec = fetchEmbeddingFromCvParser(saved.getName());
                if (vec != null && vec.size() == 768) {
                    repo.updateEmbedding(
                            saved.getName(),
                            toVectorLiteral(vec),
                            "nomic-embed-text",
                            Instant.now());
                    log.info("[skill-catalog/add] embedded '{}' on insert", saved.getName());
                } else {
                    log.warn("[skill-catalog/add] embedder unavailable for '{}', " +
                            "row saved without vector (run /backfill-embeddings to fix)",
                            saved.getName());
                }
            } catch (Exception e) {
                log.warn("[skill-catalog/add] embed-on-insert failed for '{}': {}",
                        saved.getName(), e.getMessage());
            }
        }

        // Multi-vector retrieval store for SOFT skills. The canonical AND
        // each synonym get their own embedding row in skill_synonym_embedding
        // so the /match-soft-skill Layer 1 query can pick the best-scoring
        // phrase per skill (MAX aggregation). HARD skills skip this - they
        // have a different cascade (abbreviation, Levenshtein, LLM
        // tiebreaker) that handles paraphrase variation without needing
        // multi-vector retrieval.
        if ("SOFT".equals(saved.getType())) {
            indexPhrasesForSkill(
                    saved.getName(),
                    saved.getDisplayName(),
                    saved.getSynonyms() != null ? saved.getSynonyms() : new ArrayList<>(),
                    /* replaceExisting= */ false);
        }

        return ResponseEntity.ok(new SkillCatalogDto(
                saved.getName(),
                saved.getDisplayName(),
                saved.getFirstSeenAt(),
                null,
                saved.getSource(),
                saved.isRemoved(),
                saved.getType(),
                saved.getDomains(),
                saved.getSynonyms() != null ? saved.getSynonyms() : new ArrayList<>()
        ));
    }

    /**
     * Soft-delete (tombstone) a skill. Used for typo cleanup and for retiring
     * tech that no longer applies. The row stays in the DB so:
     *   • Auto-extraction can't resurrect the bad name from new job postings
     *   • An audit trail of "what was removed" is preserved
     *   • Restore is one click away
     *
     * If the skill wasn't already in the catalog (e.g., the recruiter is
     * tombstoning an extracted-only skill they want to block from re-appearing),
     * we create a row with source=EXTRACTED and removed=true to act as the
     * blocklist marker.
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Void> remove(@PathVariable("name") String name) {
        String key = name.trim().toLowerCase();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill name is required.");
        }

        SkillCatalogEntry entry = repo.findById(key).orElseGet(() -> SkillCatalogEntry.builder()
                .name(key)
                .displayName(name.trim())
                .firstSeenAt(Instant.now())
                .source("EXTRACTED")  // we're blocking a previously-extracted skill
                .build());
        entry.setRemoved(true);
        entry.setRemovedAt(Instant.now());
        repo.save(entry);
        return ResponseEntity.noContent().build();
    }

    /**
     * Replace the domain tags on an existing skill. Used by the admin page's
     * Edit dialog so a recruiter can re-classify a skill (auto-extracted or
     * manual) without removing and re-adding it.
     *
     * Semantics differ from the POST endpoint: POST UNIONS new domains onto
     * the existing list, PATCH REPLACES the list entirely. So PATCH is the
     * right call when the recruiter wants to drop a domain tag too.
     *
     * If the skill doesn't exist yet (because it's purely auto-extracted —
     * no row in this table), we create a row with source=EXTRACTED so the
     * override sticks across catalog refreshes.
     */
    @PatchMapping("/{name}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<SkillCatalogDto> updateDomains(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> body) {

        String key = name.trim().toLowerCase();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill name is required.");
        }

        // PATCH lets the recruiter touch domains and/or synonyms. Only the
        // fields actually present in the body are updated — that way the UI
        // can send `{"synonyms": [...]}` without nuking the existing domains
        // by omitting them. REPLACE semantics within each field (recruiter
        // is the source of truth here).
        boolean hasDomains  = body.containsKey("domains");
        boolean hasSynonyms = body.containsKey("synonyms");

        List<String> domains = new ArrayList<>();
        if (hasDomains) {
            Object rawDomains = body.get("domains");
            if (rawDomains instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof String s && !s.isBlank()) domains.add(s.trim());
                }
            }
        }

        List<String> synonyms = hasSynonyms ? parseSynonymsList(body.get("synonyms")) : List.of();

        SkillCatalogEntry entry = repo.findById(key).orElseGet(() -> SkillCatalogEntry.builder()
                .name(key)
                .displayName(name.trim())
                .firstSeenAt(Instant.now())
                .source("EXTRACTED")  // skill exists in jobs but not yet in this table
                .build());
        if (hasDomains)  entry.setDomains(domains);     // REPLACE
        if (hasSynonyms) entry.setSynonyms(synonyms);   // REPLACE
        SkillCatalogEntry saved = repo.save(entry);

        // If the synonyms list changed on a SOFT row, replace the multi-
        // vector store entries for this skill: drop old phrase rows, embed
        // canonical + every new synonym, insert fresh. The Layer 1 max-
        // score query then immediately sees the new set.
        if (hasSynonyms && "SOFT".equals(saved.getType())) {
            indexPhrasesForSkill(
                    saved.getName(),
                    saved.getDisplayName(),
                    saved.getSynonyms() != null ? saved.getSynonyms() : new ArrayList<>(),
                    /* replaceExisting= */ true);
        }

        return ResponseEntity.ok(new SkillCatalogDto(
                saved.getName(),
                saved.getDisplayName(),
                saved.getFirstSeenAt(),
                null,
                saved.getSource(),
                saved.isRemoved(),
                saved.getType() != null ? saved.getType() : "HARD",
                saved.getDomains() != null ? saved.getDomains() : new ArrayList<>(),
                saved.getSynonyms() != null ? saved.getSynonyms() : new ArrayList<>()
        ));
    }

    /**
     * Convenience — same as POST but more explicit. Restores a tombstoned skill.
     */
    @PostMapping("/{name}/restore")
    public ResponseEntity<Void> restore(@PathVariable("name") String name) {
        String key = name.trim().toLowerCase();
        repo.findById(key).ifPresent(entry -> {
            entry.setRemoved(false);
            entry.setRemovedAt(null);
            repo.save(entry);
        });
        return ResponseEntity.noContent().build();
    }

    // ─── Skill intel endpoints (embedding + volatility + implies) ────────────
    //
    // Consumed by the cv-parser-service matcher. The catalog is the canonical
    // store for everything-about-a-skill so the matcher reads from one place
    // and writes back as it computes new facts.

    /**
     * Auto-merge above this cosine similarity - silently collapse, no LLM call.
     * Empirically calibrated against a labeled dataset of 139 skill pairs: SAME
     * pairs cluster around median 0.72, p90 0.88; RELATED max 0.81. 0.93 catches
     * the high-confidence merges while staying above the RELATED max.
     */
    private static final double AUTO_MERGE_THRESHOLD = 0.93;
    /**
     * Gray band [LLM_TIEBREAK_THRESHOLD, AUTO_MERGE_THRESHOLD): ask qwen2.5:7b
     * "are these the same skill?". The LLM is much better at disambiguating
     * borderline pairs (spring vs spring boot, react vs react native) than any
     * fixed threshold. Cached per pair so repeat resolves are free.
     *
     * Below this floor, we don't bother asking - embedding is too distant for
     * the LLM to plausibly say "same".
     */
    private static final double LLM_TIEBREAK_THRESHOLD = 0.75;
    /** How many nearest neighbours to return on each resolve call. */
    private static final int    SUGGESTION_LIMIT     = 5;

    /**
     * Minimum cosine similarity required for the CV-soft-skill match endpoint
     * to return a hit. Below this we return null so the candidate doesn't get
     * credited for a soft skill they didn't actually express.
     *
     * Empirically calibrated for nomic-embed-text on short soft-skill text:
     *   "Strong communication" vs catalog "Communication"  ~ 0.80-0.85
     *   "Great team player"    vs catalog "Teamwork"       ~ 0.75-0.80
     *   "Built ML models"      vs catalog "Communication"  ~ 0.40
     *   "Cooking"              vs catalog anything         ~ 0.30
     *
     * 0.70 is the sweet spot: high enough to be confident, low enough to
     * catch real variant phrasings recruiters wouldn't anticipate.
     */
    private static final double SOFT_MATCH_THRESHOLD = 0.70;

    /**
     * Common technical-skill abbreviations that the embedding model cannot reliably
     * disambiguate (k8s vs kubernetes embeds at ~0.48 cosine - way below any
     * threshold despite being obviously the same skill to a human).
     *
     * Map: short form -> canonical full name. The canonical form must either
     * already exist in the catalog or will be silently created on first use.
     *
     * Curated list - add entries here when you observe duplicates accumulating
     * because of an abbreviation we missed.
     */
    private static final java.util.Map<String, String> ABBREVIATIONS = java.util.Map.ofEntries(
            // Cloud / infra
            java.util.Map.entry("k8s",   "kubernetes"),
            java.util.Map.entry("aws",   "amazon web services"),
            java.util.Map.entry("gcp",   "google cloud platform"),
            java.util.Map.entry("azure", "microsoft azure"),
            java.util.Map.entry("ec2",   "amazon ec2"),
            java.util.Map.entry("s3",    "amazon s3"),
            java.util.Map.entry("rds",   "amazon rds"),
            java.util.Map.entry("ecs",   "amazon ecs"),
            java.util.Map.entry("eks",   "amazon eks"),
            java.util.Map.entry("ci/cd", "continuous integration"),
            // Languages
            java.util.Map.entry("js",    "javascript"),
            java.util.Map.entry("ts",    "typescript"),
            java.util.Map.entry("py",    "python"),
            java.util.Map.entry("go",    "golang"),
            java.util.Map.entry("rb",    "ruby"),
            java.util.Map.entry("cpp",   "c++"),
            java.util.Map.entry("csharp","c#"),
            java.util.Map.entry("fsharp","f#"),
            // AI / ML / data
            java.util.Map.entry("ml",    "machine learning"),
            java.util.Map.entry("dl",    "deep learning"),
            java.util.Map.entry("ai",    "artificial intelligence"),
            java.util.Map.entry("nlp",   "natural language processing"),
            java.util.Map.entry("cv",    "computer vision"),
            java.util.Map.entry("rl",    "reinforcement learning"),
            java.util.Map.entry("genai", "generative ai"),
            java.util.Map.entry("llm",   "large language models"),
            // Concepts
            java.util.Map.entry("oop",   "object oriented programming"),
            java.util.Map.entry("fp",    "functional programming"),
            java.util.Map.entry("tdd",   "test driven development"),
            java.util.Map.entry("bdd",   "behavior driven development"),
            java.util.Map.entry("ddd",   "domain driven design"),
            java.util.Map.entry("solid", "solid principles"),
            java.util.Map.entry("orm",   "object relational mapping"),
            java.util.Map.entry("mvc",   "model view controller"),
            java.util.Map.entry("api",   "rest api"),
            // Tooling
            java.util.Map.entry("npm",   "node package manager"),
            java.util.Map.entry("yarn",  "yarn package manager"),
            java.util.Map.entry("vscode","visual studio code"),
            java.util.Map.entry("vs code","visual studio code"),
            java.util.Map.entry("idea",  "intellij idea"),
            java.util.Map.entry("git hub","github"),
            java.util.Map.entry("git lab","gitlab"),
            java.util.Map.entry("gh actions","github actions"),
            // Frameworks / libraries - CONSISTENT RULE: canonical is the bare name.
            // display_name keeps the .js form ("Node.js") for the UI.
            // Every typing variant maps to the same bare canonical.
            java.util.Map.entry("sklearn",   "scikit-learn"),
            java.util.Map.entry("tf",        "tensorflow"),
            java.util.Map.entry("pt",        "pytorch"),
            // React family
            java.util.Map.entry("react.js",  "react"),
            java.util.Map.entry("reactjs",   "react"),
            // Vue family
            java.util.Map.entry("vue.js",    "vue"),
            java.util.Map.entry("vuejs",     "vue"),
            // Node family - was inconsistent, fixed: bare canonical
            java.util.Map.entry("node.js",   "node"),
            java.util.Map.entry("nodejs",    "node"),
            // Next family - was inconsistent, fixed
            java.util.Map.entry("next.js",   "next"),
            java.util.Map.entry("nextjs",    "next"),
            // Nuxt family - was inconsistent, fixed
            java.util.Map.entry("nuxt.js",   "nuxt"),
            java.util.Map.entry("nuxtjs",    "nuxt"),
            // Express family
            java.util.Map.entry("express.js","express"),
            java.util.Map.entry("expressjs", "express"),
            // PostgreSQL family
            java.util.Map.entry("postgres",  "postgresql"),
            java.util.Map.entry("postgre",   "postgresql")
    );

    /**
     * Last-resort fallback for proper display naming when the LLM is unavailable.
     *
     * The PRIMARY mechanism for getting a proper display name is the LLM call
     * inside classifySkillWithLlm() - the model knows every framework, library,
     * acronym and convention including ones invented after this code was written.
     * That's vastly more robust than any hardcoded list.
     *
     * This map only kicks in when the LLM call fails (Ollama down, network issue)
     * AND the title-case default would produce something obviously wrong. Keep it
     * small - it's a safety net, not a primary source of truth.
     */
    private static final java.util.Map<String, String> SPECIAL_DISPLAY_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("javascript",   "JavaScript"),
            java.util.Map.entry("typescript",   "TypeScript"),
            java.util.Map.entry("ios",          "iOS"),
            java.util.Map.entry("macos",        "macOS"),
            java.util.Map.entry("c++",          "C++"),
            java.util.Map.entry("c#",           "C#"),
            java.util.Map.entry("scikit-learn", "scikit-learn"),
            java.util.Map.entry("postgresql",   "PostgreSQL"),
            java.util.Map.entry("mysql",        "MySQL"),
            java.util.Map.entry("mongodb",      "MongoDB"),
            java.util.Map.entry("graphql",      "GraphQL"),
            java.util.Map.entry("html",         "HTML"),
            java.util.Map.entry("css",          "CSS"),
            java.util.Map.entry("sql",          "SQL"),
            java.util.Map.entry("nosql",        "NoSQL"),
            java.util.Map.entry("api",          "API"),
            java.util.Map.entry("rest api",     "REST API"),
            java.util.Map.entry("aws",          "AWS"),
            java.util.Map.entry("gcp",          "GCP"),
            java.util.Map.entry("ci/cd",        "CI/CD"),
            java.util.Map.entry(".net",         ".NET")
    );

    /**
     * In-memory cache of pair verdicts: key = "a||b" (sorted), value = same?.
     * Keeps the LLM from being asked twice for the same pair within the same
     * JVM run. A persistent table would be the next step if needed.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> pairVerdictCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Get the full intel record for a skill. Returns 204 if the row exists
     * but no embedding has been computed yet (caller decides to compute).
     * Returns 404 if there's no row at all.
     *
     * Bumps last_seen_at as a side effect so the catalog admin can see which
     * skills are actually being touched at match time.
     */
    @GetMapping("/{name}/intel")
    public ResponseEntity<SkillIntelDto> getIntel(@PathVariable("name") String name) {
        String key = name.trim().toLowerCase();
        SkillCatalogEntry e = repo.findById(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Skill not in catalog: " + key));
        // Touch last-seen even when we have nothing cached yet — gives admins
        // visibility into which un-embedded rows are bottlenecking matches.
        repo.touchLastSeen(key, Instant.now());

        boolean hasEmbedding = e.getEmbedding() != null && !e.getEmbedding().isBlank();
        if (!hasEmbedding && e.getVolatility() == null && (e.getImplies() == null || e.getImplies().isEmpty())) {
            // No intel yet at all — let the caller know with 204 so it computes.
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(new SkillIntelDto(
                e.getName(),
                e.getDisplayName(),
                hasEmbedding ? parseVectorLiteral(e.getEmbedding()) : null,
                e.getEmbeddingModel(),
                e.getVolatility(),
                e.getHalfLife(),
                e.getImplies() != null ? e.getImplies() : new ArrayList<>(),
                e.getLastSeenAt()
        ));
    }

    /**
     * Persist any subset of the intel fields. Null fields are left unchanged
     * so the caller can write the embedding and the volatility in independent
     * calls (or both at once if the matcher computed everything in one pass).
     *
     * Embedding is written through the native UPDATE so the ::vector cast is
     * applied. The non-vector fields (volatility, half_life, implies, lastSeenAt)
     * go through normal JPA save() so dirty-checking handles them.
     */
    @PutMapping("/{name}/intel")
    public ResponseEntity<Void> putIntel(@PathVariable("name") String name,
                                         @RequestBody SkillIntelDto dto) {
        String key = name.trim().toLowerCase();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill name is required.");
        }

        SkillCatalogEntry entry = repo.findById(key).orElseGet(() -> SkillCatalogEntry.builder()
                .name(key)
                .displayName(dto.getDisplayName() != null ? dto.getDisplayName() : name.trim())
                .firstSeenAt(Instant.now())
                .source("EXTRACTED")
                .type("HARD")
                .domains(new ArrayList<>())
                .implies(new ArrayList<>())
                .build());

        // Write the vector first via native UPDATE — JPA can't bind String→vector.
        if (dto.getEmbedding() != null && !dto.getEmbedding().isEmpty()) {
            if (dto.getEmbedding().size() != 768) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Embedding must have 768 dimensions, got " + dto.getEmbedding().size());
            }
            // Ensure the row exists before the native UPDATE touches it.
            if (!repo.existsById(key)) {
                repo.save(entry);
            }
            repo.updateEmbedding(key, toVectorLiteral(dto.getEmbedding()),
                                 dto.getEmbeddingModel(), Instant.now());
        }

        // Now everything that goes through plain JPA.
        boolean dirty = false;
        if (dto.getVolatility() != null) { entry.setVolatility(dto.getVolatility()); dirty = true; }
        if (dto.getHalfLife() != null)   { entry.setHalfLife(dto.getHalfLife());     dirty = true; }
        if (dto.getImplies() != null)    { entry.setImplies(new ArrayList<>(dto.getImplies())); dirty = true; }
        // lastSeenAt always bumped on intel write so admins can see activity.
        entry.setLastSeenAt(Instant.now());
        dirty = true;
        if (dirty) repo.save(entry);

        return ResponseEntity.noContent().build();
    }

    /**
     * Dedup-aware, type-aware skill resolution.
     *
     * The caller sends a raw skill input + its already-computed embedding +
     * the expected type (HARD or SOFT). The endpoint runs a five-layer cascade:
     *
     *   1. EXACT        - PK lookup against the catalog
     *   2. ABBREVIATION - hardcoded short-form -> canonical map (k8s -> kubernetes)
     *   3. LEVENSHTEIN  - edit-distance lookup for typos (doker -> docker)
     *   4. EMBEDDING    - pgvector cosine NN search restricted by type
     *   5. CLASSIFY     - before creating NEW, ask the LLM to validate the skill
     *
     * Thresholds (empirically calibrated, see threshold_calibration.py):
     *   >= 0.93        -> AUTO_MERGE                  (silent, no LLM call)
     *   0.75 - 0.93    -> LLM tiebreaker -> AUTO_MERGE or NEW
     *   <  0.75        -> classify -> NEW / INVALID / TYPE_MISMATCH
     *
     * No REVIEW band: the system never blocks on a "did you mean?" prompt.
     * The LLM is more reliable than a UI question and works the same way for
     * interactive forms and background CV-parser flows.
     */
    @PostMapping("/resolve")
    public ResponseEntity<SkillResolveResponse> resolve(@RequestBody SkillResolveRequest req) {
        if (req == null || req.getInput() == null || req.getInput().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input is required");
        }
        String normalized = normalizeSkillName(req.getInput());
        String expectedType = normalizeExpectedType(req.getExpectedType());

        // ── Layer 1: EXACT PK lookup ────────────────────────────────────────
        Optional<SkillCatalogEntry> exact = repo.findById(normalized);
        if (exact.isPresent() && !exact.get().isRemoved()) {
            SkillCatalogEntry e = exact.get();
            repo.touchLastSeen(normalized, Instant.now());
            String existingDisplay = e.getDisplayName() != null ? e.getDisplayName() : e.getName();
            SkillResolveResponse out = new SkillResolveResponse(
                    SkillResolveResponse.Action.EXACT,
                    normalized,
                    e.getName(),
                    existingDisplay,
                    1.0,
                    List.of()
            );
            out.setMergeReason("EXACT");
            out.setSuggestedDisplayName(existingDisplay);
            return ResponseEntity.ok(out);
        }

        // ── Layer 2: ABBREVIATION dictionary ────────────────────────────────
        String canonical = ABBREVIATIONS.get(normalized);
        if (canonical != null) {
            Optional<SkillCatalogEntry> canonicalEntry = repo.findById(canonical);
            if (canonicalEntry.isPresent() && !canonicalEntry.get().isRemoved()) {
                SkillCatalogEntry e = canonicalEntry.get();
                repo.touchLastSeen(canonical, Instant.now());
                log.info("[skill-resolve] ABBREVIATION '{}' -> '{}'", normalized, canonical);
                String existingDisplay = e.getDisplayName() != null ? e.getDisplayName() : e.getName();
                SkillResolveResponse out = new SkillResolveResponse(
                        SkillResolveResponse.Action.AUTO_MERGE,
                        normalized,
                        e.getName(),
                        existingDisplay,
                        1.0,
                        List.of()
                );
                out.setMergeReason("ABBREVIATION");
                out.setSuggestedDisplayName(existingDisplay);
                return ResponseEntity.ok(out);
            }
            // Canonical not yet in catalog - rewrite the normalized form so the
            // downstream embedding/levenshtein layers search for the canonical.
            // The caller, on receiving a NEW response, will create the canonical
            // form rather than the abbreviation - and the suggestedDisplayName
            // below is computed from the canonical, not from the original input.
            log.info("[skill-resolve] ABBREVIATION '{}' rewritten to '{}' (canonical not in catalog)",
                    normalized, canonical);
            normalized = canonical;
        }

        // ── Layer 3: LEVENSHTEIN typo check ─────────────────────────────────
        try {
            List<Object[]> lex = repo.findNearestByLevenshtein(normalized, expectedType);
            if (!lex.isEmpty()) {
                Object[] m = lex.get(0);
                String n  = (String) m[0];
                String dn = (String) m[1];
                int dist  = ((Number) m[2]).intValue();
                repo.touchLastSeen(n, Instant.now());
                log.info("[skill-resolve] LEVENSHTEIN '{}' -> '{}' (distance {})",
                        normalized, n, dist);
                String existingDisplay = dn != null ? dn : n;
                SkillResolveResponse out = new SkillResolveResponse(
                        SkillResolveResponse.Action.AUTO_MERGE,
                        normalized,
                        n,
                        existingDisplay,
                        1.0 - (dist * 0.1),
                        List.of()
                );
                out.setMergeReason("LEVENSHTEIN");
                out.setSuggestedDisplayName(existingDisplay);
                return ResponseEntity.ok(out);
            }
        } catch (Exception e) {
            // fuzzystrmatch extension might not be installed yet on dev DBs.
            log.warn("[skill-resolve] Levenshtein query failed (extension missing?): {}",
                    e.getMessage());
        }

        // ── Layer 4: EMBEDDING + pgvector search ────────────────────────────
        // Lazy embedding: if the caller didn't include one, fetch it from
        // cv-parser-service NOW that we actually need it. This avoids wasting
        // ~50ms per skill when Layers 1-3 already resolved the input - which is
        // the typical case (EXACT/ABBREVIATION/LEVENSHTEIN catches ~80%+ of
        // real-world inputs after the catalog warms up).
        List<Float> embedding = req.getEmbedding();
        if (embedding == null || embedding.size() != 768) {
            embedding = fetchEmbeddingFromCvParser(normalized);
            if (embedding == null) {
                // cv-parser unreachable - skip Layer 4, hand off to Layer 5.
                log.warn("[skill-resolve] no embedding available for '{}'; "
                       + "skipping pgvector layer", normalized);
                return ResponseEntity.ok(classifyAndDecide(normalized, expectedType,
                        SkillResolveResponse.Action.NEW, null, null, null, List.of()));
            }
        }

        String vecLit = toVectorLiteral(embedding);
        List<Object[]> rows = expectedType != null
                ? repo.findNearestSkillsByType(vecLit, expectedType, normalized, SUGGESTION_LIMIT)
                : repo.findNearestSkills(vecLit, normalized, SUGGESTION_LIMIT);
        List<SkillResolveResponse.Suggestion> suggestions = new ArrayList<>();
        for (Object[] row : rows) {
            String n  = (String) row[0];
            String dn = (String) row[1];
            double sc = ((Number) row[2]).doubleValue();
            suggestions.add(new SkillResolveResponse.Suggestion(n, dn != null ? dn : n, sc));
        }

        if (suggestions.isEmpty()) {
            return ResponseEntity.ok(classifyAndDecide(normalized, expectedType,
                    SkillResolveResponse.Action.NEW, null, null, null, suggestions));
        }

        SkillResolveResponse.Suggestion top = suggestions.get(0);
        SkillResolveResponse.Action action;
        String mergeReason = null;

        if (top.getScore() >= AUTO_MERGE_THRESHOLD) {
            action = SkillResolveResponse.Action.AUTO_MERGE;
            mergeReason = "EMBEDDING";
        } else if (top.getScore() >= LLM_TIEBREAK_THRESHOLD) {
            // Gray band: ask qwen2.5:7b whether these are the same skill.
            // Replaces the old REVIEW "did you mean?" prompt - the LLM gives a
            // more reliable answer than a user-facing dialog and works the
            // same way for interactive forms and background CV parsing.
            Boolean cached = pairVerdictCache.get(pairKey(normalized, top.getName()));
            boolean same;
            if (cached != null) {
                same = cached;
            } else {
                same = askLlmIsSameSkill(normalized, top.getName());
                pairVerdictCache.put(pairKey(normalized, top.getName()), same);
            }
            if (same) {
                action = SkillResolveResponse.Action.AUTO_MERGE;
                mergeReason = "LLM";
            } else {
                action = SkillResolveResponse.Action.NEW;
            }
        } else {
            action = SkillResolveResponse.Action.NEW;
        }

        if (action == SkillResolveResponse.Action.AUTO_MERGE) {
            repo.touchLastSeen(top.getName(), Instant.now());
        }

        // ── Layer 5: CLASSIFY (only when about to create NEW) ──────────────
        if (action == SkillResolveResponse.Action.NEW) {
            return ResponseEntity.ok(classifyAndDecide(normalized, expectedType,
                    action, top.getName(), top.getDisplayName(), top.getScore(), suggestions));
        }

        // EXACT / AUTO_MERGE - trust the matched entry's existing display name.
        String matchedDisplay = action == SkillResolveResponse.Action.NEW
                ? null : top.getDisplayName();
        SkillResolveResponse out = new SkillResolveResponse(
                action,
                normalized,
                action == SkillResolveResponse.Action.NEW ? null : top.getName(),
                matchedDisplay,
                top.getScore(),
                suggestions
        );
        out.setMergeReason(mergeReason);
        out.setSuggestedDisplayName(matchedDisplay);
        return ResponseEntity.ok(out);
    }

    /**
     * Run the LLM classifier on a skill that would otherwise be created NEW.
     * Returns INVALID if the LLM says it's not a real skill, TYPE_MISMATCH if
     * the LLM says it's a real skill but of the wrong type, or NEW if the LLM
     * confirms the expected type (or if no expectedType was provided).
     */
    private SkillResolveResponse classifyAndDecide(
            String normalized,
            String expectedType,
            SkillResolveResponse.Action defaultAction,
            String matchedName,
            String matchedDisplay,
            Double topScore,
            List<SkillResolveResponse.Suggestion> suggestions
    ) {
        // Always call the LLM for NEW: it validates (HARD/SOFT/INVALID),
        // catches TYPE_MISMATCH, AND returns a properly-cased display name.
        // One call, three outputs - cheaper than maintaining a hardcoded list
        // of every possible skill and their conventional capitalizations.
        ClassifyResult cls = classifySkillWithLlm(normalized, expectedType);

        // Prefer the LLM's display name. Fall back to local title-case helper
        // when the LLM was unavailable (cls.displayName is null).
        String suggestedDisplay = cls.displayName != null && !cls.displayName.isBlank()
                ? cls.displayName
                : toProperDisplayName(normalized);

        SkillResolveResponse out = new SkillResolveResponse(
                defaultAction, normalized, matchedName, matchedDisplay,
                topScore, suggestions
        );
        out.setClassifiedType(cls.type);
        out.setClassifyReason(cls.reason);
        out.setSuggestedDisplayName(suggestedDisplay);
        out.setSuggestedImplies(cls.implies);

        // INVALID always wins regardless of expectedType - we never want
        // garbage in the catalog (job titles, company names, etc.).
        if ("INVALID".equals(cls.type)) {
            out.setAction(SkillResolveResponse.Action.INVALID);
            log.info("[skill-resolve] INVALID '{}': {}", normalized, cls.reason);
            return out;
        }

        // TYPE_MISMATCH only matters when the caller declared an expected type.
        if (expectedType != null && !cls.type.equals(expectedType)) {
            out.setAction(SkillResolveResponse.Action.TYPE_MISMATCH);
            log.info("[skill-resolve] TYPE_MISMATCH '{}': expected {}, llm says {}",
                    normalized, expectedType, cls.type);
        }
        return out;
    }

    /**
     * Convert a free-form type string ("hard", " SOFT ", null) to the canonical
     * uppercase HARD/SOFT - or null if the caller did not provide one.
     */
    private static String normalizeExpectedType(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toUpperCase();
        if (t.equals("HARD") || t.equals("SOFT")) return t;
        return null;
    }

    /**
     * Catalog normalization for skill names. Used as the catalog primary key.
     *
     * Three operations:
     *   1. trim - remove leading/trailing whitespace
     *   2. lowercase - case-insensitive matching
     *   3. collapse runs of whitespace into a single space - so "spring   boot"
     *      and "spring boot" produce the same PK
     *
     * The result is what we look up in the catalog and store in the `name` column.
     */
    private static String normalizeSkillName(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /**
     * Compute a clean, properly-cased display name for a normalized input.
     *
     *   - Check the SPECIAL_DISPLAY_NAMES map first - covers known special casings
     *     like "javascript" -> "JavaScript", "ios" -> "iOS", "scikit-learn" -> "scikit-learn".
     *   - Otherwise apply default title-casing: capitalize the first letter of each
     *     word (split on space, hyphen, dot, slash) and lowercase the rest.
     *
     * This protects the catalog from accumulating ugly variants when a user
     * types in all caps or all lowercase. The caller stores `suggestedDisplayName`
     * from the response, not whatever the user typed verbatim.
     */
    private static String toProperDisplayName(String normalized) {
        if (normalized == null || normalized.isBlank()) return "";
        String s = normalized.trim().toLowerCase().replaceAll("\\s+", " ");
        String special = SPECIAL_DISPLAY_NAMES.get(s);
        if (special != null) return special;

        StringBuilder out = new StringBuilder(s.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '-' || c == '/') {
                out.append(c);
                capitalizeNext = true;
            } else if (capitalizeNext) {
                out.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Result of the LLM classification call. Carries type + proper display name + implies + reason. */
    private static final class ClassifyResult {
        final String type;
        final String displayName;   // proper casing from the LLM (or null on failure)
        final List<String> implies; // skills this one is built on (lowercase canonical)
        final String reason;
        ClassifyResult(String type, String displayName, List<String> implies, String reason) {
            this.type = type;
            this.displayName = displayName;
            this.implies = implies != null ? implies : List.of();
            this.reason = reason;
        }
    }

    /**
     * Multi-vector retrieval indexing for a SOFT skill: embed the canonical
     * name plus each curated synonym and upsert one row per phrase into
     * skill_synonym_embedding. The Layer 1 query of /match-soft-skill then
     * aggregates MAX cosine similarity across all phrases per skill,
     * recovering recall on CV paraphrases that don't share tokens with the
     * canonical (the empirical gap measured at cosine ~0.50 - 0.62 for pure
     * paraphrases against a bare canonical name).
     *
     * @param replaceExisting when true (PATCH path), drop all existing
     *        phrase rows for this skill before re-inserting. False on POST
     *        because the row is new and we're just adding initial phrases.
     *
     * Fails soft per-phrase. An Ollama hiccup on synonym #3 doesn't roll
     * back synonyms #1, #2, or the canonical row; we just log and move on.
     * The next /backfill-phrase-embeddings call (or a re-PATCH) can fill
     * any missing rows later.
     */
    private void indexPhrasesForSkill(String canonicalKey,
                                      String displayName,
                                      List<String> synonyms,
                                      boolean replaceExisting) {
        if (canonicalKey == null || canonicalKey.isBlank()) return;
        try {
            if (replaceExisting) {
                int wiped = phraseRepo.deleteAllForSkill(canonicalKey);
                if (wiped > 0) {
                    log.info("[skill-catalog/phrase-index] wiped {} phrase rows for '{}' before re-index",
                            wiped, canonicalKey);
                }
            }

            // Canonical name first. We index it ALONGSIDE the synonyms in the
            // multi-vector store too (not just in skill_catalog_entry.embedding)
            // so the Layer 1 query has a single source of truth and doesn't
            // need to UNION the parent table.
            embedAndUpsertPhrase(canonicalKey, canonicalKey, "CANONICAL");

            // Each synonym
            for (String syn : synonyms) {
                if (syn == null) continue;
                String phrase = syn.trim().toLowerCase().replaceAll("\\s+", " ");
                if (phrase.isEmpty() || phrase.equals(canonicalKey)) continue;
                embedAndUpsertPhrase(canonicalKey, phrase, "SYNONYM");
            }
        } catch (Exception e) {
            // Outer guard: never let phrase indexing failures bubble up and
            // 500 a successful catalog write. The parent row is already
            // saved at this point.
            log.warn("[skill-catalog/phrase-index] outer failure for '{}': {}",
                    canonicalKey, e.getMessage());
        }
    }

    /** Embed one phrase and upsert it into the multi-vector store. */
    private void embedAndUpsertPhrase(String skillName, String phrase, String type) {
        try {
            List<Float> vec = fetchEmbeddingFromCvParser(phrase);
            if (vec == null || vec.size() != 768) {
                log.warn("[skill-catalog/phrase-index] embed failed for '{}'/'{}' ({})",
                        skillName, phrase, type);
                return;
            }
            phraseRepo.upsertPhrase(
                    skillName, phrase, type,
                    toVectorLiteral(vec), "nomic-embed-text", Instant.now());
        } catch (Exception e) {
            log.warn("[skill-catalog/phrase-index] upsert failed for '{}'/'{}': {}",
                    skillName, phrase, e.getMessage());
        }
    }

    /**
     * Lazy embedding fetch. Called only when the cascade reaches Layer 4 and
     * the caller did not include a precomputed vector in the request. Talks to
     * cv-parser-service /embed-text which wraps Ollama nomic-embed-text.
     *
     * Returns null on any failure - the cascade then falls through to Layer 5
     * (classify) without the pgvector search step.
     */
    @SuppressWarnings("unchecked")
    private List<Float> fetchEmbeddingFromCvParser(String text) {
        try {
            String url = cvParserUrl + "/api/cv-parser/embed-text";
            Map<String, Object> body = Map.of("text", text);
            Map<String, Object> resp = plainRestTemplate.postForObject(url, body, Map.class);
            if (resp == null || !(resp.get("embedding") instanceof List<?> raw)) return null;
            if (raw.size() != 768) {
                log.warn("[skill-resolve] embedder returned wrong dim {} for '{}'",
                        raw.size(), text);
                return null;
            }
            List<Float> vec = new ArrayList<>(raw.size());
            for (Object o : raw) {
                if (o instanceof Number n) vec.add(n.floatValue());
                else return null;
            }
            return vec;
        } catch (Exception e) {
            log.warn("[skill-resolve] embed-text call failed for '{}': {}", text, e.getMessage());
            return null;
        }
    }

    /**
     * Ask cv-parser-service to classify the skill name AND return its proper
     * display name + skills it implies in one call. The LLM knows every framework,
     * library, acronym and naming convention.
     *
     * Fails open: if the LLM is unavailable, returns HARD with a null displayName
     * and an empty implies list so the caller can fall back gracefully.
     */
    @SuppressWarnings("unchecked")
    private ClassifyResult classifySkillWithLlm(String text, String expectedType) {
        try {
            String url = cvParserUrl + "/api/cv-parser/skill-classify";
            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("text", text);
            if (expectedType != null) body.put("expected_type", expectedType);
            Map<String, Object> resp = plainRestTemplate.postForObject(url, body, Map.class);
            if (resp == null) return new ClassifyResult("HARD", null, List.of(), "no response");
            String type = resp.get("type") instanceof String s ? s.toUpperCase() : "HARD";
            if (!type.equals("HARD") && !type.equals("SOFT") && !type.equals("INVALID")) {
                type = "HARD";
            }
            String display = resp.get("display_name") instanceof String s ? s.trim() : null;
            if (display != null && display.isEmpty()) display = null;
            String reason = resp.get("reason") instanceof String s ? s : "";
            // Parse implies list - lowercase canonical skill names.
            List<String> implies = new ArrayList<>();
            Object rawImplies = resp.get("implies");
            if (rawImplies instanceof List<?> rawList) {
                for (Object item : rawList) {
                    if (item instanceof String s && !s.isBlank()) {
                        String cleaned = s.trim().toLowerCase().replaceAll("\\s+", " ");
                        if (!cleaned.isEmpty() && !cleaned.equals(text) && implies.size() < 3) {
                            implies.add(cleaned);
                        }
                    }
                }
            }
            return new ClassifyResult(type, display, implies, reason);
        } catch (Exception e) {
            log.warn("[skill-resolve] classify call failed for '{}': {}", text, e.getMessage());
            return new ClassifyResult("HARD", null, List.of(),
                    "classifier unavailable: " + e.getClass().getSimpleName());
        }
    }

    // ─── Vector ↔ string helpers ─────────────────────────────────────────────

    private static String toVectorLiteral(List<Float> v) {
        StringBuilder sb = new StringBuilder(v.size() * 12);
        sb.append('[');
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(v.get(i));
        }
        sb.append(']');
        return sb.toString();
    }

    private static List<Float> parseVectorLiteral(String raw) {
        String t = raw.trim();
        if (t.startsWith("[")) t = t.substring(1);
        if (t.endsWith("]"))   t = t.substring(0, t.length() - 1);
        String[] parts = t.split(",");
        List<Float> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) out.add(Float.parseFloat(s));
        }
        return out;
    }

    /**
     * Return every active catalog row of the requested type (HARD / SOFT)
     * together with its cached embedding. Used by the matcher to pull the
     * soft-signal list (leadership, communication, ...) at the start of a
     * match — the catalog is the source of truth, no hardcoded list in
     * Python and no per-match Ollama re-embedding of these strings.
     *
     * Rows with NULL embedding are filtered out so callers never have to
     * handle missing vectors.
     */
    @GetMapping("/by-type/{type}")
    public ResponseEntity<List<SkillIntelDto>> listByType(@PathVariable("type") String type) {
        String normalizedType = type == null ? "" : type.trim().toUpperCase();
        if (!normalizedType.equals("HARD") && !normalizedType.equals("SOFT")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Type must be HARD or SOFT");
        }
        List<SkillCatalogEntry> rows = repo.findActiveByTypeWithEmbedding(normalizedType);
        List<SkillIntelDto> out = new ArrayList<>(rows.size());
        for (SkillCatalogEntry e : rows) {
            out.add(new SkillIntelDto(
                    e.getName(),
                    e.getDisplayName(),
                    parseVectorLiteral(e.getEmbedding()),
                    e.getEmbeddingModel(),
                    e.getVolatility(),
                    e.getHalfLife(),
                    e.getImplies() != null ? e.getImplies() : new ArrayList<>(),
                    e.getLastSeenAt()
            ));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Find the closest catalog skill to a query vector, restricted to a list
     * of candidate names. Replaces the cv-parser-service Track 1 proxy loop:
     * instead of fetching every CV-skill vector to Python and looping, the
     * matcher sends one request and Postgres returns the best match.
     *
     * Returns 204 when no candidate has a cached embedding yet — the matcher
     * treats that as "no proxy available."
     */
    @PostMapping("/nearest-of")
    public ResponseEntity<NearestSkillResponse> nearestOf(@RequestBody NearestSkillRequest req) {
        if (req == null || req.getEmbedding() == null || req.getEmbedding().size() != 768) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Request must include a 768-dim embedding");
        }
        if (req.getCandidates() == null || req.getCandidates().isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        // Build the Postgres text-array literal: {"a","b","c"}. Escape double
        // quotes inside names defensively (rare but possible if a skill name
        // contains them).
        StringBuilder arr = new StringBuilder("{");
        boolean first = true;
        for (String n : req.getCandidates()) {
            if (n == null || n.isBlank()) continue;
            if (!first) arr.append(',');
            arr.append('"').append(n.replace("\"", "\\\"")).append('"');
            first = false;
        }
        arr.append('}');

        List<Object[]> rows = repo.findNearestOf(toVectorLiteral(req.getEmbedding()), arr.toString());
        if (rows.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        Object[] row = rows.get(0);
        String name  = (String) row[0];
        String dname = (String) row[1];
        double score = ((Number) row[2]).doubleValue();
        return ResponseEntity.ok(new NearestSkillResponse(name, dname != null ? dname : name, score));
    }

    /**
     * Match free-form CV text against the EXISTING soft-skill catalog.
     *
     * Hard and soft skills have different naming dynamics. Recruiters write
     * canonical hard-skill names ("React", "Spring Boot") that candidate CVs
     * mostly mirror with minor variants. The 5-layer /resolve cascade
     * canonicalizes all those variants into a single catalog row, and CV
     * extraction is allowed to grow the hard-skill catalog with NEW entries.
     *
     * Soft skills are different. Recruiters write clean canonical names
     * ("Communication", "Leadership") but CV text is free-form prose
     * ("strong written communication", "great team player", "I'm an
     * empathetic listener"). Letting all that prose enter the catalog
     * would pollute it with sentences and synonyms that aren't really
     * skill names.
     *
     * So the system treats soft skills asymmetrically:
     *   - Recruiters add canonical soft-skill names via job requirements
     *     and the admin Skills Catalog page - these grow the catalog.
     *   - CV-extracted soft-skill TEXT calls THIS endpoint - we embed the
     *     text, find the closest catalog entry via pgvector cosine NN
     *     restricted to type=SOFT, and return it only if similarity is
     *     above SOFT_MATCH_THRESHOLD. No new entries are ever created.
     *
     * Returns:
     *   - 200 + {name, displayName, score} when a confident match exists
     *   - 204 when no catalog entry is close enough
     *
     * Embedding is fetched lazily from cv-parser-service - the caller doesn't
     * need to compute it upfront.
     */
    @PostMapping("/match-soft-skill")
    public ResponseEntity<NearestSkillResponse> matchSoftSkill(@RequestBody java.util.Map<String, Object> body) {
        Object rawText = body == null ? null : body.get("text");
        if (!(rawText instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
        }
        String cleaned = s.trim().toLowerCase().replaceAll("\\s+", " ");

        // ── Layer 0: recruiter-curated synonym short-circuit ─────────────
        // Before paying the embedding round-trip, scan the active SOFT
        // catalog and check whether `cleaned` exactly matches any entry's
        // canonical name OR any of its synonyms. Catches the paraphrase
        // failures cosine similarity caps out on ("team lead" → leadership,
        // "communicates well" → communication). Score reported as 1.0
        // because this is a deterministic dictionary hit, not a probabilistic
        // match — the recruiter explicitly told us these are the same.
        List<SkillCatalogEntry> softRows = repo.findActiveByType("SOFT");
        for (SkillCatalogEntry row : softRows) {
            if (cleaned.equals(row.getName())) {
                repo.touchLastSeen(row.getName(), Instant.now());
                log.info("[match-soft-skill] '{}' -> '{}' (exact name)", cleaned, row.getName());
                return ResponseEntity.ok(new NearestSkillResponse(
                        row.getName(),
                        row.getDisplayName() != null ? row.getDisplayName() : row.getName(),
                        1.0));
            }
            if (row.getSynonyms() != null) {
                for (String syn : row.getSynonyms()) {
                    if (syn != null && cleaned.equals(syn.trim().toLowerCase())) {
                        repo.touchLastSeen(row.getName(), Instant.now());
                        log.info("[match-soft-skill] '{}' -> '{}' (synonym hit '{}')",
                                cleaned, row.getName(), syn);
                        return ResponseEntity.ok(new NearestSkillResponse(
                                row.getName(),
                                row.getDisplayName() != null ? row.getDisplayName() : row.getName(),
                                1.0));
                    }
                }
            }
        }

        // Lazy fetch embedding via cv-parser-service - the caller doesn't deal with Ollama.
        List<Float> embedding = fetchEmbeddingFromCvParser(cleaned);
        if (embedding == null) {
            log.warn("[match-soft-skill] embedder unavailable for '{}'", cleaned);
            return ResponseEntity.noContent().build();
        }

        // Layer 1: MULTI-VECTOR retrieval against skill_synonym_embedding.
        // For each parent SOFT skill the query aggregates MAX cosine
        // similarity across all its indexed phrases (canonical + every
        // synonym). The CV phrase is compared to each phrase's vector
        // separately, then we take the best score per skill.
        //
        // Empirical motivation (see SkillSynonymEmbedding javadoc):
        // canonical-vs-synonym cosine on nomic-embed-text is only 0.58 -
        // 0.73. Pure CV paraphrases that don't share tokens with the
        // canonical embed at 0.45 - 0.62, missing a single-vector query's
        // 0.70 threshold. Indexing synonyms as their own vectors closes
        // this gap because the CV phrase often embeds at 0.85+ to one of
        // its synonym variants.
        //
        // Fallback: if the multi-vector store is empty for any reason
        // (backfill not run yet, table just created), the query returns
        // nothing; we fall through to the legacy single-canonical query
        // below so the matcher stays useful during migration windows.
        List<Object[]> rows = phraseRepo.nearestByMaxScore(
                toVectorLiteral(embedding), "SOFT", 1);
        if (rows.isEmpty()) {
            log.info("[match-soft-skill] multi-vector store empty for '{}'; " +
                    "falling back to single-canonical search", cleaned);
            rows = repo.findNearestSkillsByType(
                    toVectorLiteral(embedding), "SOFT", "", 1);
        }
        if (rows.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        Object[] top = rows.get(0);
        String name        = (String) top[0];
        String displayName = (String) top[1];
        double score       = ((Number) top[2]).doubleValue();

        if (score < SOFT_MATCH_THRESHOLD) {
            log.info("[match-soft-skill] no confident match for '{}' "
                   + "(closest='{}' score={})", cleaned, name, score);
            return ResponseEntity.noContent().build();
        }

        // Touch the matched entry's last_seen_at so admins can see soft-skill
        // demand from CVs even though we never auto-insert.
        repo.touchLastSeen(name, Instant.now());

        log.info("[match-soft-skill] '{}' -> '{}' (score {})",
                cleaned, name, score);
        return ResponseEntity.ok(new NearestSkillResponse(
                name, displayName != null ? displayName : name, score));
    }

    // ─── LLM tiebreaker for the resolve gray band ────────────────────────────

    /** Canonical key for the in-memory pair-verdict cache. Order-independent. */
    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "||" + b : b + "||" + a;
    }

    /**
     * Ask cv-parser-service (which has Ollama wired) whether these two skill
     * names refer to the same skill. Returns false on any error — failing
     * conservative means a flaky LLM never silently merges two skills.
     */
    @SuppressWarnings("unchecked")
    private boolean askLlmIsSameSkill(String a, String b) {
        try {
            String url = cvParserUrl + "/api/cv-parser/skills/same";
            Map<String, Object> body = Map.of("a", a, "b", b);
            Map<String, Object> resp = plainRestTemplate.postForObject(url, body, Map.class);
            if (resp == null) return false;
            Object same = resp.get("same");
            return same instanceof Boolean b2 ? b2 : false;
        } catch (Exception e) {
            log.warn("[skill-resolve] LLM tiebreaker failed for '{}'/'{}': {}", a, b, e.getMessage());
            return false;
        }
    }

    /**
     * One-shot backfill for catalog rows that have no embedding yet.
     *
     * Walks every non-removed row where embedding IS NULL, asks
     * cv-parser-service to embed the skill name, and persists the vector
     * via the native UPDATE.
     *
     * Idempotent: re-running it only processes rows that still have NULL
     * embeddings (e.g. failed Ollama calls from the previous run).
     *
     * Returns counts so the caller can verify how many rows changed.
     */
    /**
     * One-shot backfill for the multi-vector phrase store. Walks every
     * SOFT skill row and inserts a phrase embedding for the canonical
     * name + each curated synonym that doesn't already have one.
     *
     * Idempotent: only processes missing phrases. Safe to re-run after
     * partial failures.
     *
     * Returns {totalMissing, filled, failed, failures}.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/backfill-phrase-embeddings")
    public ResponseEntity<Map<String, Object>> backfillPhraseEmbeddings() {
        List<Object[]> missing = phraseRepo.findMissingPhrases();
        int total = missing.size();
        int filled = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();

        for (Object[] row : missing) {
            String skillName = (String) row[0];
            String phrase    = (String) row[1];
            String type      = (String) row[2];
            try {
                List<Float> vec = fetchEmbeddingFromCvParser(phrase);
                if (vec == null || vec.size() != 768) {
                    failed++;
                    failures.add(skillName + "/" + phrase);
                    continue;
                }
                phraseRepo.upsertPhrase(skillName, phrase, type,
                        toVectorLiteral(vec), "nomic-embed-text", Instant.now());
                filled++;
            } catch (Exception e) {
                log.warn("[backfill-phrase] embed failed for '{}'/'{}': {}",
                        skillName, phrase, e.getMessage());
                failed++;
                failures.add(skillName + "/" + phrase);
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("totalMissing", total);
        resp.put("filled", filled);
        resp.put("failed", failed);
        resp.put("failures", failures);
        return ResponseEntity.ok(resp);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/backfill-embeddings")
    public ResponseEntity<Map<String, Object>> backfillEmbeddings() {
        List<SkillCatalogEntry> pending = repo.findRowsMissingEmbedding();
        int total = pending.size();
        int filled = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();

        String embedUrl = cvParserUrl + "/api/cv-parser/embed-text";
        for (SkillCatalogEntry row : pending) {
            try {
                // Ask Ollama (via cv-parser-service) to embed the canonical name.
                Map<String, Object> reqBody = Map.of("text", row.getName());
                Map<String, Object> resp = plainRestTemplate.postForObject(embedUrl, reqBody, Map.class);
                if (resp == null || !(resp.get("embedding") instanceof List<?> vec) || vec.size() != 768) {
                    failed++;
                    failures.add(row.getName());
                    continue;
                }
                List<Float> floats = new ArrayList<>(vec.size());
                for (Object o : vec) {
                    if (o instanceof Number n) floats.add(n.floatValue());
                }
                String model = resp.get("model") instanceof String s ? s : "nomic-embed-text";
                repo.updateEmbedding(row.getName(), toVectorLiteral(floats), model, Instant.now());
                filled++;
            } catch (Exception e) {
                log.warn("[backfill] Embed failed for '{}': {}", row.getName(), e.getMessage());
                failed++;
                failures.add(row.getName());
            }
        }

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("totalPending", total);
        result.put("filled", filled);
        result.put("failed", failed);
        result.put("failures", failures);
        return ResponseEntity.ok(result);
    }
}
