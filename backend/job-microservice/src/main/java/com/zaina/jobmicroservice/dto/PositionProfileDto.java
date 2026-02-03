package com.zaina.jobmicroservice.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PositionProfileDto {
    private UUID id;
    private String normalizedSummary;
    private String embeddingRef;
}
