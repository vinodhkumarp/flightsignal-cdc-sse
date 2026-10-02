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
--      "flight added" notification reaches the browser. Multi-leg flights
--      (e.g. QF1 SYD -> SIN -> LHR) are created as one row per leg sharing
--      the flight number; through passengers appear on both legs with the
--      same booking reference;
--   2. plays a random disruption scenario (delays, gate changes, boarding,
--      cancellation, removal ...) on one leg, committing each step
--      separately so every step is delivered as its own real-time event.
--      A change to the SIN -> LHR leg reaches SIN and LHR users only.
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

-- Human-readable label used in console output, e.g. "QF1 SIN→LHR".
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

-- Older installs had a single-row create_flight(); journeys replace it.
DROP FUNCTION IF EXISTS sim.create_flight();

-- Inserts one SCHEDULED leg. Returns NULL when the slot is already taken.
CREATE OR REPLACE FUNCTION sim.insert_leg(
    p_carrier text,
    p_number text,
    p_origin text,
    p_destination text,
    p_origin_tz text,
    p_destination_tz text,
    p_departure timestamptz,
    p_minutes integer)
RETURNS uuid
LANGUAGE plpgsql
AS $$
DECLARE
    v_flight_id uuid;
BEGIN
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
        p_carrier,
        p_number,
        (p_departure AT TIME ZONE p_origin_tz)::date,
        p_origin,
        p_destination,
        p_origin_tz,
        p_destination_tz,
        p_departure,
        p_departure,
        p_departure + make_interval(mins => p_minutes),
        p_departure + make_interval(mins => p_minutes),
        'SCHEDULED',
        (1 + floor(random() * 60))::int::text
    )
    ON CONFLICT DO NOTHING
    RETURNING flight_id INTO v_flight_id;

    IF v_flight_id IS NOT NULL THEN
        INSERT INTO sim.simulated_flight (
            flight_id, carrier_code, flight_number, service_date, origin_airport)
        SELECT flight_id, carrier_code, flight_number, service_date, origin_airport
        FROM public.flight_instance
        WHERE flight_id = v_flight_id;
    END IF;

    RETURN v_flight_id;
END;
$$;

-- Creates a flight departing within the next three days: one leg for a
-- direct route, two legs (same flight number, 90 minutes on the ground) for a
-- route with a stop. Returns the leg IDs in order.
CREATE OR REPLACE FUNCTION sim.create_journey()
RETURNS uuid[]
LANGUAGE plpgsql
AS $$
DECLARE
    v_route      record;
    v_carrier    text;
    v_number     text;
    v_local      timestamp;
    v_departure  timestamptz;
    v_first      uuid;
    v_second     uuid;
    v_attempt    integer := 0;
BEGIN
    LOOP
        v_attempt := v_attempt + 1;

        SELECT *
        INTO v_route
        FROM (VALUES
            -- origin, origin tz, stop, stop tz, destination, destination tz, leg 1 min, leg 2 min
            ('SYD', 'Australia/Sydney',    NULL,  NULL,             'MEL', 'Australia/Melbourne',  95,    0),
            ('MEL', 'Australia/Melbourne', NULL,  NULL,             'SYD', 'Australia/Sydney',     90,    0),
            ('SYD', 'Australia/Sydney',    NULL,  NULL,             'BNE', 'Australia/Brisbane',   90,    0),
            ('BNE', 'Australia/Brisbane',  NULL,  NULL,             'SYD', 'Australia/Sydney',     85,    0),
            ('ADL', 'Australia/Adelaide',  NULL,  NULL,             'SYD', 'Australia/Sydney',    120,    0),
            ('MEL', 'Australia/Melbourne', NULL,  NULL,             'PER', 'Australia/Perth',     250,    0),
            ('CBR', 'Australia/Sydney',    NULL,  NULL,             'MEL', 'Australia/Melbourne',  65,    0),
            ('SYD', 'Australia/Sydney',    NULL,  NULL,             'AKL', 'Pacific/Auckland',    185,    0),
            ('SYD', 'Australia/Sydney',    NULL,  NULL,             'SIN', 'Asia/Singapore',      495,    0),
            ('PER', 'Australia/Perth',     NULL,  NULL,             'SIN', 'Asia/Singapore',      320,    0),
            ('MEL', 'Australia/Melbourne', NULL,  NULL,             'HKG', 'Asia/Hong_Kong',      560,    0),
            ('SYD', 'Australia/Sydney',    NULL,  NULL,             'NRT', 'Asia/Tokyo',          585,    0),
            ('SYD', 'Australia/Sydney',    NULL,  NULL,             'LAX', 'America/Los_Angeles', 835,    0),
            -- Multi-leg flights: one row per leg.
            ('SYD', 'Australia/Sydney',    'SIN', 'Asia/Singapore', 'LHR', 'Europe/London',       495,  865),
            ('MEL', 'Australia/Melbourne', 'DXB', 'Asia/Dubai',     'LHR', 'Europe/London',       870,  460),
            ('SYD', 'Australia/Sydney',    'AKL', 'Pacific/Auckland', 'JFK', 'America/New_York',  185, 1010),
            ('BNE', 'Australia/Brisbane',  'HNL', 'Pacific/Honolulu', 'YVR', 'America/Vancouver', 600,  360)
        ) AS route(origin, origin_tz, stop, stop_tz, destination, destination_tz, leg1_minutes, leg2_minutes)
        ORDER BY random()
        LIMIT 1;

        v_carrier := sim.pick(ARRAY['QF', 'VA', 'JQ', 'NZ', 'SQ', 'EK', 'CX', 'DL', 'ZL']);
        v_number := (100 + floor(random() * 8900))::int::text;
        v_local := ((now() AT TIME ZONE v_route.origin_tz)::date + floor(random() * 3)::int)
                   + make_interval(
                       hours => 6 + floor(random() * 16)::int,
                       mins => (floor(random() * 12) * 5)::int);
        v_departure := v_local AT TIME ZONE v_route.origin_tz;
        IF v_departure < now() + interval '2 hours' THEN
            v_departure := v_departure + interval '1 day';
        END IF;

        IF v_route.stop IS NULL THEN
            v_first := sim.insert_leg(
                v_carrier, v_number,
                v_route.origin, v_route.destination,
                v_route.origin_tz, v_route.destination_tz,
                v_departure, v_route.leg1_minutes);
            IF v_first IS NOT NULL THEN
                RETURN ARRAY[v_first];
            END IF;
        ELSE
            v_first := sim.insert_leg(
                v_carrier, v_number,
                v_route.origin, v_route.stop,
                v_route.origin_tz, v_route.stop_tz,
                v_departure, v_route.leg1_minutes);
            v_second := sim.insert_leg(
                v_carrier, v_number,
                v_route.stop, v_route.destination,
                v_route.stop_tz, v_route.destination_tz,
                v_departure + make_interval(mins => v_route.leg1_minutes + 90),
                v_route.leg2_minutes);
            IF v_first IS NOT NULL AND v_second IS NOT NULL THEN
                RETURN ARRAY[v_first, v_second];
            END IF;
            -- Partial insert: the surrounding transaction is rolled back by
            -- the caller's exception, so just try another flight number.
            RAISE EXCEPTION USING ERRCODE = 'unique_violation', MESSAGE = 'sim: leg slot taken';
        END IF;

        IF v_attempt >= 20 THEN
            RAISE EXCEPTION 'sim.create_journey: could not find a free flight slot';
        END IF;
    END LOOP;
