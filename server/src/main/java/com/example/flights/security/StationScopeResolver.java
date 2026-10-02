package com.example.flights.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.example.flights.config.SecurityProperties;

/**
 * Reads the caller's stations and display name from JWT claims.
 *
 * <p>Accepted claim shapes (claim name configurable, dotted paths allowed):
 * <pre>
 *   "stations": ["SYD", "SIN"]
 *   "stations": "SYD SIN"        or  "SYD,SIN"
 *   "ext": { "stations": [...] }    with stations-claim: ext.stations
 *   "stations": ["*"]               every station (values configurable)
 * </pre>
 * Unknown or malformed entries are ignored; a token without stations sees
 * nothing (deny by default).
 */
@Component
public class StationScopeResolver {

    private final SecurityProperties properties;

    public StationScopeResolver(SecurityProperties properties) {
        this.properties = properties;
    }

    public StationScope resolve(Jwt jwt) {
        if (jwt == null) {
            return StationScope.none();
        }

        var allValues = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        allValues.addAll(properties.allStationsValues());

        var stations = new TreeSet<String>();
        for (var value : values(claim(jwt.getClaims(), properties.stationsClaim()))) {
            var trimmed = value.trim();
            if (allValues.contains(trimmed)) {
                return StationScope.allStations();
            }
            var code = trimmed.toUpperCase(Locale.ROOT);
            if (StationScope.isStationCode(code)) {
                stations.add(code);
            }
        }
        return StationScope.of(stations);
    }

    public String displayName(Jwt jwt) {
        for (var claimName : List.of(properties.nameClaim(), "preferred_username", "sub")) {
            var value = claim(jwt.getClaims(), claimName);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return "Unknown user";
    }

    private static Object claim(Map<String, Object> claims, String path) {
        Object current = claims;
        for (var part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static List<String> values(Object claim) {
        var values = new ArrayList<String>();
        switch (claim) {
            case null -> { }
            case Collection<?> collection -> collection.forEach(item -> {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            });
            case String text -> {
                for (var part : text.split("[,\\s]+")) {
                    if (!part.isBlank()) {
                        values.add(part);
                    }
                }
            }
            default -> values.add(String.valueOf(claim));
        }
        return values;
    }
}
