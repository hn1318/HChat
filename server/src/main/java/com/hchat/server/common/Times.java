package com.hchat.server.common;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 时间工具：数据库列统一为 TIMESTAMP(3)，
 * 因此内存中的时间也截断到毫秒，保证「写入返回」与「再次读取」完全一致。
 */
public final class Times {

    private Times() {}

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
