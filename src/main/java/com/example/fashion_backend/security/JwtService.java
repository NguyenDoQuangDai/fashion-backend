package com.example.fashion_backend.security;

import com.example.fashion_backend.entity.RoleEntity;
import com.example.fashion_backend.entity.UserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey secretKey;
    private final long accessMinutes;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-minutes:60}") long accessMinutes) {
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(encodeSecret(secret)));
        this.accessMinutes = accessMinutes;
    }

    public String generateAccessToken(UserEntity user) {
        Instant now = Instant.now();
        List<String> roles = user.getRoles() == null ? List.of() : user.getRoles().stream()
                .map(RoleEntity::getName)
                .toList();
        return Jwts.builder()
                .subject(user.getId().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessMinutes * 60)))
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .signWith(secretKey)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getSubject(String token) {
        return parseClaims(token).getSubject();
    }

    private String encodeSecret(String secret) {
        String value = secret == null ? "" : secret;
        if (value.length() < 32) {
            StringBuilder builder = new StringBuilder(value);
            while (builder.length() < 32) {
                builder.append("0");
            }
            value = builder.toString();
        }
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
