package com.recrutment.application.repos;

import com.recrutment.application.entities.SkillCatalogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SkillCatalogRepo extends JpaRepository<SkillCatalogEntry, String> {

    /** All entries including removed ones — used by the recruiter admin page
     *  which has a "Show removed" section for restoring tombstoned skills. */
    List<SkillCatalogEntry> findAllByOrderByFirstSeenAtDesc();

    /** Active (non-removed) entries — used by the candidate-facing chip grid. */
    List<SkillCatalogEntry> findByRemovedFalseOrderByFirstSeenAtDesc();

    Optional<SkillCatalogEntry> findByNameIgnoreCase(String name);
}
