-- FlightSignal continuous simulator.
--
-- Installs a small `sim` schema with helpers that drive realistic flight
-- lifecycles straight through SQL, bypassing the REST API, so every change is
-- captured by the database trigger exactly as an external writer would be.
--
-- Usage (see scripts/simulate.sh):
--   psql -f db/simulator/install.sql          -- once (idempotent)
--   CALL sim.run_cycle(3, 24);                -- one flight lifecycle
--   CALL sim.cleanup();                       -- remove all simulated data
--
-- Each cycle:
--   1. creates a future flight AND its synthetic passenger manifest in the
--      same transaction, so the manifest already exists when the
--      "flight added" notification reaches the browser;
--   2. plays a random disruption scenario (delays, gate changes, boarding,
--      cancellation, removal ...), committing each step separately so every
--      step is delivered as its own real-time event.
--
-- This schema is a development tool. It is deliberately not part of the
-- Flyway migrations and must never be installed in a real environment.

\set ON_ERROR_STOP on
SET client_min_messages = warning;

CREATE SCHEMA IF NOT EXISTS sim;

CREATE TABLE IF NOT EXISTS sim.simulated_flight (
    flight_id        uuid PRIMARY KEY,
    carrier_code     varchar(3) NOT NULL,
    flight_number    varchar(8) NOT NULL,
    service_date     date NOT NULL,
    origin_airport   char(3) NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- Random element of a text array.
CREATE OR REPLACE FUNCTION sim.pick(p_values text[])
RETURNS text
LANGUAGE sql
VOLATILE
AS $$
    SELECT p_values[1 + floor(random() * array_length(p_values, 1))::int];
$$;

-- Random integer in [p_min, p_max] rounded down to a multiple of p_step.
CREATE OR REPLACE FUNCTION sim.random_minutes(
    p_min integer,
    p_max integer,
    p_step integer DEFAULT 5)
RETURNS integer
LANGUAGE sql
VOLATILE
AS $$
    SELECT greatest(
        p_step,
        ((p_min + floor(random() * (p_max - p_min + 1)))::int / p_step) * p_step);
$$;

-- Human-readable label used in console output, e.g. "QF421 SYD→MEL".
CREATE OR REPLACE FUNCTION sim.label(p_flight_id uuid)
RETURNS text
LANGUAGE sql
STABLE
AS $$
    SELECT carrier_code || flight_number || ' '
           || trim(origin_airport) || '→' || trim(destination_airport)
    FROM public.flight_instance
    WHERE flight_id = p_flight_id;
$$;

-- Creates a SCHEDULED flight departing within the next three days.
CREATE OR REPLACE FUNCTION sim.create_flight()
RETURNS uuid
LANGUAGE plpgsql
AS $$
DECLARE
    v_route         record;
    v_carrier       text;
    v_number        text;
    v_service_date  date;
    v_local         timestamp;
    v_departure     timestamptz;
    v_flight_id     uuid;
    v_attempt       integer := 0;
BEGIN
    LOOP
        v_attempt := v_attempt + 1;

        SELECT *
        INTO v_route
        FROM (VALUES
            ('SYD', 'MEL', 'Australia/Sydney',    'Australia/Melbourne',  95),
            ('MEL', 'SYD', 'Australia/Melbourne', 'Australia/Sydney',     90),
            ('SYD', 'BNE', 'Australia/Sydney',    'Australia/Brisbane',   90),
            ('BNE', 'SYD', 'Australia/Brisbane',  'Australia/Sydney',     85),
            ('ADL', 'SYD', 'Australia/Adelaide',  'Australia/Sydney',    120),
            ('MEL', 'PER', 'Australia/Melbourne', 'Australia/Perth',     250),
            ('CBR', 'MEL', 'Australia/Sydney',    'Australia/Melbourne',  65),
            ('SYD', 'AKL', 'Australia/Sydney',    'Pacific/Auckland',    185),
            ('SYD', 'SIN', 'Australia/Sydney',    'Asia/Singapore',      495),
            ('PER', 'SIN', 'Australia/Perth',     'Asia/Singapore',      320),
            ('MEL', 'HKG', 'Australia/Melbourne', 'Asia/Hong_Kong',      560),
            ('SYD', 'NRT', 'Australia/Sydney',    'Asia/Tokyo',          585),
            ('SYD', 'HNL', 'Australia/Sydney',    'Pacific/Honolulu',    600),
            ('SYD', 'LAX', 'Australia/Sydney',    'America/Los_Angeles', 835),
            ('MEL', 'DXB', 'Australia/Melbourne', 'Asia/Dubai',          870)
        ) AS route(origin, destination, origin_tz, destination_tz, minutes)
        ORDER BY random()
        LIMIT 1;

        v_carrier := sim.pick(ARRAY['QF', 'VA', 'JQ', 'NZ', 'SQ', 'EK', 'CX', 'DL', 'ZL']);
        v_number := (100 + floor(random() * 8900))::int::text;
        v_service_date := (now() AT TIME ZONE v_route.origin_tz)::date
                          + floor(random() * 3)::int;
        v_local := v_service_date
                   + make_interval(
                       hours => 6 + floor(random() * 16)::int,
                       mins => (floor(random() * 12) * 5)::int);
        v_departure := v_local AT TIME ZONE v_route.origin_tz;

        IF v_departure < now() + interval '2 hours' THEN
            v_service_date := v_service_date + 1;
            v_departure := (v_local + interval '1 day') AT TIME ZONE v_route.origin_tz;
        END IF;

        INSERT INTO public.flight_instance (
            carrier_code,
            flight_number,
            service_date,
            origin_airport,
            destination_airport,
            origin_timezone,
            destination_timezone,
            scheduled_departure_utc,
            estimated_departure_utc,
            scheduled_arrival_utc,
            estimated_arrival_utc,
            status,
            gate
        )
        VALUES (
            v_carrier,
            v_number,
            v_service_date,
            v_route.origin,
            v_route.destination,
            v_route.origin_tz,
            v_route.destination_tz,
            v_departure,
            v_departure,
            v_departure + make_interval(mins => v_route.minutes),
            v_departure + make_interval(mins => v_route.minutes),
            'SCHEDULED',
            (1 + floor(random() * 60))::int::text
        )
        ON CONFLICT DO NOTHING
        RETURNING flight_id INTO v_flight_id;

        EXIT WHEN v_flight_id IS NOT NULL;

        IF v_attempt >= 20 THEN
            RAISE EXCEPTION 'sim.create_flight: could not find a free flight slot';
        END IF;
    END LOOP;

    INSERT INTO sim.simulated_flight (
        flight_id, carrier_code, flight_number, service_date, origin_airport)
    VALUES (v_flight_id, v_carrier, v_number, v_service_date, v_route.origin);

    RETURN v_flight_id;
END;
$$;

-- Generates a synthetic manifest for one flight. Returns the passenger count.
-- All data is fictitious: e-mail addresses use the reserved example.test
-- domain and phone numbers are random.
CREATE OR REPLACE FUNCTION sim.seed_passengers(
    p_flight_id uuid,
    p_count integer)
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
    v_business  integer := greatest(1, ceil(p_count * 0.08)::int);
    v_premium   integer := greatest(2, ceil(p_count * 0.20)::int);
    v_inserted  integer;
BEGIN
    INSERT INTO public.flight_passenger (
        flight_id,
        carrier_code,
        flight_number,
        service_date,
        origin_airport,
        destination_airport,
        manifest_sequence,
        booking_reference,
        given_name,
        family_name,
        email,
        phone_number,
        preferred_contact_method,
        seat_number,
        cabin_class,
        loyalty_tier,
        special_assistance,
        contact_status
    )
    SELECT
        flight.flight_id,
        flight.carrier_code,
        flight.flight_number,
        flight.service_date,
        flight.origin_airport,
        flight.destination_airport,
        pax.n,
        upper(substr(md5(random()::text || pax.n), 1, 6)),
        pax.given_name,
        pax.family_name,
        lower(pax.given_name || '.' || pax.family_name || pax.n || '@example.test'),
        '+614' || lpad(floor(random() * 100000000)::bigint::text, 8, '0'),
        sim.pick(ARRAY['EMAIL', 'EMAIL', 'SMS', 'SMS', 'PHONE']),
        CASE
            WHEN pax.n <= v_business THEN
                (1 + (pax.n - 1) / 4)::text
                || substr('ACDF', 1 + (pax.n - 1) % 4, 1)
            WHEN pax.n <= v_premium THEN
                (10 + (pax.n - v_business - 1) / 6)::text
                || substr('ABCDEF', 1 + (pax.n - v_business - 1) % 6, 1)
            ELSE
                (20 + (pax.n - v_premium - 1) / 6)::text
                || substr('ABCDEF', 1 + (pax.n - v_premium - 1) % 6, 1)
        END,
        CASE
            WHEN pax.n <= v_business THEN 'BUSINESS'
            WHEN pax.n <= v_premium THEN 'PREMIUM_ECONOMY'
            ELSE 'ECONOMY'
        END,
        sim.pick(ARRAY['NONE', 'NONE', 'NONE', 'SILVER', 'GOLD', 'PLATINUM']),
        random() < 0.06,
        'NOT_CONTACTED'
    FROM public.flight_instance flight
    CROSS JOIN LATERAL (
        SELECT
            n,
            sim.pick(ARRAY[
                'Amelia', 'Noah', 'Olivia', 'Liam', 'Isla', 'Jack', 'Mia',
                'Leo', 'Ava', 'Henry', 'Grace', 'Ethan', 'Sophie', 'Lucas',
                'Chloe', 'Oscar', 'Zoe', 'James', 'Ruby', 'Thomas', 'Aarav',
                'Priya', 'Wei', 'Mei', 'Hiroshi', 'Yuki', 'Mateo', 'Sofia',
                'Omar', 'Layla', 'Arjun', 'Ananya', 'Minh', 'Linh', 'Kai',
                'Aroha', 'Tane', 'Elena', 'Luca', 'Fatima'
            ]) AS given_name,
            sim.pick(ARRAY[
                'Nguyen', 'Smith', 'Patel', 'Williams', 'Chen', 'Brown',
                'Singh', 'Wilson', 'Kim', 'Taylor', 'Martin', 'Anderson',
                'Thomas', 'White', 'Harris', 'Clark', 'Walker', 'Hall',
                'Young', 'King', 'Tanaka', 'Sato', 'Wong', 'Li', 'Garcia',
                'Rossi', 'Kaur', 'Sharma', 'Tran', 'Pham', 'Ngata', 'Parata',
                'Hussain', 'Ahmed', 'Murphy', 'Kelly', 'Lopez', 'Silva',
                'Novak', 'Ivanova'
            ]) AS family_name
        FROM generate_series(1, p_count) AS n
    ) AS pax
    WHERE flight.flight_id = p_flight_id
    ON CONFLICT (flight_id, manifest_sequence) DO NOTHING;

    GET DIAGNOSTICS v_inserted = ROW_COUNT;

    -- 'NONE' keeps the pick distribution simple; store it as NULL.
    UPDATE public.flight_passenger
    SET loyalty_tier = NULL
    WHERE flight_id = p_flight_id
      AND loyalty_tier = 'NONE';

    RETURN v_inserted;
END;
$$;

-- Applies one scenario step and returns a console message.
CREATE OR REPLACE FUNCTION sim.apply_step(p_flight_id uuid, p_step text)
RETURNS text
LANGUAGE plpgsql
AS $$
DECLARE
    v_label    text := sim.label(p_flight_id);
    v_minutes  integer;
    v_old_gate text;
    v_new_gate text;
BEGIN
    IF v_label IS NULL THEN
        RETURN NULL;
    END IF;

    CASE p_step
        WHEN 'delay' THEN
            v_minutes := sim.random_minutes(10, 60);
            UPDATE public.flight_instance
            SET estimated_departure_utc =
                    coalesce(estimated_departure_utc, scheduled_departure_utc)
                    + make_interval(mins => v_minutes),
                estimated_arrival_utc =
                    coalesce(estimated_arrival_utc, scheduled_arrival_utc)
                    + make_interval(mins => v_minutes),
                status = 'DELAYED'
            WHERE flight_id = p_flight_id;
            RETURN format('%s delayed by %s min', v_label, v_minutes);

        WHEN 'earlier' THEN
            v_minutes := sim.random_minutes(10, 25);
            UPDATE public.flight_instance
            SET estimated_departure_utc =
                    coalesce(estimated_departure_utc, scheduled_departure_utc)
                    - make_interval(mins => v_minutes),
                estimated_arrival_utc =
                    coalesce(estimated_arrival_utc, scheduled_arrival_utc)
                    - make_interval(mins => v_minutes)
            WHERE flight_id = p_flight_id;
            RETURN format('%s departs %s min earlier', v_label, v_minutes);

        WHEN 'gate' THEN
            SELECT gate INTO v_old_gate
            FROM public.flight_instance
            WHERE flight_id = p_flight_id;

            LOOP
                v_new_gate := (1 + floor(random() * 60))::int::text;
                EXIT WHEN v_new_gate IS DISTINCT FROM v_old_gate;
            END LOOP;

            UPDATE public.flight_instance
            SET gate = v_new_gate
            WHERE flight_id = p_flight_id;
            RETURN format('%s gate %s → %s', v_label, coalesce(v_old_gate, '-'), v_new_gate);

        WHEN 'boarding' THEN
            UPDATE public.flight_instance
            SET status = 'BOARDING'
            WHERE flight_id = p_flight_id;
            RETURN format('%s boarding', v_label);

        WHEN 'departed' THEN
            UPDATE public.flight_instance
            SET status = 'DEPARTED',
                actual_departure_utc =
                    coalesce(estimated_departure_utc, scheduled_departure_utc)
            WHERE flight_id = p_flight_id;
            RETURN format('%s departed', v_label);

        WHEN 'arrived' THEN
            UPDATE public.flight_instance
            SET status = 'ARRIVED',
                actual_arrival_utc =
                    coalesce(estimated_arrival_utc, scheduled_arrival_utc)
            WHERE flight_id = p_flight_id;
            RETURN format('%s arrived', v_label);

        WHEN 'cancel' THEN
            UPDATE public.flight_instance
            SET status = 'CANCELLED'
            WHERE flight_id = p_flight_id;
            RETURN format('%s cancelled', v_label);

        WHEN 'remove' THEN
            DELETE FROM public.flight_instance
            WHERE flight_id = p_flight_id;
            RETURN format('%s removed from the schedule', v_label);

        ELSE
            RAISE EXCEPTION 'sim.apply_step: unknown step %', p_step;
    END CASE;
END;
$$;

-- Runs one complete flight lifecycle. Each step commits on its own so the
-- trigger emits one real-time event per step.
CREATE OR REPLACE PROCEDURE sim.run_cycle(
    p_step_seconds numeric DEFAULT 3,
    p_passengers integer DEFAULT 24)
LANGUAGE plpgsql
AS $$
DECLARE
    v_flight_id  uuid;
    v_count      integer;
    v_steps      text[];
    v_step       text;
    v_message    text;
BEGIN
    -- 1. Flight + manifest in ONE transaction: the passenger list is
    --    committed together with (and therefore before anyone can observe)
    --    the "flight added" event.
    v_flight_id := sim.create_flight();
    v_count := sim.seed_passengers(
        v_flight_id,
        greatest(4, p_passengers + floor(random() * (p_passengers / 2 + 1))::int));
    RAISE NOTICE '[sim] % added with % passengers', sim.label(v_flight_id), v_count;
    COMMIT;

    -- 2. Random disruption scenario.
    v_steps := CASE 1 + floor(random() * 6)::int
        WHEN 1 THEN ARRAY['delay', 'delay', 'boarding', 'departed', 'arrived']
        WHEN 2 THEN ARRAY['gate', 'boarding', 'departed', 'arrived']
        WHEN 3 THEN ARRAY['delay', 'gate', 'cancel']
        WHEN 4 THEN ARRAY['earlier', 'gate', 'boarding', 'departed']
        WHEN 5 THEN ARRAY['gate', 'delay', 'remove']
        ELSE ARRAY['delay', 'gate', 'boarding', 'departed', 'arrived']
    END;

    FOREACH v_step IN ARRAY v_steps LOOP
        PERFORM pg_sleep(p_step_seconds);
        v_message := sim.apply_step(v_flight_id, v_step);
        EXIT WHEN v_message IS NULL;

        RAISE NOTICE '[sim] %', v_message;
        COMMIT;
    END LOOP;
END;
$$;

-- Removes every simulated flight, its passengers, and its change events.
-- Change events are deleted in the same transaction, so the listener finds
-- nothing to broadcast and the UI is not flooded with "removed" notices.
CREATE OR REPLACE PROCEDURE sim.cleanup()
LANGUAGE plpgsql
AS $$
DECLARE
    v_flights    integer;
    v_passengers integer;
BEGIN
    DELETE FROM public.flight_passenger passenger
    USING sim.simulated_flight simulated
    WHERE passenger.flight_id = simulated.flight_id
       OR (passenger.flight_id IS NULL
           AND passenger.carrier_code = simulated.carrier_code
           AND passenger.flight_number = simulated.flight_number
           AND passenger.service_date = simulated.service_date
           AND passenger.origin_airport = simulated.origin_airport);
    GET DIAGNOSTICS v_passengers = ROW_COUNT;

    DELETE FROM public.flight_instance flight
    USING sim.simulated_flight simulated
    WHERE flight.flight_id = simulated.flight_id;
    GET DIAGNOSTICS v_flights = ROW_COUNT;

    DELETE FROM app_internal.flight_change_event event
    USING sim.simulated_flight simulated
    WHERE event.flight_id = simulated.flight_id;

    TRUNCATE sim.simulated_flight;

    RAISE NOTICE '[sim] removed % flights and % passengers', v_flights, v_passengers;
END;
$$;
