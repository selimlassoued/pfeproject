package com.zaina.interviewservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateDelegationRequest {
    @NotNull
    private UUID toRecruiterId;

    @Size(max = 1000)
    private String message;
}
