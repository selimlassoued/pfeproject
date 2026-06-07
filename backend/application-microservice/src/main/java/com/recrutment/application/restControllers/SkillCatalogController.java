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
@RequiredArgsConstructor
@Slf4j
public class SkillCatalogController {

    private final SkillCatalogRepo repo;
    /** Plain RestTemplate (no JWT propagation) for internal service-to-service
     *  calls to cv-parser-service during backfill. */
    @Qualifier("plainRestTemplate")
    private final RestTemplate plainRestTemplate;

    @Value("${cv.parser.url:http://cv-parser-service:8085}")
    private String cvParserUrl;

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
                e.getDomains() != null ? e.getDomains() : new ArrayList<>()
        )).toList());
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

        SkillCatalogEntry saved = repo.save(entry);
        return ResponseEntity.ok(new SkillCatalogDto(
                saved.getName(),
                saved.getDisplayName(),
                saved.getFirstSeenAt(),
                null,
                saved.getSource(),
                saved.isRemoved(),
                saved.getType(),
                saved.getDomains()
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

        List<String> domains = new ArrayList<>();
        Object rawDomains = body.get("domains");
        if (rawDomains instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) domains.add(s.trim());
            }
        }

        SkillCatalogEntry entry = repo.findById(key).orElseGet(() -> SkillCatalogEntry.builder()
                .name(key)
                .displayName(name.trim())
                .firstSeenAt(Instant.now())
                .source("EXTRACTED")  // skill exists in jobs but not yet in this table
                .build());
        entry.setDomains(domains);  // REPLACE, not union — recruiter has full control
        SkillCatalogEntry saved = repo.save(entry);

        return ResponseEntity.ok(new SkillCatalogDto(
                saved.getName(),
                saved.getDisplayName(),
                saved.getFirstSeenAt(),
                null,
                saved.getSource(),
                saved.isRemoved(),
                saved.getType() != null ? saved.getType() : "HARD",
                saved.getDomains() != null ? saved.getDomains() : new ArrayList<>()
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

    /** Auto-merge above this cosine similarity — silently collapse, no LLM call. */
    private static final double AUTO_MERGE_THRESHOLD = 0.95;
    /** Gray band [LLM_TIEBREAK_THRESHOLD, AUTO_MERGE_THRESHOLD): consult qwen2.5:7b. */
    private static final double LLM_TIEBREAK_THRESHOLD = 0.90;
    /** Below this we don't bother — embedding says too different. */
    private static final double REVIEW_THRESHOLD     = 0.85;
    /** How many nearest neighbours to return on each resolve call. */
    private static final int    SUGGESTION_LIMIT     = 5;

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
     * Dedup-aware skill resolution.
     *
     * The caller (cv-parser-service or admin UI) sends a raw skill input
     * with its already-computed embedding. The endpoint runs the standard
     * three-layer pipeline:
     *   1. Normalize the input
     *   2. Exact PK lookup against the catalog
     *   3. Embedding cosine similarity against existing entries
     * and returns an action + the matched skill if any.
     *
     * Thresholds:
     *   ≥ 0.95 → AUTO_MERGE   (collapse onto the suggested entry silently)
     *   ≥ 0.85 → REVIEW       (surface "did you mean?" prompt, don't apply)
     *   <  0.85 → NEW          (caller can safely add as a new catalog row)
     */
    @PostMapping("/resolve")
    public ResponseEntity<SkillResolveResponse> resolve(@RequestBody SkillResolveRequest req) {
        if (req == null || req.getInput() == null || req.getInput().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input is required");
        }
        String normalized = req.getInput().trim().toLowerCase();

        // Step 1: exact PK lookup wins immediately.
        Optional<SkillCatalogEntry> exact = repo.findById(normalized);
        if (exact.isPresent() && !exact.get().isRemoved()) {
            SkillCatalogEntry e = exact.get();
            repo.touchLastSeen(normalized, Instant.now());
            return ResponseEntity.ok(new SkillResolveResponse(
                    SkillResolveResponse.Action.EXACT,
                    normalized,
                    e.getName(),
                    e.getDisplayName() != null ? e.getDisplayName() : e.getName(),
                    1.0,
                    List.of()
            ));
        }

        // Step 2: nearest-neighbour search needs a vector.
        if (req.getEmbedding() == null || req.getEmbedding().size() != 768) {
            // Caller didn't provide one — return NEW with no suggestion.
            return ResponseEntity.ok(new SkillResolveResponse(
                    SkillResolveResponse.Action.NEW, normalized,
                    null, null, null, List.of()
            ));
        }

        String vecLit = toVectorLiteral(req.getEmbedding());
        List<Object[]> rows = repo.findNearestSkills(vecLit, normalized, SUGGESTION_LIMIT);
        List<SkillResolveResponse.Suggestion> suggestions = new ArrayList<>();
        for (Object[] row : rows) {
            String n  = (String) row[0];
            String dn = (String) row[1];
            double sc = ((Number) row[2]).doubleValue();
            suggestions.add(new SkillResolveResponse.Suggestion(n, dn != null ? dn : n, sc));
        }

        if (suggestions.isEmpty()) {
            return ResponseEntity.ok(new SkillResolveResponse(
                    SkillResolveResponse.Action.NEW, normalized,
                    null, null, null, suggestions
            ));
        }

        SkillResolveResponse.Suggestion top = suggestions.get(0);
        SkillResolveResponse.Action action;

        if (top.getScore() >= AUTO_MERGE_THRESHOLD) {
            // High-confidence: silent auto-merge, no LLM call needed.
            action = SkillResolveResponse.Action.AUTO_MERGE;
        } else if (top.getScore() >= LLM_TIEBREAK_THRESHOLD) {
            // 0.90-0.95 gray band: ask qwen2.5:7b whether these are the same skill.
            // The LLM is much better than fixed thresholds at telling React from
            // React Native, Spring from Spring Boot, etc. Cached so the same pair
            // never goes back to the LLM within this JVM lifetime.
            Boolean cached = pairVerdictCache.get(pairKey(normalized, top.getName()));
            boolean same;
            if (cached != null) {
                same = cached;
            } else {
                same = askLlmIsSameSkill(normalized, top.getName());
                pairVerdictCache.put(pairKey(normalized, top.getName()), same);
            }
            action = same ? SkillResolveResponse.Action.AUTO_MERGE
                          : SkillResolveResponse.Action.NEW;
        } else if (top.getScore() >= REVIEW_THRESHOLD) {
            // 0.85-0.90: too distant for the LLM to plausibly say "same" — skip
            // the call. Surface as REVIEW so admins can periodically batch-clean.
            action = SkillResolveResponse.Action.REVIEW;
        } else {
            action = SkillResolveResponse.Action.NEW;
        }

        // Bump the matched entry's recency when we auto-merge — it just got used.
        if (action == SkillResolveResponse.Action.AUTO_MERGE) {
            repo.touchLastSeen(top.getName(), Instant.now());
        }

        return ResponseEntity.ok(new SkillResolveResponse(
                action,
                normalized,
                action == SkillResolveResponse.Action.NEW ? null : top.getName(),
                action == SkillResolveResponse.Action.NEW ? null : top.getDisplayName(),
                top.getScore(),
                suggestions
        ));
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
