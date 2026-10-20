package com.hchat.server.auth;

import com.hchat.server.common.ApiException;
import com.hchat.server.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 从 SecurityContext 取当前调用者。 */
public final class CurrentUser {

    private CurrentUser() {}

    public static ChatPrincipal require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ChatPrincipal principal) {
            return principal;
        }
        throw new ApiException(ErrorCode.UNAUTHORIZED);
    }

    public static ChatPrincipal orNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof ChatPrincipal p) ? p : null;
    }
}