END;
$$;

-- Generates a synthetic manifest for one flight. Returns the passenger count.
-- All data is fictitious: e-mail addresses use the reserved example.test
-- domain and phone numbers are random.
DROP FUNCTION IF EXISTS sim.seed_passengers(uuid, integer);

CREATE OR REPLACE FUNCTION sim.seed_passengers(
    p_flight_id uuid,
    p_count integer,
    p_first_sequence integer DEFAULT 1)
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
        FROM generate_series(p_first_sequence, p_first_sequence + p_count - 1) AS n
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

-- Copies roughly 60% of the first leg's passengers onto the second leg as
-- through passengers (same name, booking reference, seat, and contact
-- details). Returns the number copied.
CREATE OR REPLACE FUNCTION sim.copy_through_passengers(
    p_from_flight_id uuid,
    p_to_flight_id uuid)
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
    v_copied integer;
BEGIN
    INSERT INTO public.flight_passenger (
        flight_id, carrier_code, flight_number, service_date,
        origin_airport, destination_airport, manifest_sequence,
        booking_reference, given_name, family_name, email, phone_number,
        preferred_contact_method, seat_number, cabin_class, loyalty_tier,
        special_assistance, contact_status
    )
    SELECT
        leg.flight_id, leg.carrier_code, leg.flight_number, leg.service_date,
        leg.origin_airport, leg.destination_airport,
        row_number() OVER (ORDER BY passenger.manifest_sequence),
        passenger.booking_reference, passenger.given_name, passenger.family_name,
        passenger.email, passenger.phone_number, passenger.preferred_contact_method,
        passenger.seat_number, passenger.cabin_class, passenger.loyalty_tier,
        passenger.special_assistance, 'NOT_CONTACTED'
    FROM public.flight_passenger passenger
    CROSS JOIN public.flight_instance leg
    WHERE passenger.flight_id = p_from_flight_id
      AND leg.flight_id = p_to_flight_id
      AND random() < 0.6;

    GET DIAGNOSTICS v_copied = ROW_COUNT;
    RETURN v_copied;
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
    v_legs       uuid[];
    v_target     uuid;
    v_count      integer;
    v_through    integer;
    v_attempt    integer := 0;
    v_steps      text[];
    v_step       text;
    v_message    text;
BEGIN
    -- 1. Flight legs + manifests in ONE transaction: the passenger lists are
    --    committed together with (and therefore before anyone can observe)
    --    the "flight added" events.
    LOOP
        v_attempt := v_attempt + 1;
        BEGIN
            v_legs := sim.create_journey();
            EXIT;
        EXCEPTION WHEN unique_violation THEN
            IF v_attempt >= 20 THEN
                RAISE;
            END IF;
        END;
    END LOOP;

    v_count := sim.seed_passengers(
        v_legs[1],
        greatest(4, p_passengers + floor(random() * (p_passengers / 2 + 1))::int));
    RAISE NOTICE '[sim] % added with % passengers', sim.label(v_legs[1]), v_count;

    IF cardinality(v_legs) > 1 THEN
        v_through := sim.copy_through_passengers(v_legs[1], v_legs[2]);
        v_count := sim.seed_passengers(
            v_legs[2],
            greatest(2, p_passengers / 2),
            v_through + 1);
        RAISE NOTICE '[sim] % added with % through and % joining passengers',
            sim.label(v_legs[2]), v_through, v_count;
    END IF;
    COMMIT;

    -- 2. Random disruption scenario on one leg (either leg of a multi-leg
    --    flight, so leg-specific delivery is visible in the UI).
    v_target := v_legs[1 + floor(random() * cardinality(v_legs))::int];
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
        v_message := sim.apply_step(v_target, v_step);
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
