package com.zaina.interviewservice.services;


import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class JitsiTokenService {

    @Value("${jitsi.app-id:zaina-interview-app}")
    private String appId;

    @Value("${jitsi.secret:your-super-secret-key-at-least-32-chars!!}")
    private String secret;

    public String generateToken(String roomName, String userId,
                                String displayName, String email,
                                boolean isModerator) {

        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        // Use HashMap instead of Map.of() to avoid type inference issues
        Map<String, Object> user = new HashMap<>();
        user.put("id",        userId);
        user.put("name",      displayName);
        user.put("email",     email);
        user.put("moderator", String.valueOf(isModerator));

        Map<String, Object> features = new HashMap<>();
        features.put("recording",     isModerator);
        features.put("livestreaming", false);
        features.put("transcription", false);

        Map<String, Object> context = new HashMap<>();
        context.put("user",     user);
        context.put("features", features);

        return Jwts.builder()
                .setHeaderParam("kid", "zaina-jitsi-key")
                .setIssuer(appId)
                .setSubject("*")
                .setAudience("jitsi")
                .claim("room",    roomName)
                .claim("context", context)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3 * 60 * 60 * 1000L))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }
}