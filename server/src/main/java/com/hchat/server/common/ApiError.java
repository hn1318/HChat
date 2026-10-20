package com.hchat.server.common;

import java.util.Map;

/** 标准错误响应：{@code {"error":{"code","message","requestId","details?"}}}。 */
public record ApiError(Body error) {

    public record Body(String code, String message, String requestId, Map<String, Object> details) {}

    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(new Body(code, message, requestId, null));
    }

    public static ApiError of(String code, String message, String requestId, Map<String, Object> details) {
        return new ApiError(new Body(code, message, requestId, details == null || details.isEmpty() ? null : details));
    }
}
