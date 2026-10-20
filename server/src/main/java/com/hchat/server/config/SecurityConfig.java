package com.hchat.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hchat.server.auth.JwtAuthenticationFilter;
import com.hchat.server.auth.RateLimitFilter;
import com.hchat.server.common.ApiError;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.common.RequestContext;
import com.hchat.server.domain.App;
import com.hchat.server.mapper.AppMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 安全配置：无状态 JWT、CORS 白名单（环境变量 + apps.allowed_origins）、默认限流。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final HChatProperties props;
    private final AppMapper appMapper;
    private final ObjectMapper objectMapper;

    /** apps.allowed_origins 的读多写少缓存，避免每次 CORS 预检都查库。 */
    private final Cache<String, Set<String>> originCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    public SecurityConfig(HChatProperties props, AppMapper appMapper, ObjectMapper objectMapper) {
        this.props = props;
        this.appMapper = appMapper;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtFilter,
                                           RateLimitFilter rateLimitFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.frameOptions(frame -> frame.disable()))
                .authorizeHttpRequests(auth -> auth
                        // SSE / 异步请求的二次分派不带认证上下文，需放行，否则会被判为 Access Denied
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/health", "/health/**").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        .requestMatchers("/files/**").permitAll()
                        .requestMatchers("/api/openapi.json", "/api/swagger-ui/**", "/api/swagger-ui.html").permitAll()
                        .requestMatchers("/", "/playground/**", "/embed/**", "/docs/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> writeError(response, ErrorCode.UNAUTHORIZED);
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> writeError(response, ErrorCode.FORBIDDEN);
    }

    private void writeError(HttpServletResponse response, ErrorCode code) {
        try {
            response.setStatus(code.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiError.of(code.code(), code.message(), RequestContext.requestId())));
        } catch (Exception e) {
            log.debug("写错误响应失败", e);
        }
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return (HttpServletRequest request) -> {
            CorsConfiguration config = new CorsConfiguration();
            List<String> origins = new ArrayList<>(props.getCorsOrigins());
            origins.addAll(appOrigins());
            config.setAllowedOriginPatterns(origins.stream().distinct().toList());
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("*"));
            config.setExposedHeaders(List.of("X-Request-Id"));
            config.setAllowCredentials(false);
            config.setMaxAge(3600L);
            return config;
        };
    }

    private Set<String> appOrigins() {
        return originCache.get("all", key -> {
            Set<String> result = new LinkedHashSet<>();
            try {
                for (App app : appMapper.selectList(null)) {
                    String raw = app.getAllowedOrigins();
                    if (raw == null || raw.isBlank()) {
                        continue;
                    }
                    for (String origin : raw.split(",")) {
                        if (!origin.isBlank()) {
                            result.add(origin.trim());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("读取 apps.allowed_origins 失败（忽略）", e);
            }
            return result;
        });
    }
}
