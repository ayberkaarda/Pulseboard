package io.pulseboard.core.application.auth;

import io.pulseboard.core.application.common.ResourceConflictException;
import io.pulseboard.core.application.common.SecureTokens;
import io.pulseboard.core.application.ratelimit.RateLimiter;
import io.pulseboard.core.domain.identity.RefreshToken;
import io.pulseboard.core.domain.identity.RefreshTokenOwner;
import io.pulseboard.core.domain.identity.RefreshTokenRepository;
import io.pulseboard.core.domain.identity.Role;
import io.pulseboard.core.domain.identity.User;
import io.pulseboard.core.domain.identity.UserRepository;
import io.pulseboard.core.domain.tenancy.Plan;
import io.pulseboard.core.domain.tenancy.Tenant;
import io.pulseboard.core.domain.tenancy.TenantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration, password login and refresh-token rotation.
 *
 * <p>Password hashing (Argon2id) is CPU and memory heavy, so it always runs outside a database transaction: no
 * pooled connection is held while a hash is computed. Login attempts are throttled per tenant slug and email
 * before any hashing (the per-client limit is applied earlier, in the web filter chain).
 *
 * <p>Refresh tokens are opaque random strings stored only as SHA-256 hashes. Each exchange revokes the
 * presented token and issues a successor in the same family. Presenting an already rotated token is treated
 * as theft: the whole family is revoked. All refresh operations of one user are serialized on the user row, so
 * a revocation can never miss a successor that a concurrent rotation is about to commit.
 */
@Service
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);
    private static final String INVALID_CREDENTIALS = "Invalid credentials";
    private static final String INVALID_REFRESH_TOKEN = "Invalid refresh token";
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RateLimiter rateLimiter;
    private final AuthThrottlingPolicy throttlingPolicy;
    private final Clock clock;
    private final Duration refreshTokenTtl;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final String timingEqualizationHash;

    public AuthService(TenantRepository tenantRepository, UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder,
            AccessTokenIssuer accessTokenIssuer, RateLimiter rateLimiter, AuthThrottlingPolicy throttlingPolicy,
            Clock clock, PlatformTransactionManager transactionManager,
            @Value("${pulseboard.security.jwt.refresh-token-ttl}") Duration refreshTokenTtl) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.rateLimiter = rateLimiter;
        this.throttlingPolicy = throttlingPolicy;
        this.clock = clock;
        this.refreshTokenTtl = refreshTokenTtl;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        // Verified against when the tenant or user does not exist, so failed logins cost the same time.
        this.timingEqualizationHash = passwordEncoder.encode(SecureTokens.randomUrlSafe(REFRESH_TOKEN_BYTES));
    }

    /** Creates a FREE tenant and its OWNER user, then signs the owner in. */
    public AuthTokens register(RegisterCommand command) {
        String slug = normalizeSlug(command.tenantSlug());
        String passwordHash = passwordEncoder.encode(command.password());
        return writeTransaction.execute(status -> {
            if (tenantRepository.existsBySlug(slug)) {
                throw new ResourceConflictException("Tenant slug '" + slug + "' is already taken");
            }
            Instant now = clock.instant();
            Tenant tenant = tenantRepository.save(new Tenant(slug, command.tenantName().trim(), Plan.FREE, now));
            User owner = userRepository.save(new User(tenant.tenantId(), command.email(), passwordHash, Role.OWNER,
                    now));
            LOG.info("Registered tenant {} with owner {}", tenant.getId(), owner.getId());
            return issueTokens(owner, UUID.randomUUID(), now);
        });
    }

    public AuthTokens login(LoginCommand command) {
        String slug = normalizeSlug(command.tenantSlug());
        String email = User.normalizeEmail(command.email());
        rateLimiter.acquireOrThrow(AuthThrottlingPolicy.ACCOUNT_NAMESPACE, SecureTokens.sha256Hex(slug + "\n" + email),
                throttlingPolicy.perAccount());

        Optional<User> user = readTransaction.execute(status -> tenantRepository.findBySlug(slug)
                .flatMap(tenant -> userRepository.findByTenantIdAndEmail(tenant.tenantId(), email)));
        String hash = user.map(User::getPasswordHash).orElse(timingEqualizationHash);
        boolean passwordMatches = passwordEncoder.matches(command.password(), hash);
        if (user.isEmpty() || !passwordMatches) {
            throw new AuthenticationFailedException(INVALID_CREDENTIALS);
        }
        return writeTransaction.execute(status -> issueTokens(user.get(), UUID.randomUUID(), clock.instant()));
    }

    /**
     * Exchanges a refresh token for a new token pair. Not rolled back on authentication failure, so a family
     * revocation triggered by token reuse is committed even though the request fails.
     */
    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public AuthTokens refresh(String presentedToken) {
        String tokenHash = SecureTokens.sha256Hex(presentedToken);
        RefreshTokenOwner owner = refreshTokenRepository.findOwnerByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthenticationFailedException(INVALID_REFRESH_TOKEN));
        // Serialization point for every rotation and revocation of this user's token families.
        User user = userRepository.findLockedByIdAndTenantId(owner.userId(), owner.tenantId())
                .orElseThrow(() -> new AuthenticationFailedException(INVALID_REFRESH_TOKEN));
        RefreshToken current = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthenticationFailedException(INVALID_REFRESH_TOKEN));

        Instant now = clock.instant();
        if (current.isRevoked()) {
            int revoked = refreshTokenRepository.revokeFamily(current.getFamilyId(), current.getTenantId(), now);
            LOG.warn("Refresh token reuse detected for family {} of tenant {}; revoked {} active token(s)",
                    current.getFamilyId(), current.getTenantId(), revoked);
            throw new AuthenticationFailedException(INVALID_REFRESH_TOKEN);
        }
        if (current.isExpiredAt(now)) {
            throw new AuthenticationFailedException(INVALID_REFRESH_TOKEN);
        }

        String rawSuccessor = SecureTokens.randomUrlSafe(REFRESH_TOKEN_BYTES);
        RefreshToken successor = new RefreshToken(user.getTenantId(), user.getId(), current.getFamilyId(),
                SecureTokens.sha256Hex(rawSuccessor), now, now.plus(refreshTokenTtl));
        current.rotateTo(successor, now);
        refreshTokenRepository.save(successor);
        refreshTokenRepository.save(current);
        return tokens(user, rawSuccessor, successor.getExpiresAt(), now);
    }

    private AuthTokens issueTokens(User user, UUID familyId, Instant now) {
        String rawRefreshToken = SecureTokens.randomUrlSafe(REFRESH_TOKEN_BYTES);
        RefreshToken refreshToken = refreshTokenRepository.save(new RefreshToken(user.getTenantId(), user.getId(),
                familyId, SecureTokens.sha256Hex(rawRefreshToken), now, now.plus(refreshTokenTtl)));
        return tokens(user, rawRefreshToken, refreshToken.getExpiresAt(), now);
    }

    private AuthTokens tokens(User user, String rawRefreshToken, Instant refreshExpiresAt, Instant now) {
        AccessTokenIssuer.IssuedAccessToken accessToken = accessTokenIssuer.issue(user, now);
        return new AuthTokens(accessToken.value(), accessToken.expiresAt(), rawRefreshToken, refreshExpiresAt,
                user.getId(), user.getTenantId(), user.getRole());
    }

    private static String normalizeSlug(String slug) {
        return slug.trim().toLowerCase(Locale.ROOT);
    }
}
