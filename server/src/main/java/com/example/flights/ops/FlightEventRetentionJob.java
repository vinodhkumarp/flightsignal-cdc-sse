package com.example.flights.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.flights.config.RetentionProperties;
import com.example.flights.event.FlightEventRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Keeps {@code app_internal.flight_change_event} bounded by deleting events
 * older than the retention period in small batches. Safe to run on several
 * instances at once.
 */
@Component
public class FlightEventRetentionJob {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(FlightEventRetentionJob.class);

    private final FlightEventRepository repository;
    private final RetentionProperties properties;
    private final Counter purged;

    public FlightEventRetentionJob(
            FlightEventRepository repository,
            RetentionProperties properties,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.properties = properties;
        this.purged = Counter.builder("flightsignal.events.purged")
                .description("Change events deleted by the retention job")
                .register(meterRegistry);
    }

    @Scheduled(cron = "${app.events.retention.cron:0 17 3 * * *}")
    public void purgeExpiredEvents() {
        if (!properties.enabled()
                || properties.period().isZero()
                || properties.period().isNegative()) {
            return;
        }

        var batchSize = Math.max(1, properties.batchSize());
        var total = 0L;
        long deleted;
        do {
            deleted = repository.purgeOlderThan(properties.period(), batchSize);
            total += deleted;
        } while (deleted >= batchSize);

        purged.increment(total);
        if (total > 0) {
            LOGGER.info("Purged {} flight change events older than {}",
                    total, properties.period());
        }
    }
}
