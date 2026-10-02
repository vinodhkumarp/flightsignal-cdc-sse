import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { displayEnum, eventIcon, flightCode, formatEventTime } from '../lib/format.js';
import { emptyFilters, filtersForEvent, passengerQuery } from '../lib/passenger-filters.js';

let nextQueryId = 1;

function newQuery(filters) {
  return { id: nextQueryId++, filters: { ...filters } };
}

/**
 * Passenger manifest search.
 *
 * The parent remounts this component (via `key`) whenever a notification is
 * opened, so `seedEvent` only seeds the initial state and its search runs
 * immediately.
 *
 * Every submitted search is a `query` object; the effect fetches it and
 * stores the result tagged with the query ID, so a slow response for an
 * older query can never overwrite a newer one.
 */
export function PassengerSearch({ notification, seedEvent }) {
  const [filters, setFilters] = useState(() =>
    seedEvent ? filtersForEvent(seedEvent) : emptyFilters()
  );
  const [query, setQuery] = useState(() =>
    seedEvent ? newQuery(filtersForEvent(seedEvent)) : null
  );
  const [result, setResult] = useState({ queryId: null, passengers: [], error: '' });

  useEffect(() => {
    if (!query) {
      return undefined;
    }

    let cancelled = false;
    api('/api/passengers?' + passengerQuery(query.filters))
      .then((response) => {
        if (!cancelled) {
          setResult({ queryId: query.id, passengers: response.passengers, error: '' });
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setResult({ queryId: query.id, passengers: [], error: error.message });
        }
      });

    return () => {
      cancelled = true;
    };
  }, [query]);

  const submittedFilters = query?.filters ?? null;
  const loading = query !== null && result.queryId !== query.id;
  const searchError = loading ? '' : result.error;
  const passengers = loading || !query ? [] : result.passengers;

  const route = useMemo(() => {
    const flight = notification?.flight;
    return flight ? flight.originAirport + ' → ' + flight.destinationAirport : '';
  }, [notification]);

  function update(field, value) {
    setFilters((current) => ({ ...current, [field]: value }));
  }

  function applyNotification() {
    const notificationFilters = filtersForEvent(notification);
    setFilters(notificationFilters);
    setQuery(newQuery(notificationFilters));
  }

  function submit(event) {
    event.preventDefault();
    setQuery(newQuery(filters));
  }

  function retry() {
    if (query) {
      setQuery(newQuery(query.filters));
    }
  }

  function clear() {
    setFilters(emptyFilters());
    setQuery(null);
    setResult({ queryId: null, passengers: [], error: '' });
  }

  return (
    <div className="passenger-page">
      <section className="passenger-hero">
        <div>
          <span className="eyebrow">Passenger care workspace</span>
          <h1>Find affected passengers</h1>
          <p>
            Start from a flight notification, review the affected service, and
            prepare the passenger outreach list.
          </p>
        </div>
      </section>

      {notification && (
        <section
          className={
            'disruption-banner disruption-banner--' + notification.severity
          }
          aria-live="polite"
        >
          <div className="disruption-banner__visual" aria-hidden="true">
            <span>{eventIcon(notification.type)}</span>
          </div>
          <div className="disruption-banner__content">
            <span className="eyebrow">Latest selected notification</span>
            <h2>{notification.message}</h2>
            <div className="disruption-banner__meta">
              <strong>{flightCode(notification.flight)}</strong>
              <span>{route}</span>
              <span>{notification.flight?.serviceDate}</span>
              <time dateTime={notification.occurredAt}>
                Received {formatEventTime(notification.occurredAt, { withYear: true })}
              </time>
            </div>
          </div>
          <button
            className="button disruption-banner__button"
            type="button"
            onClick={applyNotification}
          >
            Use this flight
          </button>
        </section>
      )}

      <section className="panel passenger-search-panel">
        <div className="panel__header passenger-search-panel__header">
          <div>
            <span className="eyebrow">Manifest filters</span>
            <h2>Passenger search</h2>
          </div>
        </div>

        <form className="passenger-search-form" onSubmit={submit}>
          <div className="passenger-filter-grid">
            <label>
              <span>Flight number</span>
              <input
                value={filters.flightNumber}
                placeholder="AA112"
                onChange={(event) =>
                  update('flightNumber', event.target.value.toUpperCase())
                }
                required
              />
            </label>
            <label>
              <span>Travel date</span>
              <input
                type="date"
                value={filters.travelDate}
                onChange={(event) => update('travelDate', event.target.value)}
                required
              />
            </label>
            <label>
              <span>Origin</span>
              <input
                value={filters.originAirport}
                maxLength="3"
                placeholder="SYD"
                onChange={(event) =>
                  update('originAirport', event.target.value.toUpperCase())
                }
              />
            </label>
            <label>
              <span>Destination</span>
              <input
                value={filters.destinationAirport}
                maxLength="3"
                placeholder="LAX"
                onChange={(event) =>
                  update('destinationAirport', event.target.value.toUpperCase())
                }
              />
            </label>
            <label>
              <span>Passenger name</span>
              <input
                value={filters.passengerName}
                placeholder="Optional"
                onChange={(event) => update('passengerName', event.target.value)}
              />
            </label>
            <label>
              <span>Booking reference</span>
              <input
                value={filters.bookingReference}
                placeholder="Optional"
                onChange={(event) =>
                  update('bookingReference', event.target.value.toUpperCase())
                }
              />
            </label>
          </div>

          <div className="passenger-search-form__actions">
            <button
              className="button button--secondary"
              type="button"
              onClick={clear}
            >
              Clear filters
            </button>
            <button
              className="button button--primary"
              type="submit"
              disabled={loading}
            >
              {loading ? 'Searching…' : 'Search passengers'}
            </button>
          </div>
        </form>
      </section>

      {(submittedFilters || loading || searchError) && (
      <section className="panel passenger-results-panel" aria-live="polite">
        <div className="panel__header">
          <div>
            <span className="eyebrow">Passenger results</span>
            <h2>
              {submittedFilters
                ? `${submittedFilters.flightNumber} · ${submittedFilters.travelDate}`
                : 'Affected passenger manifest'}
            </h2>
          </div>
          {submittedFilters && !loading && !searchError && (
            <span className="record-count">
              {passengers.length} passengers
            </span>
          )}
        </div>

        {loading && (
          <div className="loading-state">Loading passenger manifest…</div>
        )}

        {searchError && !loading && (
          <div className="passenger-search-error" role="alert">
            <strong>Passenger search failed.</strong>
            <span>{searchError}</span>
            <button type="button" onClick={retry}>
              Retry
            </button>
          </div>
        )}

        {submittedFilters && !loading && !searchError && passengers.length === 0 && (
          <div className="empty-state">
            <strong>No passengers matched this flight</strong>
            <span>Review the flight, date, route, and passenger filters.</span>
          </div>
        )}

        {!loading && !searchError && passengers.length > 0 && (
          <div className="table-wrap">
            <table className="passenger-table">
              <thead>
                <tr>
                  <th>Passenger</th>
                  <th>Booking</th>
                  <th>Seat</th>
                  <th>Cabin</th>
                  <th>Contact details</th>
                  <th>Preferred</th>
                  <th>Outreach status</th>
                </tr>
              </thead>
              <tbody>
                {passengers.map((passenger) => (
                  <tr key={passenger.passengerId}>
                    <td>
                      <div className="passenger-name">
                        <strong>
                          {passenger.givenName} {passenger.familyName}
                        </strong>
                        <span>#{passenger.manifestSequence}</span>
                        {passenger.specialAssistance && (
                          <span className="assistance-badge">Assistance</span>
                        )}
                      </div>
                    </td>
                    <td>
                      <strong className="booking-reference">
                        {passenger.bookingReference}
                      </strong>
                    </td>
                    <td>{passenger.seatNumber || '—'}</td>
                    <td>{displayEnum(passenger.cabinClass)}</td>
                    <td>
                      <div className="passenger-contact">
                        <span>{passenger.email || 'No email'}</span>
                        <span>{passenger.phoneNumber || 'No phone'}</span>
                      </div>
                    </td>
                    <td>{displayEnum(passenger.preferredContactMethod)}</td>
                    <td>
                      <span
                        className={
                          'contact-status contact-status--' +
                          passenger.contactStatus.toLowerCase().replaceAll('_', '-')
                        }
                      >
                        {displayEnum(passenger.contactStatus)}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      )}
    </div>
  );
}
