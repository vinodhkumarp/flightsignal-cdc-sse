-- Station-based routing of notifications.
--
-- Every flight row records the full list of stations its journey touches
-- (route_stations), e.g. QF1 SYD -> SIN -> LHR = {SYD,SIN,LHR}. Every change
-- event records the stations it is relevant to (old and new route, so a
-- diversion reaches both the station losing and the station gaining the
-- flight). The API only delivers an event to users whose JWT station claims
-- overlap the event's stations.

-- 1. Flight route --------------------------------------------------------

ALTER TABLE public.flight_instance
    ADD COLUMN IF NOT EXISTS route_stations text[];

-- Backfill without firing the capture/metadata triggers: this is a schema
-- change, not a business change, and must not notify every browser.
ALTER TABLE public.flight_instance DISABLE TRIGGER capture_flight_change;
ALTER TABLE public.flight_instance DISABLE TRIGGER set_flight_update_metadata;

UPDATE public.flight_instance
SET route_stations = ARRAY[trim(origin_airport), trim(destination_airport)]::text[]
WHERE route_stations IS NULL OR cardinality(route_stations) = 0;

ALTER TABLE public.flight_instance ENABLE TRIGGER capture_flight_change;
ALTER TABLE public.flight_instance ENABLE TRIGGER set_flight_update_metadata;

-- Keeps route_stations normalised: upper-case, trimmed, no duplicates,
-- order preserved, and always containing origin and destination. Writers
-- that do not know about routes (older clients, simple SQL) get
-- {origin,destination} automatically.
CREATE OR REPLACE FUNCTION app_internal.normalize_route_stations()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_origin      text := upper(trim(NEW.origin_airport));
    v_destination text := upper(trim(NEW.destination_airport));
    v_route       text[];
BEGIN
    SELECT coalesce(array_agg(station ORDER BY first_position), '{}')
    INTO v_route
    FROM (
        SELECT upper(trim(station)) AS station, min(position) AS first_position
        FROM unnest(coalesce(NEW.route_stations, '{}'::text[]))
             WITH ORDINALITY AS item(station, position)
        WHERE station IS NOT NULL AND trim(station) <> ''
        GROUP BY upper(trim(station))
    ) stations;

    IF NOT (v_route @> ARRAY[v_origin, v_destination]) THEN
        v_route := ARRAY[v_origin, v_destination];
    END IF;

    NEW.route_stations := v_route;
    RETURN NEW;
END;
$$;

-- Fires before set_flight_update_metadata (BEFORE triggers run in name
-- order), so an UPDATE that changes nothing is still detected as a no-op.
DROP TRIGGER IF EXISTS normalize_route_stations ON public.flight_instance;

CREATE TRIGGER normalize_route_stations
BEFORE INSERT OR UPDATE ON public.flight_instance
FOR EACH ROW
EXECUTE FUNCTION app_internal.normalize_route_stations();

ALTER TABLE public.flight_instance
    ALTER COLUMN route_stations SET NOT NULL;

ALTER TABLE public.flight_instance
    DROP CONSTRAINT IF EXISTS flight_instance_route_stations_check;

ALTER TABLE public.flight_instance
    ADD CONSTRAINT flight_instance_route_stations_check
    CHECK (cardinality(route_stations) BETWEEN 2 AND 10);

CREATE INDEX IF NOT EXISTS flight_instance_route_stations_idx
    ON public.flight_instance USING gin (route_stations);

COMMENT ON COLUMN public.flight_instance.route_stations IS
  'All stations of the journey in order (e.g. {SYD,SIN,LHR}); drives station-scoped notification delivery.';

-- 2. Event stations -------------------------------------------------------

ALTER TABLE app_internal.flight_change_event
    ADD COLUMN IF NOT EXISTS stations text[] NOT NULL DEFAULT '{}';

UPDATE app_internal.flight_change_event event
SET stations = (
    SELECT coalesce(array_agg(DISTINCT station ORDER BY station), '{}')
    FROM unnest(ARRAY[
        trim(event.old_row ->> 'origin_airport'),
        trim(event.old_row ->> 'destination_airport'),
        trim(event.new_row ->> 'origin_airport'),
        trim(event.new_row ->> 'destination_airport')
    ]) AS station
    WHERE station IS NOT NULL
)
WHERE cardinality(event.stations) = 0;

CREATE INDEX IF NOT EXISTS flight_change_event_stations_idx
    ON app_internal.flight_change_event USING gin (stations);

-- 3. Capture trigger: record the stations of the old and new row ----------

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
