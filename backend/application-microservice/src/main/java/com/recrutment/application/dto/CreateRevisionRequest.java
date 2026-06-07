package com.recrutment.application.dto;

import com.recrutment.application.enums.ContractType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    @DecimalMin(value = "0.0", inclusive = false, message = "salary must be positive")
    private BigDecimal salary;

    @Pattern(regexp = "^[A-Z]{3}$|^$", message = "currency must be a 3-letter ISO code")
    private String currency;

    private LocalDate startDate;

    private ContractType contractType;

    @NotBlank
    @Size(max = 2000)
    private String message;
}
