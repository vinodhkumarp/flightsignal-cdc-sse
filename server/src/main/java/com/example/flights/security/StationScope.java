package com.example.flights.security;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.example.flights.api.ApiException;

/**
 * The stations whose flights a caller may see, derived from their JWT.
 *
 * @param all      {@code true} for global roles (head office, network control)
 * @param stations IATA station codes, e.g. {@code [SIN, SYD]}; ignored when
 *                 {@code all} is set
 */
public record StationScope(boolean all, Set<String> stations) {

    private static final Pattern STATION = Pattern.compile("[A-Z]{3}");
    private static final StationScope ALL = new StationScope(true, Set.of());
    private static final StationScope NONE = new StationScope(false, Set.of());

    public StationScope {
        stations = all ? Set.of() : Collections.unmodifiableSortedSet(new TreeSet<>(stations));
    }

    public static StationScope allStations() {
        return ALL;
    }

    public static StationScope none() {
        return NONE;
    }

    public static StationScope of(Collection<String> stations) {
        return new StationScope(false, Set.copyOf(stations));
    }

    /** {@code true} when the caller may see something touching these stations. */
    public boolean permits(Collection<String> eventStations) {
        if (all) {
            return true;
        }
        if (eventStations == null) {
            return false;
        }
        for (var station : eventStations) {
            if (stations.contains(station)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return !all && stations.isEmpty();
    }

    /**
     * Narrows the scope to stations the caller asked for (for example a
     * station filter in the UI). Asking for a station outside the caller's
     * own scope is rejected with 403.
     *
     * @param requested comma separated codes, or {@code null}/blank for no narrowing
     */
    public StationScope narrowTo(String requested) {
        var codes = parse(requested);
        if (codes.isEmpty()) {
            return this;
        }
        if (!all && !stations.containsAll(codes)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "You do not have access to station(s) "
                            + String.join(", ", difference(codes)) + ".");
        }
        return of(codes);
    }

    /** Comma separated codes for SQL {@code string_to_array(:stations, ',')}. */
    public String sqlList() {
        return String.join(",", stations);
    }

    static Set<String> parse(String requested) {
        var codes = new TreeSet<String>();
        if (requested == null || requested.isBlank()) {
            return codes;
        }
        for (var raw : requested.split("[,\\s]+")) {
            if (raw.isBlank()) {
                continue;
            }
            var code = raw.trim().toUpperCase(Locale.ROOT);
            if (!STATION.matcher(code).matches()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "stations must be three-letter IATA codes.");
            }
            codes.add(code);
        }
        return codes;
    }

    static boolean isStationCode(String value) {
        return value != null && STATION.matcher(value).matches();
    }

    private Set<String> difference(Set<String> requested) {
        var missing = new TreeSet<>(requested);
        missing.removeAll(stations);
        return missing;
    }
}
