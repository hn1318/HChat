package com.hchat.server.common;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码。错误响应体固定为 {@code {"error":{"code","message","requestId"}}}。
 */
public enum ErrorCode {
    INVALID_REQUEST("invalid_request", "请求参数不合法", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("unauthorized", "未认证或 Token 无效", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("forbidden", "没有权限执行该操作", HttpStatus.FORBIDDEN),
    NOT_FOUND("not_found", "资源不存在", HttpStatus.NOT_FOUND),
    CONFLICT("conflict", "资源冲突", HttpStatus.CONFLICT),
    PAYLOAD_TOO_LARGE("payload_too_large", "内容超过限制", HttpStatus.PAYLOAD_TOO_LARGE),
    RATE_LIMITED("rate_limited", "请求过于频繁", HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR("internal_error", "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    NOT_IMPLEMENTED("not_implemented", "能力未启用", HttpStatus.NOT_IMPLEMENTED),

    APP_NOT_FOUND("app_not_found", "appId 不存在", HttpStatus.UNAUTHORIZED),
    INVALID_APP_SECRET("invalid_app_secret", "appSecret 校验失败", HttpStatus.UNAUTHORIZED),
    APP_DISABLED("app_disabled", "应用已被停用", HttpStatus.FORBIDDEN),
    DEV_TOKEN_DISABLED("dev_token_disabled", "开发 Token 接口未启用", HttpStatus.NOT_FOUND),

    CONVERSATION_NOT_FOUND("conversation_not_found", "会话不存在", HttpStatus.NOT_FOUND),
    NOT_A_MEMBER("not_a_member", "不是该会话成员", HttpStatus.FORBIDDEN),
    MEMBER_EXISTS("member_exists", "成员已存在", HttpStatus.CONFLICT),

    MESSAGE_NOT_FOUND("message_not_found", "消息不存在", HttpStatus.NOT_FOUND),
    RECALL_WINDOW_EXPIRED("recall_window_expired", "已超过可撤回时间", HttpStatus.CONFLICT),
    MESSAGE_NOT_OWNED("message_not_owned", "只能撤回自己发送的消息", HttpStatus.FORBIDDEN),

    TENANT_REQUIRED("tenant_required", "需要 tenantId", HttpStatus.BAD_REQUEST),
    AI_AGENT_NOT_FOUND("ai_agent_not_found", "AI 智能体不存在", HttpStatus.NOT_FOUND),
    AI_PROVIDER_ERROR("ai_provider_error", "AI 服务调用失败", HttpStatus.BAD_GATEWAY),
    AI_PROVIDER_DISABLED("ai_provider_disabled", "AI 能力未启用", HttpStatus.NOT_IMPLEMENTED),

    UNSUPPORTED_MESSAGE_TYPE("unsupported_message_type", "不支持的消息类型", HttpStatus.BAD_REQUEST),
    FILE_TOO_LARGE("file_too_large", "文件超过大小限制", HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_FILE_TYPE("unsupported_file_type", "不支持的文件类型", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    STORAGE_ERROR("storage_error", "文件存储失败", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus status;

    ErrorCode(String code, String message, HttpStatus status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public HttpStatus status() {
        return status;
    }
}
