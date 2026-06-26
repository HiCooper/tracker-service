package com.gateflow.tracker.api;

import com.gateflow.tracker.security.JwtTokenGenerator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * SDK 自服务鉴权 — 业务方前端引入 SDK + appKey 即可，无需后端介入。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/collect")
public class AuthController {

    private final JwtTokenGenerator tokenGenerator;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    /** tracker-admin 内网基址(容器/分布式部署可配),默认本机 8082。 */
    private final String appKeyUrl;

    public AuthController(JwtTokenGenerator tokenGenerator,
                          @Value("${gateflow.admin.base-url:http://localhost:8082}") String adminBaseUrl) {
        this.tokenGenerator = tokenGenerator;
        this.appKeyUrl = adminBaseUrl.replaceAll("/$", "") + "/api/v1/internal/app-key/";
    }

    @PostMapping("/auth")
    public ResponseEntity<Map<String, Object>> authenticate(HttpServletRequest request) {
        String appKey = request.getHeader("X-App-Key");
        if (appKey == null || appKey.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing X-App-Key"));
        }

        if (!isValidAppKey(appKey)) {
            log.warn("Auth rejected: invalid appKey from {}", request.getRemoteAddr());
            return ResponseEntity.status(401).body(Map.of("error", "Invalid appKey"));
        }

        String token = tokenGenerator.generate(appKey);
        log.info("SDK auth OK: appKey={}, ip={}", appKey, request.getRemoteAddr());
        return ResponseEntity.ok(Map.of("sdkToken", token, "expiresIn", 3600));
    }

    private boolean isValidAppKey(String appKey) {
        try {
            var resp = httpClient.send(
                    HttpRequest.newBuilder().uri(URI.create(appKeyUrl + appKey))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            // valid = body contains non-empty appCode like "appCode":"A_MAIN"
            String body = resp.body();
            return resp.statusCode() == 200 && body.contains("\"appCode\":\"") && !body.contains("\"appCode\":\"\"");
        } catch (Exception e) {
            log.error("appKey validation failed: {}", e.getMessage());
            return false;
        }
    }
}
