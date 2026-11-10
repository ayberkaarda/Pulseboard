package io.pulseboard.core.infrastructure.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Pattern;

/**
 * Replaces the value of credential-bearing query parameters in log messages. Frameworks log request lines
 * (method, path and query string) at DEBUG and TRACE, and a stream ticket or an access token in the query must not
 * end up in a log file. Registered for the message conversion word in {@code logback-spring.xml}.
 */
public class SecretRedactingMessageConverter extends ClassicConverter {

    static final String MASK = "***";
    private static final Pattern SECRET_PARAMETER = Pattern.compile(
            "(?i)((?<![A-Za-z0-9_])(?:ticket|access_token)=)[^&\\s\"'#]*");

    @Override
    public String convert(ILoggingEvent event) {
        return redact(event.getFormattedMessage());
    }

    static String redact(String message) {
        if (message == null || message.indexOf('=') < 0) {
            return message;
        }
        return SECRET_PARAMETER.matcher(message).replaceAll("$1" + MASK);
    }
}
