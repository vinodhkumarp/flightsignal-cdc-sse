-- Development seed data (Flyway 'dev' profile or 'make db-seed'). Re-runnable.

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
VALUES
(
    'QF',
    '11',
    CURRENT_DATE + 1,
    'SYD',
    'LAX',
    'Australia/Sydney',
    'America/Los_Angeles',
    date_trunc('day', now()) + interval '1 day 09 hours',
    date_trunc('day', now()) + interval '1 day 09 hours',
    date_trunc('day', now()) + interval '1 day 23 hours 15 minutes',
    date_trunc('day', now()) + interval '1 day 23 hours 15 minutes',
    'SCHEDULED',
    '24'
),
(
    'AA',
    '112',
    CURRENT_DATE + 1,
    'SYD',
    'LAX',
    'Australia/Sydney',
    'America/Los_Angeles',
    date_trunc('day', now()) + interval '1 day 11 hours',
    date_trunc('day', now()) + interval '1 day 11 hours 25 minutes',
    date_trunc('day', now()) + interval '2 days 01 hour 20 minutes',
    date_trunc('day', now()) + interval '2 days 01 hour 45 minutes',
    'DELAYED',
    '31'
),
(
    'NZ',
    '104',
    CURRENT_DATE + 1,
    'SYD',
    'AKL',
    'Australia/Sydney',
    'Pacific/Auckland',
    date_trunc('day', now()) + interval '1 day 13 hours 40 minutes',
    date_trunc('day', now()) + interval '1 day 13 hours 40 minutes',
    date_trunc('day', now()) + interval '1 day 16 hours 45 minutes',
    date_trunc('day', now()) + interval '1 day 16 hours 45 minutes',
    'BOARDING',
    '8'
)
ON CONFLICT DO NOTHING;

-- Multi-leg flight QF1 Sydney -> Singapore -> London, stored as one row per
-- leg. Each leg notifies only its own stations: a change to SIN -> LHR reaches
-- SIN and LHR, never SYD.
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
VALUES
(
    'QF',
    '1',
    CURRENT_DATE + 1,
    'SYD',
    'SIN',
    'Australia/Sydney',
    'Asia/Singapore',
    date_trunc('day', now()) + interval '1 day 06 hours 30 minutes',
    date_trunc('day', now()) + interval '1 day 06 hours 30 minutes',
    date_trunc('day', now()) + interval '1 day 14 hours 45 minutes',
    date_trunc('day', now()) + interval '1 day 14 hours 45 minutes',
    'SCHEDULED',
    '10'
),
(
    'QF',
    '1',
    CURRENT_DATE + 1,
    'SIN',
    'LHR',
    'Asia/Singapore',
    'Europe/London',
    date_trunc('day', now()) + interval '1 day 16 hours 15 minutes',
    date_trunc('day', now()) + interval '1 day 16 hours 15 minutes',
    date_trunc('day', now()) + interval '1 day 30 hours 40 minutes',
    date_trunc('day', now()) + interval '1 day 30 hours 40 minutes',
    'SCHEDULED',
    'C21'
)
ON CONFLICT DO NOTHING;
