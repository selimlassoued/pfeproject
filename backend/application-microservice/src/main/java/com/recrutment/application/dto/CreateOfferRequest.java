package com.recrutment.application.dto;

import com.recrutment.application.enums.ContractType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateOfferRequest {
    private BigDecimal salary;

    /** Defaults to TND when omitted. */
    private String currency;

    private LocalDate startDate;

    private ContractType contractType;

    /** Recruiter's note attached to the initial offer. */
    private String message;

    /** When the candidate must accept or decline by. */
    private Instant expiresAt;
}
