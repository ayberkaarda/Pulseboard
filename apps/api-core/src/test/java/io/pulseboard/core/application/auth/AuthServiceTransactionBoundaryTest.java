package io.pulseboard.core.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.pulseboard.core.application.ratelimit.RateLimitExceededException;
import io.pulseboard.core.application.ratelimit.RateLimitPolicy;
import io.pulseboard.core.application.ratelimit.RateLimiter;
import io.pulseboard.core.domain.identity.RefreshToken;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Password hashing must never run while a transaction (and therefore a pooled connection) is open, and a
 * throttled login must not reach the database or the password encoder.
 */
class AuthServiceTransactionBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final RateLimitPolicy POLICY = new RateLimitPolicy(10, Duration.ofMinutes(15));

    private final RecordingPasswordEncoder passwordEncoder = new RecordingPasswordEncoder();
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final AccessTokenIssuer accessTokenIssuer = mock(AccessTokenIssuer.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final List<Boolean> repositoryCallsInTransaction = new ArrayList<>();
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(tenantRepository, userRepository, refreshTokenRepository, passwordEncoder,
                accessTokenIssuer, rateLimiter, new AuthThrottlingPolicy(POLICY, POLICY),
                Clock.fixed(NOW, ZoneOffset.UTC), new InMemoryTransactionManager(), Duration.ofDays(7),
                Duration.ofDays(30));
        passwordEncoder.calls.clear();
        when(accessTokenIssuer.issue(any(), any()))
                .thenReturn(new AccessTokenIssuer.IssuedAccessToken("access", NOW.plusSeconds(900)));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> {
            repositoryCallsInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.getArgument(0);
        });
    }

    @Test
    void loginVerifiesThePasswordOutsideAnyTransaction() {
        Tenant tenant = new Tenant("acme", "Acme", Plan.FREE, NOW);
        User user = new User(tenant.tenantId(), "owner@example.com", "plain:secret-password", Role.OWNER, NOW);
        when(tenantRepository.findBySlug("acme")).thenAnswer(invocation -> {
            repositoryCallsInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(tenant);
        });
        when(userRepository.findByTenantIdAndEmail(tenant.tenantId(), "owner@example.com"))
                .thenReturn(Optional.of(user));

        AuthTokens tokens = authService.login(new LoginCommand("acme", "owner@example.com", "secret-password"));

        assertThat(tokens.userId()).isEqualTo(user.getId());
        assertThat(passwordEncoder.calls).containsExactly("matches:false");
        assertThat(repositoryCallsInTransaction).isNotEmpty().containsOnly(true);
    }

    @Test
    void registerHashesThePasswordOutsideAnyTransaction() {
        when(tenantRepository.existsBySlug("acme")).thenAnswer(invocation -> {
            repositoryCallsInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return false;
        });
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.register(new RegisterCommand("acme", "Acme", "owner@example.com", "secret-password"));

        assertThat(passwordEncoder.calls).containsExactly("encode:false");
        assertThat(repositoryCallsInTransaction).isNotEmpty().containsOnly(true);
    }

    @Test
    void throttledLoginTouchesNeitherTheDatabaseNorThePasswordEncoder() {
        doThrow(new RateLimitExceededException(Duration.ofSeconds(30))).when(rateLimiter)
                .acquireOrThrow(eq(AuthThrottlingPolicy.ACCOUNT_NAMESPACE), anyString(), eq(POLICY));

        assertThatThrownBy(() -> authService.login(new LoginCommand("acme", "owner@example.com", "guess")))
                .isInstanceOf(RateLimitExceededException.class);

        assertThat(passwordEncoder.calls).isEmpty();
        verifyNoInteractions(tenantRepository, userRepository);
    }

    /** Deterministic encoder that records whether a transaction was active on every hash operation. */
    private static final class RecordingPasswordEncoder implements PasswordEncoder {

        private final List<String> calls = new ArrayList<>();

        @Override
        public String encode(CharSequence rawPassword) {
            calls.add("encode:" + TransactionSynchronizationManager.isActualTransactionActive());
            return "plain:" + rawPassword;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            calls.add("matches:" + TransactionSynchronizationManager.isActualTransactionActive());
            return ("plain:" + rawPassword).equals(encodedPassword);
        }
    }

    /** Transaction manager without a resource; it only drives Spring's transaction synchronization state. */
    private static final class InMemoryTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // No underlying resource to bind.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // Nothing to commit.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // Nothing to roll back.
        }
    }
}
