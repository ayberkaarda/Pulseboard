package io.pulseboard.core.application.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Generation and hashing of high-entropy opaque secrets (API keys, refresh tokens). */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SecureTokens() {
    }

    /** Returns {@code byteCount} random bytes encoded as unpadded base64url. */
    public static String randomUrlSafe(int byteCount) {
        if (byteCount < 16) {
            throw new IllegalArgumentException("At least 16 random bytes are required");
        }
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /**
     * Lower-case hex SHA-256 of the UTF-8 bytes of {@code value}. A fast unsalted hash is adequate here because
     * the inputs are random secrets with at least 128 bits of entropy, not user-chosen passwords.
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", ex);
        }
    }
}
