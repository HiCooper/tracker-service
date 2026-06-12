package com.gateflow.tracker.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenGenerator {

    private final SecretKey key;

    public JwtTokenGenerator(@Value("${jwt.secret:GateFlowTrackerJwtSecretKey2026!!}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generate(String appCode) {
        Date now = new Date();
        return Jwts.builder()
                .subject(appCode)
                .claim("type", "sdk_token")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3_600_000))
                .signWith(key)
                .compact();
    }
}
