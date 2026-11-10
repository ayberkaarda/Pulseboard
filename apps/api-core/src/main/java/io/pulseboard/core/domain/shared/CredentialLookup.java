package io.pulseboard.core.domain.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the only repository methods allowed to query a {@link TenantScoped} aggregate without a tenant:
 * lookups by the hash of a high-entropy secret (API key, refresh token) that resolve the tenant in the first
 * place. The architecture test pins the exact set of methods carrying this annotation.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CredentialLookup {
}
