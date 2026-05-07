package com.zaina.interviewservice.dto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ConsentUpdateRequest {
    private Boolean recordingConsent;
}