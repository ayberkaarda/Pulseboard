package io.pulseboard.core.support;

import io.pulseboard.core.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

/**
 * Deterministic interleaving for concurrency tests: a separate database session takes a row lock and keeps its
 * transaction open, so application transactions that need the row stop right after they reach it. The test
 * waits until the expected number of sessions is blocked, optionally changes data in the blocking session, and
 * then commits to release them.
 */
public final class LockBarrier implements AutoCloseable {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final long POLL_MILLIS = 20;

    private final Connection connection;

    private LockBarrier(Connection connection) {
        this.connection = connection;
    }

    /** Opens a session as the application role and locks one row with {@code FOR UPDATE}. */
    public static LockBarrier lockRow(String jdbcUrl, String table, UUID id) throws SQLException {
        if (!table.matches("[a-z_]+")) {
            throw new IllegalArgumentException("table must be a plain identifier");
        }
        Connection connection = DriverManager.getConnection(jdbcUrl, TestcontainersConfiguration.API_CORE_USER,
                TestcontainersConfiguration.API_CORE_PASSWORD);
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    connection.rollback();
                    connection.close();
                    throw new IllegalArgumentException("No row " + id + " in " + table);
                }
            }
        }
        return new LockBarrier(connection);
    }

    /** Runs a statement inside the still-open blocking transaction. */
    public void execute(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.execute();
        }
    }

    /** Blocks until at least {@code count} other sessions of this database wait on a heavyweight lock. */
    public void awaitWaitingSessions(int count) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*) FROM pg_stat_activity
                 WHERE datname = current_database() AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()
                """)) {
            while (true) {
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= count) {
                        return;
                    }
                }
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Timed out waiting for " + count + " blocked session(s)");
                }
                Thread.sleep(POLL_MILLIS);
            }
        }
    }

    /** Commits the blocking transaction, releasing the waiting sessions. */
    public void release() throws SQLException {
        connection.commit();
    }

    @Override
    public void close() throws SQLException {
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
        } finally {
            connection.close();
        }
    }
}
