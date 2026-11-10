package io.pulseboard.core.application.auth;

/** Input for creating a tenant together with its first OWNER user. */
public record RegisterCommand(String tenantSlug, String tenantName, String email, String password) {
}
