package com.hchat.server.auth;

import java.util.Set;

/**
 * 认证后的调用者上下文（来自短期 JWT）。
 *
 * @param userId         内部用户 ID（external_users.id）
 * @param appId          所属应用
 * @param tenantId       所属租户
 * @param externalUserId 业务方用户 ID
 * @param scopes         授权范围
 */
public record ChatPrincipal(Long userId, String appId, String tenantId, String externalUserId, Set<String> scopes) {

    public boolean hasScope(String scope) {
        return scopes != null && (scopes.contains(scope) || scopes.contains("*"));
    }
}
