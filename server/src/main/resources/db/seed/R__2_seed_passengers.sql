-- Synthetic passengers for every flight in the next seven days. Re-runnable.

WITH passenger_names AS (
    SELECT
      ARRAY[
        'Amelia', 'Noah', 'Olivia', 'Liam', 'Isla',
        'Jack', 'Mia', 'Leo', 'Ava', 'Henry',
        'Grace', 'Ethan', 'Sophie', 'Lucas', 'Chloe',
        'Oscar', 'Zoe', 'James', 'Ruby', 'Thomas'
      ]::text[] AS given_names,
      ARRAY[
        'Nguyen', 'Smith', 'Patel', 'Williams', 'Chen',
        'Brown', 'Singh', 'Wilson', 'Kim', 'Taylor',
        'Martin', 'Anderson', 'Thomas', 'White', 'Harris',
        'Clark', 'Walker', 'Hall', 'Young', 'King'
      ]::text[] AS family_names
),
eligible_flights AS (
    SELECT
      flight.*,
      row_number() OVER (ORDER BY service_date, carrier_code, flight_number)
        AS seed_flight_number
    FROM public.flight_instance flight
    WHERE flight.service_date BETWEEN CURRENT_DATE AND CURRENT_DATE + 7
),
generated_passengers AS (
    SELECT
      flight.*,
      passenger_number,
      names.given_names[passenger_number] AS given_name,
      names.family_names[
        ((passenger_number + flight.seed_flight_number::integer - 2) % 20) + 1
      ] AS family_name
    FROM eligible_flights flight
    CROSS JOIN generate_series(1, 20) AS passenger_number
    CROSS JOIN passenger_names names
)
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
    flight_id,
    carrier_code,
    flight_number,
    service_date,
    origin_airport,
    destination_airport,
    passenger_number,
    upper(substr(md5(flight_id::text || ':' || passenger_number), 1, 6)),
    given_name,
    family_name,
    lower(
      given_name || '.' || family_name || '.' ||
      substr(flight_id::text, 1, 6) || '@example.test'
    ),
    '+614' || lpad(
      (seed_flight_number::integer * 100 + passenger_number)::text,
      8,
      '0'
    ),
    CASE passenger_number % 3
      WHEN 0 THEN 'SMS'
      WHEN 1 THEN 'EMAIL'
      ELSE 'PHONE'
    END,
    (((passenger_number - 1) / 6) + 10)::text ||
      chr(65 + ((passenger_number - 1) % 6)),
    CASE
      WHEN passenger_number <= 2 THEN 'BUSINESS'
      WHEN passenger_number <= 5 THEN 'PREMIUM_ECONOMY'
      ELSE 'ECONOMY'
    END,
    CASE passenger_number % 5
      WHEN 0 THEN 'GOLD'
      WHEN 1 THEN 'SILVER'
      ELSE NULL
    END,
    passenger_number IN (7, 16),
    'NOT_CONTACTED'
FROM generated_passengers
ON CONFLICT (flight_id, manifest_sequence) DO NOTHING;
