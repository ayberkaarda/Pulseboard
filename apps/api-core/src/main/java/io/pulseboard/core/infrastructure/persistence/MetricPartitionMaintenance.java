package io.pulseboard.core.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps monthly {@code metric_point} partitions ahead of time by calling the Flyway-managed
 * {@code ensure_metric_partitions(months_ahead)} function once at startup and then daily.
 */
@Component
public class MetricPartitionMaintenance {

    private static final Logger LOG = LoggerFactory.getLogger(MetricPartitionMaintenance.class);

    private final JdbcTemplate jdbcTemplate;
    private final int monthsAhead;

    public MetricPartitionMaintenance(JdbcTemplate jdbcTemplate,
            @Value("${pulseboard.partitions.months-ahead}") int monthsAhead) {
        if (monthsAhead < 1) {
            throw new IllegalArgumentException("pulseboard.partitions.months-ahead must be at least 1");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.monthsAhead = monthsAhead;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensurePartitionsOnStartup() {
        ensurePartitions();
    }

    @Scheduled(cron = "${pulseboard.partitions.cron}", zone = "UTC")
    public void ensurePartitionsDaily() {
        ensurePartitions();
    }

    /**
     * Creates any missing partitions.
     *
     * @return number of partitions created by this call
     */
    public int ensurePartitions() {
        Integer created = jdbcTemplate.queryForObject("SELECT ensure_metric_partitions(?)", Integer.class,
                monthsAhead);
        int count = created == null ? 0 : created;
        LOG.info("metric_point partitions ensured for {} month(s) ahead; {} created", monthsAhead, count);
        return count;
    }
}
