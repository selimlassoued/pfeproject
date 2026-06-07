package com.zaina.interviewservice.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateReschedRequest {
    /** 2-4 candidate-pickable new times. */
    @NotEmpty(message = "at least one proposed slot is required")
    @Size(max = 10, message = "max 10 slots")
    private List<LocalDateTime> proposedSlots;

    /** When the recipient must respond by. */
    @NotNull
    private LocalDateTime deadline;

    /** Optional reason / context shown to the recipient. */
    @Size(max = 1000)
    private String message;
}
