package com.recrutment.application.restControllers;

import com.recrutment.application.dto.SkillCatalogDto;
import com.recrutment.application.entities.SkillCatalogEntry;
import com.recrutment.application.repos.SkillCatalogRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
public class SkillCatalogController {

    private final SkillCatalogRepo repo;

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
}
