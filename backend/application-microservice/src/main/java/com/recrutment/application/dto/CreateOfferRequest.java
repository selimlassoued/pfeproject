package com.recrutment.application.dto;

import com.recrutment.application.enums.ContractType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false, message = "salary must be positive")
    private BigDecimal salary;

    /** Defaults to TND when omitted. ISO 4217 currency code (USD, EUR, TND...). */
    @Pattern(regexp = "^[A-Z]{3}$|^$", message = "currency must be a 3-letter ISO code")
    private String currency;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private ContractType contractType;

    /** Recruiter's note attached to the initial offer. */
    @Size(max = 2000)
    private String message;

    /** When the candidate must accept or decline by. */
    @Future(message = "expiresAt must be in the future")
    private Instant expiresAt;
}
