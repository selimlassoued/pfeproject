package com.recrutment.application.dto;

import com.recrutment.application.enums.ContractType;
import com.recrutment.application.enums.OfferStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfferDto {
    private UUID id;
    private UUID applicationId;
    private UUID jobId;
    private UUID recruiterId;
    private String candidateUserId;

    private BigDecimal salary;
    private String currency;
    private LocalDate startDate;
    private ContractType contractType;
    private String message;

    private OfferStatus status;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant respondedAt;

    /** Full back-and-forth, ordered oldest to newest. */
    private List<OfferRevisionDto> revisions;
}
