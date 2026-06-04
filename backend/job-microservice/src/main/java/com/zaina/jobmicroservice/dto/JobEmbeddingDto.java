package com.zaina.jobmicroservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Cached semantic-matcher embedding for a JobOffer.
 *
 * Wire format: a 768-dim list of floats plus the model that produced them. The
 * vector itself lives in a pgvector VECTOR(768) column in the DB; this DTO
 * decouples HTTP callers (the Python cv-parser-service) from the pgvector
 * literal representation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobEmbeddingDto {
    /** Length must be 768 to match the column's declared dimension. */
    private List<Float> embedding;
    /** Embedding model identifier, e.g. "nomic-embed-text". */
    private String model;
}
