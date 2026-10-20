package com.hchat.server.auth;

import com.hchat.server.common.Times;
import com.hchat.server.common.ApiException;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.config.HChatProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * 短期 JWT 的签发与校验（HS256）。
 *
 * <p>密钥由 {@code JWT_SECRET} 经 SHA-256 派生，保证任意长度的配置都能得到合规的 256 位密钥。
 */
@Component
public class JwtService {

    private final HChatProperties props;
    private final SecretKey key;

    public JwtService(HChatProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(sha256(props.getJwt().getSecret()));
    }

    public record IssuedToken(String token, long expiresInSeconds, Instant expiresAt) {}

    public IssuedToken issue(long userId, String appId, String tenantId, String externalUserId, List<String> scopes) {
        Instant now = Times.now();
        long ttl = props.getJwt().getTtlSeconds();
        Instant exp = now.plusSeconds(ttl);
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(props.getJwt().getIssuer())
                .subject(String.valueOf(userId))
                .claim("appId", appId)
                .claim("tenantId", tenantId)
                .claim("externalUserId", externalUserId)
                .claim("scopes", scopes == null ? List.of("chat") : scopes)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new IssuedToken(token, ttl, exp);
    }

    public ChatPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(props.getJwt().getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            String appId = claims.get("appId", String.class);
            String tenantId = claims.get("tenantId", String.class);
            String externalUserId = claims.get("externalUserId", String.class);
            if (appId == null || tenantId == null) {
                throw new ApiException(ErrorCode.UNAUTHORIZED, "Token 缺少 appId/tenantId");
            }
            Set<String> scopes = new LinkedHashSet<>();
            Object raw = claims.get("scopes");
            if (raw instanceof List<?> list) {
                list.forEach(s -> scopes.add(String.valueOf(s)));
            } else if (raw instanceof String s) {
                Arrays.stream(s.split("[,\\s]+")).filter(v -> !v.isBlank()).forEach(scopes::add);
            }
            return new ChatPrincipal(userId, appId, tenantId, externalUserId, scopes);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Token 无效或已过期");
        }
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
