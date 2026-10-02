package com.example.flights.event;

import java.time.Instant;

public record DelayDetails(
        long additionalDelayMinutes,
        long totalDelayMinutes,
        Instant previousDepartureUtc,
        Instant newDepartureUtc) {
}

