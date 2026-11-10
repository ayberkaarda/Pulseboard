package io.pulseboard.core.domain.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an aggregate whose rows belong to exactly one tenant. Repository methods that read or modify such
 * aggregates must take a {@link io.pulseboard.core.domain.tenancy.TenantId}; this is enforced by an
 * architecture test.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TenantScoped {
}
