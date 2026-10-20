package com.hchat.server.auth;

import com.hchat.server.api.dto.AuthDtos.DevTokenRequest;
import com.hchat.server.api.dto.AuthDtos.TokenRequest;
import com.hchat.server.api.dto.AuthDtos.TokenResponse;
import com.hchat.server.common.ApiException;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.config.HChatProperties;
import com.hchat.server.domain.App;
import com.hchat.server.domain.ExternalUser;
import com.hchat.server.mapper.AppMapper;
import com.hchat.server.service.UserDirectoryService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Token 签发：校验 appId + appSecret，映射外部用户到内部用户，签发短期 JWT。
 * appSecret 只在业务方后端使用，前端永远拿不到。
 */
@Service
public class AuthService {

    private static final List<String> DEFAULT_SCOPES = List.of("chat", "ai");

    private final AppMapper appMapper;
    private final UserDirectoryService users;
    private final JwtService jwtService;
    private final HChatProperties props;

    public AuthService(AppMapper appMapper, UserDirectoryService users, JwtService jwtService,
                       HChatProperties props) {
        this.appMapper = appMapper;
        this.users = users;
        this.jwtService = jwtService;
        this.props = props;
    }

    public TokenResponse issueToken(TokenRequest request) {
        App app = requireApp(request.appId());
        if (!Secrets.matches(request.appSecret(), app.getAppSecretHash())) {
            throw new ApiException(ErrorCode.INVALID_APP_SECRET);
        }
        return issueFor(app, request.externalUserId(), request.displayName(), request.avatarUrl(), request.metadata());
    }

    /** 开发模式下的免密签发，生产环境默认关闭（DEV_MODE=false）。 */
    public TokenResponse issueDevToken(DevTokenRequest request) {
        if (!props.isDevMode()) {
            throw new ApiException(ErrorCode.DEV_TOKEN_DISABLED);
        }
        App app = requireApp(request.appId());
        return issueFor(app, request.externalUserId(), request.displayName(), null, Map.of());
    }

    private App requireApp(String appId) {
        App app = appMapper.selectById(appId);
        if (app == null) {
            throw new ApiException(ErrorCode.APP_NOT_FOUND);
        }
        if (!"active".equals(app.getStatus())) {
            throw new ApiException(ErrorCode.APP_DISABLED);
        }
        return app;
    }

    private TokenResponse issueFor(App app, String externalUserId, String displayName, String avatarUrl,
                                   Map<String, Object> metadata) {
        ExternalUser user = users.resolve(app.getTenantId(), app.getId(), externalUserId, displayName, avatarUrl,
                metadata);
        JwtService.IssuedToken issued = jwtService.issue(
                user.getId(), app.getId(), app.getTenantId(), user.getExternalUserId(), DEFAULT_SCOPES);
        return new TokenResponse(
                issued.token(),
                "Bearer",
                issued.expiresInSeconds(),
                issued.expiresAt().toString(),
                String.valueOf(user.getId()),
                user.getExternalUserId(),
                app.getId(),
                app.getTenantId(),
                DEFAULT_SCOPES);
    }
}
