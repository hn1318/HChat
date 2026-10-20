package com.hchat.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hchat.server.common.ApiError;
import com.hchat.server.common.ApiException;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 解析 {@code Authorization: Bearer <jwt>}，把调用者写入 SecurityContext。
 * Token 无效时直接返回 401（统一错误体），不进入业务链路。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER.length()).trim();
        try {
            ChatPrincipal principal = jwtService.parse(token);
            List<SimpleGrantedAuthority> authorities = principal.scopes().stream()
                    .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                    .toList();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, authorities));
        } catch (ApiException ex) {
            writeUnauthorized(response, ex.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }

    public void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiError body = ApiError.of(ErrorCode.UNAUTHORIZED.code(),
                message == null ? ErrorCode.UNAUTHORIZED.message() : message, RequestContext.requestId());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
