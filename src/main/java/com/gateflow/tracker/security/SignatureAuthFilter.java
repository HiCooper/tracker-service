package com.gateflow.tracker.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * 采集端点分层鉴权：
 * - 无 X-App-Key → 401
 * - 有 X-App-Key，无签名 → 放行（Web SDK 模式，依赖限流+origin）
 * - 有 X-App-Key + X-Signature → 验证 HMAC，通过才放行（Server SDK 模式）
 */
/**
 * 采集端点鉴权：仅接受 tracker-admin 签发的 SDK Token。
 * 禁止 appKey-only / 无凭据请求。
 *
 * 授权流程：
 *   业务后端(apiKey) → tracker-admin → SDK Token → 注入页面 → SDK 携带 → 验证通过
 *   未授权方: 没有 apiKey → 拿不到 Token → 401
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SignatureAuthFilter implements Filter {

    private final SdkTokenValidator tokenValidator;

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        // 只拦截事件采集，放过鉴权端点
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/collect") || path.endsWith("/auth")) {
            chain.doFilter(req, resp);
            return;
        }

        String sdkToken = request.getHeader("X-Sdk-Token");
        if (sdkToken == null || sdkToken.isBlank()) {
            log.warn("Missing X-Sdk-Token from {}", request.getRemoteAddr());
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":401,\"message\":\"SDK Token required. Get one from tracker-admin with your apiKey.\"}");
            return;
        }

        if (!tokenValidator.isValid(sdkToken)) {
            log.warn("Invalid SDK token from {}", request.getRemoteAddr());
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":401,\"message\":\"Invalid or expired SDK token\"}");
            return;
        }

        chain.doFilter(req, resp);
    }
}
