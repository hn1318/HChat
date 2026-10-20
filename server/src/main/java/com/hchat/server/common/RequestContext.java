package com.hchat.server.common;

import org.slf4j.MDC;

/** 读取当前请求的 requestId（无请求上下文时返回 "-"）。 */
public final class RequestContext {

    private RequestContext() {}

    public static String requestId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        return id == null ? "-" : id;
    }
}
