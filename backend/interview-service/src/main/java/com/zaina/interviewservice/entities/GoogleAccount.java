package com.zaina.interviewservice.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A recruiter's Google Calendar link. Holds the long-lived OAuth refresh token
 * so the backend can mint short-lived access tokens whenever it needs to push
 * an interview onto their calendar — without the recruiter signing in again.
 */
@Entity
@Table(name = "google_accounts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoogleAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Keycloak subject of the recruiter — one Google link per recruiter. */
    @Column(nullable = false, unique = true)
    private UUID recruiterId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String refreshToken;

    @CreationTimestamp
    private LocalDateTime connectedAt;
}
