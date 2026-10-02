package com.example.flights.event;

public record FieldChange(
        String field,
        Object previousValue,
        Object newValue) {
}

