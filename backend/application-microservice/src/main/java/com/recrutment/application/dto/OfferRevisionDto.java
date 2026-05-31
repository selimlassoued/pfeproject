package com.recrutment.application.dto;

import com.recrutment.application.entities.OfferRevision;
import com.recrutment.application.enums.ContractType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfferRevisionDto {
    private UUID id;
    private UUID offerId;
    private OfferRevision.ProposedBy proposedBy;
    private BigDecimal salary;
    private String currency;
    private LocalDate startDate;
    private ContractType contractType;
    private String message;
    private Instant createdAt;
}
