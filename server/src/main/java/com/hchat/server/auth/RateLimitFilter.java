package com.hchat.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hchat.server.common.ApiError;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.common.RequestContext;
import com.hchat.server.service.RateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 默认限流：每 IP / 每用户 / 每 app 三维令牌桶，超限返回 429 + 统一错误体。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterService limiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiterService limiter, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !limiter.isEnabled()
                || !path.startsWith("/api/")
                || path.startsWith("/api/openapi")
                || path.startsWith("/api/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = clientIp(request);
        boolean allowed = limiter.tryAcquire("ip:" + ip, limiter.ipRps());
        ChatPrincipal principal = CurrentUser.orNull();
        if (allowed && principal != null) {
            allowed = limiter.tryAcquire("user:" + principal.appId() + ":" + principal.userId(), limiter.userRps())
                    && limiter.tryAcquire("app:" + principal.appId(), limiter.appRps());
        }
        if (!allowed) {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(ApiError.of(
                    ErrorCode.RATE_LIMITED.code(), ErrorCode.RATE_LIMITED.message(), RequestContext.requestId())));
            return;
        }
        chain.doFilter(request, response);
    }

    /** 兼容反向代理场景：优先取 X-Forwarded-For 的第一跳。 */
    public static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
