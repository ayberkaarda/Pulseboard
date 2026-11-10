package io.pulseboard.core;

import java.nio.file.Path;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL bootstrapped exactly like the Compose stack: the real {@code infra/postgres/init} script runs as the
 * superuser and creates the {@code api_core}, {@code analytics_reader} and {@code analytics_writer} roles. The
 * application then connects as the unprivileged {@code api_core} role, so Flyway migrations and grants are
 * exercised with production privileges. Passwords are random per test JVM.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

    public static final String DATABASE = "pulseboard";
    public static final String API_CORE_USER = "api_core";
    public static final String API_CORE_PASSWORD = randomPassword();
    public static final String ANALYTICS_READER_USER = "analytics_reader";
    public static final String ANALYTICS_READER_PASSWORD = randomPassword();
    public static final String ANALYTICS_WRITER_PASSWORD = randomPassword();

    private static final String INIT_SCRIPT = "01-pulseboard-roles-and-database.sql";
    private static final Path INIT_SCRIPT_PATH = Path.of("..", "..", "infra", "postgres", "init", INIT_SCRIPT);

    @Bean
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"))
                .withDatabaseName(DATABASE)
                .withUsername("postgres")
                .withPassword(randomPassword())
                .withEnv("PULSEBOARD_DB_NAME", DATABASE)
                .withEnv("API_CORE_DB_PASSWORD", API_CORE_PASSWORD)
                .withEnv("ANALYTICS_READER_DB_PASSWORD", ANALYTICS_READER_PASSWORD)
                .withEnv("ANALYTICS_WRITER_DB_PASSWORD", ANALYTICS_WRITER_PASSWORD)
                .withCopyFileToContainer(MountableFile.forHostPath(INIT_SCRIPT_PATH),
                        "/docker-entrypoint-initdb.d/" + INIT_SCRIPT);
    }

    @Bean
    DynamicPropertyRegistrar postgresProperties(PostgreSQLContainer postgresContainer) {
        return registry -> {
            registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
            registry.add("spring.datasource.username", () -> API_CORE_USER);
            registry.add("spring.datasource.password", () -> API_CORE_PASSWORD);
        };
    }

    public static String randomPassword() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
