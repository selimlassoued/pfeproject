package com.recrutment.application.dto;

import com.recrutment.application.enums.ContractType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A counter-proposal from either side during offer negotiation. All term
 * fields (salary / currency / startDate / contractType) are optional — when
 * one is omitted the current Offer value carries over. Message is required
 * because that's the discussion text.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateRevisionRequest {
    private BigDecimal salary;
    private String currency;
    private LocalDate startDate;
    private ContractType contractType;
    private String message;
}
