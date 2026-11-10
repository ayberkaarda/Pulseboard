package io.pulseboard.core.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pulseboard.core.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V6 and V7 on data that already exists: the migrations up to V5 are applied to a scratch schema, refresh tokens are
 * inserted, and then V6 (and V7) run over them.
 */
class RefreshFamilyLifetimeMigrationIntegrationTest extends IntegrationTest {

    private static final String SCHEMA = "migration_v6_check";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void existingFamiliesGetThirtyDaysFromTheirFirstTokenAndNoActiveSessionIsShortened() throws Exception {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        try {
            Flyway before = flyway("5");
            before.migrate();
            JdbcTemplate scratch = new JdbcTemplate(new SingleConnectionDataSource(
                    dataSource.getConnection(), true));
            scratch.execute("SET search_path TO " + SCHEMA);

            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            UUID tenant = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            scratch.update("INSERT INTO tenant (id, slug, name, plan, created_at) VALUES (?, 'mig', 'Mig', 'FREE', ?)",
                    tenant, Timestamp.from(now));
            scratch.update("INSERT INTO app_user (id, tenant_id, email, password_hash, role) "
                    + "VALUES (?, ?, 'a@example.com', 'x', 'OWNER')", user, tenant);

            UUID family = UUID.randomUUID();
            Instant firstIssued = now.minus(10, ChronoUnit.DAYS);
            insertToken(scratch, tenant, user, family, firstIssued, firstIssued.plus(7, ChronoUnit.DAYS));
            insertToken(scratch, tenant, user, family, now.minus(2, ChronoUnit.DAYS),
                    now.plus(5, ChronoUnit.DAYS));
            // A token whose own expiry is already beyond 30 days after the family start keeps its expiry.
            UUID oddFamily = UUID.randomUUID();
            insertToken(scratch, tenant, user, oddFamily, now.minus(1, ChronoUnit.DAYS),
                    now.plus(60, ChronoUnit.DAYS));

            flyway(null).migrate();

            assertThat(expiries(scratch, family)).containsOnly(Timestamp.from(firstIssued.plus(30, ChronoUnit.DAYS)));
            assertThat(expiries(scratch, oddFamily)).containsOnly(Timestamp.from(now.plus(60, ChronoUnit.DAYS)));
            assertThat(scratch.queryForObject("""
                    SELECT is_nullable FROM information_schema.columns
                     WHERE table_schema = ? AND table_name = 'refresh_token' AND column_name = 'family_expires_at'
                    """, String.class, SCHEMA)).isEqualTo("NO");
            assertThatThrownBy(() -> insertTokenWithFamilyEnd(scratch, tenant, user, now))
                    .hasMessageContaining("refresh_token_family_expiry_ck");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void migrationSevenGivesEveryTokenOfAFamilyTheLatestEndAlreadyComputed() throws Exception {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        try {
            flyway("5").migrate();
            JdbcTemplate scratch = new JdbcTemplate(new SingleConnectionDataSource(
                    dataSource.getConnection(), true));
            scratch.execute("SET search_path TO " + SCHEMA);

            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            UUID tenant = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            scratch.update("INSERT INTO tenant (id, slug, name, plan, created_at) VALUES (?, 'mig7', 'Mig', 'FREE', ?)",
                    tenant, Timestamp.from(now));
            scratch.update("INSERT INTO app_user (id, tenant_id, email, password_hash, role) "
                    + "VALUES (?, ?, 'a@example.com', 'x', 'OWNER')", user, tenant);

            // Issuance on days 0, 29 and 35 of a family with token expiries on days 7, 36 and 42.
            Instant start = now.minus(35, ChronoUnit.DAYS);
            UUID family = UUID.randomUUID();
            insertToken(scratch, tenant, user, family, start, start.plus(7, ChronoUnit.DAYS));
            insertToken(scratch, tenant, user, family, start.plus(29, ChronoUnit.DAYS),
                    start.plus(36, ChronoUnit.DAYS));
            insertToken(scratch, tenant, user, family, start.plus(35, ChronoUnit.DAYS),
                    start.plus(42, ChronoUnit.DAYS));
            // A consistent family and a single token family stay as V6 left them.
            UUID calm = UUID.randomUUID();
            insertToken(scratch, tenant, user, calm, now.minus(3, ChronoUnit.DAYS), now.plus(4, ChronoUnit.DAYS));
            insertToken(scratch, tenant, user, calm, now.minus(1, ChronoUnit.DAYS), now.plus(6, ChronoUnit.DAYS));

            flyway("6").migrate();
            // The defect V7 repairs: V6 alone leaves three different values inside the family.
            assertThat(expiries(scratch, family)).containsExactlyInAnyOrder(
                    Timestamp.from(start.plus(30, ChronoUnit.DAYS)), Timestamp.from(start.plus(36, ChronoUnit.DAYS)),
                    Timestamp.from(start.plus(42, ChronoUnit.DAYS)));
            List<Timestamp> calmBefore = expiries(scratch, calm);

            flyway(null).migrate();

            Timestamp familyEnd = Timestamp.from(start.plus(42, ChronoUnit.DAYS));
            assertThat(expiries(scratch, family)).hasSize(3).containsOnly(familyEnd);
            // Nothing shortened: the end is not before the latest token the session holds.
            assertThat(scratch.queryForObject(
                    "SELECT count(*) FROM refresh_token WHERE family_expires_at < expires_at", Integer.class)).isZero();
            assertThat(expiries(scratch, calm)).containsExactlyInAnyOrderElementsOf(calmBefore);
            assertThat(scratch.queryForObject("""
                    SELECT count(*) FROM (SELECT family_id FROM refresh_token GROUP BY family_id
                                          HAVING count(DISTINCT family_expires_at) > 1) f
                    """, Integer.class)).isZero();
            assertThatThrownBy(() -> insertTokenWithFamilyEnd(scratch, tenant, user, now))
                    .hasMessageContaining("refresh_token_family_expiry_ck");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void migrationsSixAndSevenDoNotChangeTheAnalyticsGrants() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT grantee FROM information_schema.role_table_grants
                 WHERE table_name = 'refresh_token' AND grantee IN ('analytics_reader', 'analytics_writer')
                """, String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.column_privileges
                 WHERE table_name = 'refresh_token' AND column_name = 'family_expires_at'
                   AND grantee IN ('analytics_reader', 'analytics_writer')
                """, Integer.class)).isZero();
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                .createSchemas(true).locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void insertToken(JdbcTemplate jdbc, UUID tenant, UUID user, UUID family, Instant issued,
            Instant expires) {
        jdbc.update("""
                INSERT INTO refresh_token (id, tenant_id, user_id, family_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenant, user, family, "%064x".formatted(Math.abs(UUID.randomUUID()
                        .getMostSignificantBits())), Timestamp.from(issued), Timestamp.from(expires));
    }

    private static void insertTokenWithFamilyEnd(JdbcTemplate jdbc, UUID tenant, UUID user, Instant now) {
        jdbc.update("""
                INSERT INTO refresh_token (id, tenant_id, user_id, family_id, token_hash, issued_at, expires_at,
                                           family_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenant, user, UUID.randomUUID(), "%064x".formatted(Math.abs(UUID.randomUUID()
                        .getMostSignificantBits())), Timestamp.from(now), Timestamp.from(now.plusSeconds(60)),
                Timestamp.from(now.plusSeconds(30)));
    }

    private static List<Timestamp> expiries(JdbcTemplate jdbc, UUID family) {
        return jdbc.queryForList("SELECT family_expires_at FROM refresh_token WHERE family_id = ?", Timestamp.class,
                family);
    }
}
