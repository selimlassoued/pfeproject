package com.zaina.interviewservice.dto;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MeetingTokenResponse {
    private String token;
    private String roomUrl;
    private String roomName;
}