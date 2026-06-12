package com.gateflow.tracker.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * 验证 tracker-admin 签发的 SDK Token（JWT）。
 * 共享 secret，token 服务端签发 → appSecret 永不出现在浏览器。
 */
@Slf4j
@Component
public class SdkTokenValidator {

    private final SecretKey key;

    public SdkTokenValidator(@Value("${jwt.secret:GateFlowTrackerJwtSecretKey2026!!}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String extractAppCode(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean isValid(String token) {
        try {
            return "sdk_token".equals(parseClaims(token).get("type", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
