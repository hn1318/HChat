package com.hchat.server.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** appSecret 的哈希与比对工具。 */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {}

    /**
     * appSecret 只存哈希。约定 appSecret 由服务端随机生成（≥32 字节熵），
     * 因此这里使用 SHA-256 即可满足「泄库不代表泄密」的要求。
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 常量时间比较，避免时序侧信道。 */
    public static boolean matches(String rawSecret, String storedHash) {
        if (rawSecret == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sha256Hex(rawSecret).getBytes(StandardCharsets.UTF_8),
                storedHash.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    public static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String randomWebhookSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }
}
