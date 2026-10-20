package com.hchat.server.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** 鉴权相关 DTO。 */
public final class AuthDtos {

    private AuthDtos() {}

    /**
     * 业务方后端调用（持有 appSecret），为某个外部用户换取短期 Token。
     */
    public record TokenRequest(
            @NotBlank @Size(max = 64) String appId,
            @NotBlank @Size(max = 256) String appSecret,
            @NotBlank @Size(max = 128) String externalUserId,
            @Size(max = 128) String displayName,
            @Size(max = 512) String avatarUrl,
            Map<String, Object> metadata,
            Long ttlSeconds) {}

    /** 仅开发模式可用：不校验 appSecret，便于本地 playground 联调。 */
    public record DevTokenRequest(
            @NotBlank @Size(max = 64) String appId,
            @NotBlank @Size(max = 128) String externalUserId,
            @Size(max = 128) String displayName) {}

    public record TokenResponse(
            String token,
            String tokenType,
            long expiresIn,
            String expiresAt,
            String userId,
            String externalUserId,
            String appId,
            String tenantId,
            List<String> scopes) {}
}
