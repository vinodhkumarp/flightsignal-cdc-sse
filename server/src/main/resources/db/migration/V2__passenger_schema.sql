-- Passenger manifest snapshot used for disruption outreach. Idempotent (see V1).

CREATE TABLE IF NOT EXISTS public.flight_passenger (
    passenger_id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    flight_id                  uuid,
    carrier_code               varchar(3) NOT NULL,
    flight_number              varchar(8) NOT NULL,
    service_date               date NOT NULL,
    origin_airport             char(3) NOT NULL,
    destination_airport        char(3) NOT NULL,
    manifest_sequence          smallint NOT NULL
                               CHECK (manifest_sequence > 0),
    booking_reference          varchar(8) NOT NULL,
    given_name                 varchar(80) NOT NULL,
    family_name                varchar(80) NOT NULL,
    email                      varchar(254),
    phone_number               varchar(32),
    preferred_contact_method   text NOT NULL DEFAULT 'EMAIL'
                               CHECK (preferred_contact_method IN (
                                 'EMAIL', 'SMS', 'PHONE'
                               )),
    seat_number                varchar(5),
    cabin_class                text NOT NULL DEFAULT 'ECONOMY'
                               CHECK (cabin_class IN (
                                 'ECONOMY',
                                 'PREMIUM_ECONOMY',
                                 'BUSINESS',
                                 'FIRST'
                               )),
    loyalty_tier               text,
    special_assistance         boolean NOT NULL DEFAULT false,
    contact_status             text NOT NULL DEFAULT 'NOT_CONTACTED'
                               CHECK (contact_status IN (
                                 'NOT_CONTACTED',
                                 'CONTACTED',
                                 'FAILED'
                               )),
    created_at                 timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at                 timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT flight_passenger_flight_fk
      FOREIGN KEY (flight_id)
      REFERENCES public.flight_instance (flight_id)
      ON DELETE SET NULL,
    CONSTRAINT flight_passenger_manifest_unique
      UNIQUE (flight_id, manifest_sequence)
);

CREATE INDEX IF NOT EXISTS flight_passenger_flight_search_idx
    ON public.flight_passenger (
      carrier_code,
      flight_number,
      service_date,
      origin_airport,
      destination_airport
    );

CREATE INDEX IF NOT EXISTS flight_passenger_name_search_idx
    ON public.flight_passenger (upper(family_name), upper(given_name));

COMMENT ON TABLE public.flight_passenger IS
  'Passenger manifest snapshot populated by an external application. Flight identity columns are retained after a flight row is deleted so disruption outreach can continue.';

COMMENT ON COLUMN public.flight_passenger.flight_id IS
  'Optional live reference to flight_instance. ON DELETE SET NULL preserves the passenger manifest and its copied flight identity.';
