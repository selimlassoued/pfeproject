package com.zaina.interviewservice.dto;

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
    private List<LocalDateTime> proposedSlots;
    /** When the recipient must respond by. */
    private LocalDateTime deadline;
    /** Optional reason / context shown to the recipient. */
    private String message;
}
