package io.pulseboard.core.application.auth;

import io.pulseboard.core.domain.identity.User;
import java.time.Instant;

/** Port for minting short-lived signed access tokens. */
public interface AccessTokenIssuer {

    IssuedAccessToken issue(User user, Instant issuedAt);

    /** A signed access token and the instant it stops being accepted. */
    record IssuedAccessToken(String value, Instant expiresAt) {
    }
}
