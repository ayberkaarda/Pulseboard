package io.pulseboard.core.application.auth;

/** Input for password login; users are identified by tenant slug plus email. */
public record LoginCommand(String tenantSlug, String email, String password) {
}
