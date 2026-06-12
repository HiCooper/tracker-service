package com.gateflow.tracker.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HMAC-SHA256 签名验证器。
 * 从 tracker-admin 获取 appSecret，本地缓存 5 分钟。
 */
@Slf4j
@Component
public class SignatureVerifier {

    private static final String ADMIN_URL = "http://localhost:8099/api/v1/internal/app-secret/";
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 300_000;

    private record CachedSecret(String secret, long expireAt) {}

    /**
     * 验证 HMAC-SHA256 签名。
     * @param appKey    X-App-Key header
     * @param timestamp X-Timestamp header（毫秒，防重放）
     * @param body      请求体原文
     * @param signature X-Signature header: Base64(HMAC-SHA256(secret, body + timestamp))
     */
    public boolean verify(String appKey, String timestamp, String body, String signature) {
        if (appKey == null || appKey.isBlank()) return false;
        if (timestamp == null || signature == null) return false;

        try {
            long ts = Long.parseLong(timestamp);
            if (Math.abs(System.currentTimeMillis() - ts) > 300_000) {
                log.warn("Expired timestamp: appKey={}", appKey);
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }

        String secret = getSecret(appKey);
        if (secret.isEmpty()) return false;

        return sign(body + timestamp, secret).equals(signature);
    }

    private String getSecret(String appKey) {
        var cached = cache.get(appKey);
        if (cached != null && System.currentTimeMillis() < cached.expireAt) return cached.secret;

        try {
            var req = HttpRequest.newBuilder().uri(URI.create(ADMIN_URL + appKey))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String secret = extractJsonValue(resp.body(), "appSecret");
                cache.put(appKey, new CachedSecret(secret, System.currentTimeMillis() + CACHE_TTL_MS));
                return secret;
            }
        } catch (Exception e) {
            log.error("Failed to fetch secret for {}: {}", appKey, e.getMessage());
        }
        return "";
    }

    public static String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String extractJsonValue(String json, String key) {
        String s = "\"" + key + "\":\"";
        int a = json.indexOf(s);
        if (a < 0) return "";
        a += s.length();
        int b = json.indexOf('"', a);
        return b < 0 ? "" : json.substring(a, b);
    }
}
