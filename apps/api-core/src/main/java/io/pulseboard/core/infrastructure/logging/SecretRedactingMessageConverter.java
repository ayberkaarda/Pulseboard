package io.pulseboard.core.infrastructure.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces the value of credential-bearing {@code name=value} pairs in log messages. Frameworks log request lines
 * (method, path and query string) at DEBUG and TRACE, and a stream ticket or a token in the query must not end up
 * in a log file. Registered for the message conversion word in {@code logback-spring.xml}.
 *
 * <p>The servlet container decodes parameter names, so {@code ?%74icket=...} is the {@code ticket} parameter even
 * though the raw request line does not contain that word. Every candidate name is therefore percent-decoded
 * (repeatedly, so double encoding is covered), lower-cased and stripped of {@code _} and {@code -} before it is
 * compared with the known secret names. JSON members such as {@code "refreshToken":"..."} are masked as well.
 */
public class SecretRedactingMessageConverter extends ClassicConverter {

    static final String MASK = "***";

    /** Normalised names (decoded, lower case, without '_' and '-'): ticket, access_token, token, jwt, ... */
    private static final Set<String> SECRET_NAMES = Set.of("ticket", "accesstoken", "token", "jwt", "refreshtoken");
    private static final int MAX_NAME_LENGTH = 96;
    private static final int MAX_DECODE_ROUNDS = 4;

    /** A name made of URL characters (including '%' escapes), directly followed by '=' and a value. */
    private static final Pattern PAIR = Pattern.compile(
            "(?<![A-Za-z0-9_.%+\\-])([A-Za-z0-9_.%+\\-]{1," + MAX_NAME_LENGTH
                    + "})(=)([^&\\s\"'#;,\\])}]*)");
    private static final Pattern JSON_MEMBER = Pattern.compile(
            "(?i)(\"(?:ticket|access_?token|refresh_?token|token|jwt)\"\\s*:\\s*\")[^\"]*");

    @Override
    public String convert(ILoggingEvent event) {
        return redact(event.getFormattedMessage());
    }

    static String redact(String message) {
        if (message == null) {
            return null;
        }
        String result = message;
        if (result.indexOf('=') >= 0) {
            result = maskPairs(result);
        }
        if (result.indexOf(':') >= 0) {
            result = JSON_MEMBER.matcher(result).replaceAll("$1" + MASK);
        }
        return result;
    }

    private static String maskPairs(String message) {
        Matcher matcher = PAIR.matcher(message);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            if (!isSecretName(matcher.group(1))) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(message.length());
            }
            out.append(message, last, matcher.start(3)).append(MASK);
            last = matcher.end(3);
        }
        if (out == null) {
            return message;
        }
        return out.append(message, last, message.length()).toString();
    }

    private static boolean isSecretName(String rawName) {
        String name = rawName;
        for (int round = 0; round < MAX_DECODE_ROUNDS && name.indexOf('%') >= 0; round++) {
            name = percentDecode(name);
        }
        String normalised = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SECRET_NAMES.contains(normalised);
    }

    /** Lenient decoder: an invalid escape is kept as it is. Names only matter as ASCII, so bytes become chars. */
    private static String percentDecode(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length() && hex(value.charAt(i + 1)) >= 0
                    && hex(value.charAt(i + 2)) >= 0) {
                decoded.append((char) (hex(value.charAt(i + 1)) * 16 + hex(value.charAt(i + 2))));
                i += 2;
            } else {
                decoded.append(c);
            }
        }
        return decoded.toString();
    }

    private static int hex(char c) {
        return Character.digit(c, 16);
    }
}
