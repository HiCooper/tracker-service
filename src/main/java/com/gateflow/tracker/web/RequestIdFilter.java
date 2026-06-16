package com.gateflow.tracker.web;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求分配/透传关联 ID(X-Request-Id),写入 SLF4J MDC 并回写响应头,
 * 实现跨服务/跨日志的请求追踪。最高优先级执行,确保后续过滤器与业务日志都带上该 ID。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements Filter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final int MAX_LEN = 64;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String requestId = sanitize(req.getHeader(HEADER));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }

        MDC.put(MDC_KEY, requestId);
        resp.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 仅接受安全字符并限长,防止日志注入/超长头。无效则返回 null 触发自动生成。 */
    static String sanitize(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LEN) {
            return null;
        }
        return value.matches("[A-Za-z0-9._-]+") ? value : null;
    }
}
