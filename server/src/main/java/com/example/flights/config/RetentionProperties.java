package com.example.flights.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Change-event retention ({@code app.events.retention.*}).
 *
 * @param enabled   whether the scheduled purge runs
 * @param period    events older than this are deleted
 * @param cron      when the purge runs (server time zone)
 * @param batchSize rows deleted per statement
 */
@ConfigurationProperties("app.events.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30d") Duration period,
        @DefaultValue("0 17 3 * * *") String cron,
        @DefaultValue("5000") int batchSize) {
}
