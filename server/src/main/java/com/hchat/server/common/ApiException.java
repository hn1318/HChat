package com.hchat.server.common;

/** 业务异常：携带统一错误码，由 {@link GlobalExceptionHandler} 转成标准错误响应。 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static ApiException of(ErrorCode code, String message) {
        return new ApiException(code, message);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
