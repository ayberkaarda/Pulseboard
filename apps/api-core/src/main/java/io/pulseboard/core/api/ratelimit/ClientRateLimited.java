package io.pulseboard.core.api.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks controllers (or single handler methods) whose requests consume the per-client authentication bucket.
 * The check is bound to the handler Spring MVC actually resolved, so every URL spelling that reaches the handler
 * (percent-encoding, path parameters, trailing slashes) is throttled the same way.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ClientRateLimited {
}
