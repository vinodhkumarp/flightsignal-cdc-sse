-- Onward-leg alerts for multi-leg flights.
--
-- A multi-leg flight is stored one row per leg with the same carrier and
-- flight number (QF1 SYD -> SIN, QF1 SIN -> LHR). Normally each leg's
-- changes reach only that leg's stations. When an earlier leg is delayed or
-- cancelled, passengers connecting onto later legs are affected, so the
-- change event also carries the stations of the onward legs.
--
-- Onward legs are found by following the chain: same carrier and flight
-- number, departing from this leg's destination within 24 hours after this
-- leg's scheduled arrival (so tomorrow's QF1 from SYD is never included).

CREATE INDEX IF NOT EXISTS flight_instance_leg_chain_idx
    ON public.flight_instance (carrier_code, flight_number, origin_airport, scheduled_departure_utc);

CREATE OR REPLACE FUNCTION app_internal.onward_leg_stations(
    p_carrier text,
    p_flight_number text,
    p_destination text,
    p_scheduled_arrival timestamptz)
RETURNS text[]
LANGUAGE sql
STABLE
AS $$
    WITH RECURSIVE chain (destination, scheduled_arrival, stations, depth) AS (
        SELECT p_destination, p_scheduled_arrival, '{}'::text[], 0
        UNION ALL
        SELECT trim(leg.destination_airport)::text,
               leg.scheduled_arrival_utc,
               leg.route_stations,
               chain.depth + 1
        FROM chain
        JOIN public.flight_instance leg
          ON leg.carrier_code = p_carrier
         AND leg.flight_number = p_flight_number
         AND trim(leg.origin_airport) = chain.destination
         AND leg.scheduled_departure_utc > chain.scheduled_arrival
         AND leg.scheduled_departure_utc <= chain.scheduled_arrival + interval '24 hours'
        WHERE chain.depth < 8
    )
    SELECT coalesce(array_agg(DISTINCT station ORDER BY station), '{}')
    FROM chain, unnest(chain.stations) AS station;
$$;

-- TRUE when the update makes the leg later or cancels/delays it.
CREATE OR REPLACE FUNCTION app_internal.affects_onward_legs(
    p_old public.flight_instance,
    p_new public.flight_instance)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT (p_new.status = 'CANCELLED' AND p_old.status IS DISTINCT FROM 'CANCELLED')
        OR (p_new.status = 'DELAYED' AND p_old.status IS DISTINCT FROM 'DELAYED')
        OR coalesce(p_new.estimated_arrival_utc, p_new.scheduled_arrival_utc)
           > coalesce(p_old.estimated_arrival_utc, p_old.scheduled_arrival_utc)
        OR coalesce(p_new.estimated_departure_utc, p_new.scheduled_departure_utc)
           > coalesce(p_old.estimated_departure_utc, p_old.scheduled_departure_utc);
$$;

CREATE OR REPLACE FUNCTION app_internal.capture_flight_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_event_id bigint;
    v_flight_id uuid;
    v_candidates text[] := '{}';
    v_stations text[];
BEGIN
    IF TG_OP = 'UPDATE' AND NEW IS NOT DISTINCT FROM OLD THEN
        RETURN NEW;
    END IF;

    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        v_candidates := v_candidates
            || coalesce(OLD.route_stations, '{}'::text[])
            || ARRAY[trim(OLD.origin_airport), trim(OLD.destination_airport)]::text[];
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        v_candidates := v_candidates
            || coalesce(NEW.route_stations, '{}'::text[])
            || ARRAY[trim(NEW.origin_airport), trim(NEW.destination_airport)]::text[];
    END IF;

    -- A delay or cancellation can break connections onto later legs of the
    -- same flight, so their stations are alerted too (e.g. LHR hears about
    -- a delayed QF1 SYD -> SIN). Other changes, such as a gate change, stay
    -- with the leg's own stations.
    IF TG_OP = 'UPDATE' AND app_internal.affects_onward_legs(OLD, NEW) THEN
        v_candidates := v_candidates || app_internal.onward_leg_stations(
            NEW.carrier_code,
            NEW.flight_number,
            trim(NEW.destination_airport),
            NEW.scheduled_arrival_utc);
    END IF;

    SELECT coalesce(array_agg(DISTINCT station ORDER BY station), '{}')
    INTO v_stations
    FROM unnest(v_candidates) AS station
    WHERE station IS NOT NULL AND station <> '';

    v_flight_id := CASE
      WHEN TG_OP = 'DELETE' THEN OLD.flight_id
      ELSE NEW.flight_id
    END;

    INSERT INTO app_internal.flight_change_event (
        operation,
        flight_id,
        old_row,
        new_row,
        stations
    )
    VALUES (
        TG_OP,
        v_flight_id,
        CASE WHEN TG_OP IN ('UPDATE', 'DELETE') THEN to_jsonb(OLD) END,
        CASE WHEN TG_OP IN ('INSERT', 'UPDATE') THEN to_jsonb(NEW) END,
        v_stations
    )
    RETURNING event_id INTO v_event_id;

    PERFORM pg_notify('flight_changes', v_event_id::text);

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;
