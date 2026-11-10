package io.pulseboard.core;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers setup: PostgreSQL ({@link PostgresTestcontainersConfiguration}) and Redis.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresTestcontainersConfiguration.class)
public class TestcontainersConfiguration {

    public static final String DATABASE = PostgresTestcontainersConfiguration.DATABASE;
    public static final String API_CORE_USER = PostgresTestcontainersConfiguration.API_CORE_USER;
    public static final String API_CORE_PASSWORD = PostgresTestcontainersConfiguration.API_CORE_PASSWORD;
    public static final String ANALYTICS_READER_USER = PostgresTestcontainersConfiguration.ANALYTICS_READER_USER;
    public static final String ANALYTICS_READER_PASSWORD =
            PostgresTestcontainersConfiguration.ANALYTICS_READER_PASSWORD;
    public static final String ANALYTICS_WRITER_PASSWORD =
            PostgresTestcontainersConfiguration.ANALYTICS_WRITER_PASSWORD;

    static final String REDIS_IMAGE = "redis:8.10.3-alpine";
    static final int REDIS_PORT = 6379;

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(REDIS_PORT);
    }
}
