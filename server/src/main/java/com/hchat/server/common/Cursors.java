package com.hchat.server.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** 不透明游标：{@code base64url("<epochMillis>:<id>")}。 */
public final class Cursors {

    private Cursors() {}

    public record Decoded(Instant time, long id) {}

    public static String encode(Instant time, long id) {
        long millis = time == null ? 0L : time.toEpochMilli();
        String raw = millis + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Decoded decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            if (sep <= 0) {
                throw new IllegalArgumentException("bad cursor");
            }
            long millis = Long.parseLong(raw.substring(0, sep));
            long id = Long.parseLong(raw.substring(sep + 1));
            return new Decoded(millis == 0 ? null : Instant.ofEpochMilli(millis), id);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "cursor 不合法");
        }
    }
}
