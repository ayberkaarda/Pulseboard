package io.pulseboard.core.infrastructure.security;

import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Authentication of a machine caller that presented a valid {@code X-Api-Key}. */
public final class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    /** Authority granted to every API-key authenticated request. */
    public static final String API_KEY_ROLE = "API_KEY";

    private static final long serialVersionUID = 1L;

    private final UUID apiKeyId;
    private final TenantId tenantId;

    public ApiKeyAuthenticationToken(UUID apiKeyId, TenantId tenantId) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + API_KEY_ROLE)));
        this.apiKeyId = apiKeyId;
        this.tenantId = tenantId;
        setAuthenticated(true);
    }

    public TenantId getTenantId() {
        return tenantId;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return apiKeyId;
    }
}
