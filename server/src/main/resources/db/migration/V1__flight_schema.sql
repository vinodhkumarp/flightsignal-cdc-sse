-- Flight schedule, durable change log, and trigger-based change data capture.
--
-- Every statement is idempotent so the migration can also be applied to a
-- database that was created by the earlier docker-entrypoint init scripts
-- (Flyway baselines such databases at version 0 and then runs this file).

CREATE SCHEMA IF NOT EXISTS app_internal;

CREATE TABLE IF NOT EXISTS public.flight_instance (
    flight_id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    carrier_code                 varchar(3) NOT NULL,
    flight_number                varchar(8) NOT NULL,
    service_date                 date NOT NULL,
    origin_airport               char(3) NOT NULL,
    destination_airport          char(3) NOT NULL,
    origin_timezone              text NOT NULL,
    destination_timezone         text NOT NULL,
    scheduled_departure_utc      timestamptz NOT NULL,
    estimated_departure_utc      timestamptz,
    actual_departure_utc         timestamptz,
    scheduled_arrival_utc        timestamptz NOT NULL,
    estimated_arrival_utc        timestamptz,
    actual_arrival_utc           timestamptz,
    status                       text NOT NULL DEFAULT 'SCHEDULED'
                                 CHECK (status IN (
                                   'SCHEDULED',
                                   'DELAYED',
                                   'BOARDING',
                                   'DEPARTED',
                                   'ARRIVED',
                                   'CANCELLED'
                                 )),
    gate                         varchar(12),
    version                      bigint NOT NULL DEFAULT 1,
    created_at                   timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at                   timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT flight_instance_route_check
      CHECK (origin_airport <> destination_airport),
    CONSTRAINT flight_instance_unique
      UNIQUE (
        carrier_code,
        flight_number,
        service_date,
        origin_airport,
        scheduled_departure_utc
      )
);

CREATE TABLE IF NOT EXISTS app_internal.flight_change_event (
    event_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation       text NOT NULL
                    CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE')),
    flight_id       uuid NOT NULL,
    old_row         jsonb,
    new_row         jsonb,
    occurred_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT flight_change_event_shape_check CHECK (
      (operation = 'INSERT' AND old_row IS NULL AND new_row IS NOT NULL)
      OR
      (operation = 'UPDATE' AND old_row IS NOT NULL AND new_row IS NOT NULL)
      OR
      (operation = 'DELETE' AND old_row IS NOT NULL AND new_row IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS flight_change_event_occurred_idx
    ON app_internal.flight_change_event (occurred_at DESC, event_id DESC);

-- BEFORE UPDATE: maintain optimistic-locking metadata.
-- An UPDATE that changes no column keeps the row untouched (same version and
-- updated_at), which lets the capture trigger below recognise it as a no-op.
CREATE OR REPLACE FUNCTION app_internal.set_flight_update_metadata()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW IS NOT DISTINCT FROM OLD THEN
        RETURN NEW;
    END IF;

    NEW.updated_at := clock_timestamp();
    NEW.version := OLD.version + 1;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS set_flight_update_metadata
ON public.flight_instance;

CREATE TRIGGER set_flight_update_metadata
BEFORE UPDATE ON public.flight_instance
FOR EACH ROW
EXECUTE FUNCTION app_internal.set_flight_update_metadata();

-- AFTER INSERT/UPDATE/DELETE: append to the durable change log and send the
-- new event ID as a wake-up signal. pg_notify is transactional, so listeners
-- only hear about events whose transaction committed.
CREATE OR REPLACE FUNCTION app_internal.capture_flight_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_event_id bigint;
    v_flight_id uuid;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW IS NOT DISTINCT FROM OLD THEN
        RETURN NEW;
    END IF;

    v_flight_id := CASE
      WHEN TG_OP = 'DELETE' THEN OLD.flight_id
      ELSE NEW.flight_id
    END;

    INSERT INTO app_internal.flight_change_event (
        operation,
        flight_id,
        old_row,
        new_row
    )
    VALUES (
        TG_OP,
        v_flight_id,
        CASE WHEN TG_OP IN ('UPDATE', 'DELETE') THEN to_jsonb(OLD) END,
        CASE WHEN TG_OP IN ('INSERT', 'UPDATE') THEN to_jsonb(NEW) END
    )
    RETURNING event_id INTO v_event_id;

    PERFORM pg_notify('flight_changes', v_event_id::text);

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

DROP TRIGGER IF EXISTS capture_flight_change
ON public.flight_instance;

CREATE TRIGGER capture_flight_change
AFTER INSERT OR UPDATE OR DELETE
ON public.flight_instance
FOR EACH ROW
EXECUTE FUNCTION app_internal.capture_flight_change();

-- Retention: delete change events older than the retention period in bounded
-- batches so a large purge never holds long locks. Returns the rows deleted
-- by this call; callers repeat until it returns 0.
CREATE OR REPLACE FUNCTION app_internal.purge_flight_change_events(
    p_retention interval,
    p_batch_size integer DEFAULT 5000)
RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
    v_deleted bigint;
BEGIN
    DELETE FROM app_internal.flight_change_event
    WHERE event_id IN (
        SELECT event_id
        FROM app_internal.flight_change_event
        WHERE occurred_at < clock_timestamp() - p_retention
        ORDER BY event_id
        LIMIT p_batch_size
    );

    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;
