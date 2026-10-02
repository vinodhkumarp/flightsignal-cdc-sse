package com.example.flights.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PostgreSQL LISTEN/NOTIFY listener settings ({@code app.listener.*}).
 *
 * @param pollTimeout         how long one wait for notifications blocks
 * @param healthCheckInterval how often the dedicated connection is probed
 *                            with {@code SELECT 1}, detecting half-open TCP
 *                            connections that would otherwise go silent
 * @param socketTimeout       socket read timeout for the dedicated connection
 * @param initialBackoff      first reconnect delay after a failure
 * @param maxBackoff          upper bound for the exponential reconnect delay
 * @param reconcileWindow     event IDs below the high-water mark re-checked
 *                            after a reconnect (out-of-order commits)
 * @param reconcilePageSize   page size used while catching up after an outage
 */
@ConfigurationProperties("app.listener")
public record ListenerProperties(
        @DefaultValue("5s") Duration pollTimeout,
        @DefaultValue("15s") Duration healthCheckInterval,
        @DefaultValue("30s") Duration socketTimeout,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("30s") Duration maxBackoff,
        @DefaultValue("500") int reconcileWindow,
        @DefaultValue("500") int reconcilePageSize) {
}
