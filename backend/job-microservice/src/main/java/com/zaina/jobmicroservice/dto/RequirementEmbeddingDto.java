package com.zaina.jobmicroservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Cached embedding for a single JobRequirement.
 *
 * Used in two shapes:
 *  - As the body of PUT /api/jobs/{jobId}/requirements/{reqId}/embedding
 *  - Inside the list returned by GET /api/jobs/{jobId}/requirement-embeddings
 *    where each entry carries the requirement id alongside its vector
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequirementEmbeddingDto {
    /** Null when used as a PUT body (the id comes from the URL). */
    private UUID requirementId;
    /** 768 floats matching the column dimension. */
    private List<Float> embedding;
    /** Model that produced the vector — e.g. "nomic-embed-text". */
    private String model;
}
