package io.pulseboard.core.application.alert.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs webhook requests.
 *
 * <p>The signature is the lower-case hex HMAC-SHA256, keyed with the signing secret, of the string
 * {@code timestamp + "." + body}, where {@code timestamp} is the value of the {@code X-Pulseboard-Timestamp} header
 * (Unix time in seconds) and {@code body} the raw request body. A receiver recomputes it over the bytes it
 * received, compares in constant time, and rejects timestamps that are too old to stop replays.
 */
public final class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {
    }

    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available", ex);
        }
    }
}
