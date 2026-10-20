package com.hchat.server.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花 ID 生成器：41 位毫秒时间戳 + 10 位 workerId + 12 位序列号。
 *
 * <p>ID 单调递增，天然可作为消息游标；返回值在 JSON 中按字符串输出，避免 JS 精度丢失。
 */
@Component
public class IdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01T00:00:00Z
    private static final long WORKER_BITS = 10L;
    private static final long SEQ_BITS = 12L;
    private static final long MAX_WORKER = (1L << WORKER_BITS) - 1;
    private static final long MAX_SEQ = (1L << SEQ_BITS) - 1;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public IdGenerator(@Value("${hchat.worker-id:0}") long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException("hchat.worker-id 必须在 0.." + MAX_WORKER + " 之间");
        }
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            // 时钟回拨：退化为上一个时间戳继续发号，保证单调
            now = lastTimestamp;
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQ;
            if (sequence == 0) {
                now = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << (WORKER_BITS + SEQ_BITS)) | (workerId << SEQ_BITS) | sequence;
    }

    /** 由 ID 反解出生成时间，用于归档、调试。 */
    public static long timestampOf(long id) {
        return (id >>> (WORKER_BITS + SEQ_BITS)) + EPOCH;
    }

    private long waitNextMillis(long last) {
        long now = System.currentTimeMillis();
        while (now <= last) {
            Thread.onSpinWait();
            now = System.currentTimeMillis();
        }
        return now;
    }
}
