import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../../api.js';
import { eventIcon, flightCode, formatClockTime, timeOnly } from '../../lib/format.js';

/*
 * Flight operations workspace: add, delay, cancel, and remove flights to
 * drive the change-data-capture pipeline from the UI.
 *
 * Intentionally hidden in the portfolio build (see SHOW_FLIGHT_OPERATIONS in
 * src/config.js). Kept fully functional so it can be re-enabled at any time.
 */

const statusLabels = {
  SCHEDULED: 'Scheduled',
  DELAYED: 'Delayed',
  BOARDING: 'Boarding',
  DEPARTED: 'Departed',
  ARRIVED: 'Arrived',
  CANCELLED: 'Cancelled'
};

function toDateTimeLocal(date) {
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

function toLocalDate(date) {
  return toDateTimeLocal(date).slice(0, 10);
}

function initialForm() {
  const departure = new Date();
  departure.setDate(departure.getDate() + 1);
  departure.setHours(11, 0, 0, 0);

  const arrival = new Date(departure);
  arrival.setHours(arrival.getHours() + 14);

  return {
    carrierCode: 'AA',
    flightNumber: '',
    serviceDate: toLocalDate(departure),
    originAirport: 'SYD',
    destinationAirport: 'LAX',
    originTimezone: 'Australia/Sydney',
    destinationTimezone: 'America/Los_Angeles',
    scheduledDepartureUtc: toDateTimeLocal(departure),
    scheduledArrivalUtc: toDateTimeLocal(arrival),
    gate: ''
  };
}

function AddFlightForm({ onClose, onCreated }) {
  const [form, setForm] = useState(initialForm);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  function update(field, value) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  async function submit(event) {
    event.preventDefault();
    setSubmitting(true);
    setError('');

    try {
      // Multi-leg flights are added one leg at a time (same flight number).
      await api('/api/flights', {
        method: 'POST',
        body: JSON.stringify({
          ...form,
          scheduledDepartureUtc: new Date(
            form.scheduledDepartureUtc
          ).toISOString(),
          scheduledArrivalUtc: new Date(
            form.scheduledArrivalUtc
          ).toISOString(),
          estimatedDepartureUtc: new Date(
            form.scheduledDepartureUtc
          ).toISOString(),
          estimatedArrivalUtc: new Date(
            form.scheduledArrivalUtc
          ).toISOString()
        })
      });
      onCreated();
      onClose();
    } catch (submitError) {
      setError(submitError.message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="drawer-backdrop" role="presentation">
      <section
        className="drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="add-flight-title"
      >
        <div className="drawer__header">
          <div>
            <span className="eyebrow">Schedule management</span>
            <h2 id="add-flight-title">Add a flight</h2>
          </div>
          <button
            className="icon-button"
            type="button"
            aria-label="Close"
            onClick={onClose}
          >
            ×
          </button>
        </div>

        <form onSubmit={submit} className="flight-form">
          <div className="form-grid form-grid--compact">
            <label>
              <span>Carrier</span>
              <input
                value={form.carrierCode}
                maxLength="3"
                onChange={(event) =>
                  update('carrierCode', event.target.value.toUpperCase())
                }
                required
              />
            </label>
            <label>
              <span>Flight number</span>
              <input
                value={form.flightNumber}
                onChange={(event) =>
                  update('flightNumber', event.target.value)
                }
                placeholder="112"
                required
              />
            </label>
          </div>

          <div className="form-grid">
            <label>
              <span>Origin</span>
              <input
                value={form.originAirport}
                maxLength="3"
                onChange={(event) =>
                  update('originAirport', event.target.value.toUpperCase())
                }
                required
              />
            </label>
            <label>
              <span>Destination</span>
              <input
                value={form.destinationAirport}
                maxLength="3"
                onChange={(event) =>
                  update('destinationAirport', event.target.value.toUpperCase())
                }
                required
              />
            </label>
          </div>

          <label>
            <span>Service date</span>
            <input
              type="date"
              value={form.serviceDate}
              onChange={(event) => update('serviceDate', event.target.value)}
              required
            />
          </label>

          <label>
            <span>Scheduled departure</span>
            <input
              type="datetime-local"
              value={form.scheduledDepartureUtc}
              onChange={(event) =>
                update('scheduledDepartureUtc', event.target.value)
              }
              required
            />
          </label>

          <label>
            <span>Scheduled arrival</span>
            <input
              type="datetime-local"
              value={form.scheduledArrivalUtc}
              onChange={(event) =>
                update('scheduledArrivalUtc', event.target.value)
              }
              required
            />
          </label>

          <div className="form-grid">
            <label>
              <span>Origin timezone</span>
              <input
                value={form.originTimezone}
                onChange={(event) =>
                  update('originTimezone', event.target.value)
                }
                required
              />
            </label>
            <label>
              <span>Destination timezone</span>
              <input
                value={form.destinationTimezone}
                onChange={(event) =>
                  update('destinationTimezone', event.target.value)
                }
                required
              />
            </label>
          </div>

          <label>
            <span>Gate</span>
            <input
              value={form.gate}
              onChange={(event) => update('gate', event.target.value)}
              placeholder="Optional"
            />
          </label>

          {error && <p className="form-error">{error}</p>}

          <div className="drawer__actions">
            <button className="button button--secondary" type="button" onClick={onClose}>
              Cancel
            </button>
            <button className="button button--primary" type="submit" disabled={submitting}>
              {submitting ? 'Adding…' : 'Add flight'}
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}

function FlightRow({ flight, busy, onDelay, onCancel, onRemove }) {
  const effectiveDeparture =
    flight.estimatedDepartureUtc || flight.scheduledDepartureUtc;
  const delayMinutes = Math.max(
    0,
    Math.round(
      (Date.parse(effectiveDeparture) -
        Date.parse(flight.scheduledDepartureUtc)) /
        60_000
    )
  );

  return (
    <tr>
      <td>
        <div className="flight-identity">
          <strong>{flightCode(flight)}</strong>
          <span>{flight.serviceDate}</span>
        </div>
      </td>
      <td>
        <div className="route">
          <strong>{flight.originAirport}</strong>
          <span className="route__line" aria-hidden="true" />
          <strong>{flight.destinationAirport}</strong>
        </div>

      </td>
      <td>
        <div className="time-cell">
          <strong>
            {timeOnly(effectiveDeparture, flight.originTimezone)}
          </strong>
          <span>
            Scheduled{' '}
            {timeOnly(
              flight.scheduledDepartureUtc,
              flight.originTimezone
            )}
          </span>
        </div>
      </td>
      <td>
        <span className={'status status--' + flight.status.toLowerCase()}>
          <span className="status__dot" />
          {statusLabels[flight.status]}
        </span>
        {delayMinutes > 0 && (
          <span className="delay-copy">+{delayMinutes} min</span>
        )}
      </td>
      <td>{flight.gate || '—'}</td>
      <td>
        <div className="row-actions">
          <button
            className="text-button"
            type="button"
            disabled={busy || flight.status === 'CANCELLED'}
            onClick={() => onDelay(flight)}
          >
            Delay 15m
          </button>
          <button
            className="text-button"
            type="button"
            disabled={busy || flight.status === 'CANCELLED'}
            onClick={() => onCancel(flight)}
          >
            Cancel
          </button>
          <button
            className="icon-button icon-button--danger"
            type="button"
            aria-label={'Remove ' + flightCode(flight)}
            disabled={busy}
            onClick={() => onRemove(flight)}
          >
            ×
          </button>
        </div>
      </td>
    </tr>
  );
}


function ActivityItem({ event }) {
  return (
    <li className="activity-item">
      <div className={'activity-icon activity-icon--' + event.severity}>
        {eventIcon(event.type)}
      </div>
      <div>
        <strong>{event.message}</strong>
        <span>{formatClockTime(event.occurredAt)}</span>
      </div>
    </li>
  );
}

/**
 * @param events       notification history (newest first) for the activity panel
 * @param lastEventId  newest event ID; the schedule reloads when it changes
 */
export function FlightOperations({ events, lastEventId }) {
  const [flights, setFlights] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busyFlight, setBusyFlight] = useState('');
  const [showAddFlight, setShowAddFlight] = useState(false);

  // Bumped to force a reload after this page changes a flight.
  const [reloadToken, setReloadToken] = useState(0);
  const loadFlights = useCallback(() => setReloadToken((token) => token + 1), []);

  // Reload whenever a new flight event arrives or a reload is requested.
  useEffect(() => {
    let cancelled = false;
    api('/api/flights')
      .then((response) => {
        if (!cancelled) {
          setFlights(response.flights);
          setError('');
        }
      })
      .catch((loadError) => {
        if (!cancelled) {
          setError(loadError.message);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [lastEventId, reloadToken]);

  const metrics = useMemo(() => ({
    total: flights.length,
    delayed: flights.filter((flight) => flight.status === 'DELAYED').length,
    active: flights.filter(
      (flight) => !['CANCELLED', 'ARRIVED'].includes(flight.status)
    ).length
  }), [flights]);

  async function updateFlight(flight, patch) {
    setBusyFlight(flight.flightId);
    setError('');

    try {
      await api('/api/flights/' + flight.flightId, {
        method: 'PATCH',
        body: JSON.stringify({ ...patch, version: flight.version })
      });
      loadFlights();
    } catch (updateError) {
      setError(updateError.message);
    } finally {
      setBusyFlight('');
    }
  }

  async function delayFlight(flight) {
    const currentDeparture = new Date(
      flight.estimatedDepartureUtc || flight.scheduledDepartureUtc
    );
    currentDeparture.setMinutes(currentDeparture.getMinutes() + 15);

    await updateFlight(flight, {
      estimatedDepartureUtc: currentDeparture.toISOString(),
      status: 'DELAYED'
    });
  }

  async function cancelFlight(flight) {
    await updateFlight(flight, { status: 'CANCELLED' });
  }

  async function removeFlight(flight) {
    if (!window.confirm('Remove ' + flightCode(flight) + ' from the schedule?')) {
      return;
    }

    setBusyFlight(flight.flightId);
    try {
      await api('/api/flights/' + flight.flightId, { method: 'DELETE' });
      loadFlights();
    } catch (removeError) {
      setError(removeError.message);
    } finally {
      setBusyFlight('');
    }
  }

  return (
    <>
      <section className="hero">
        <div>
          <span className="eyebrow">Network overview</span>
          <h1>Flight operations</h1>
          <p>
            Monitor schedule changes as they commit in PostgreSQL and arrive
            in the browser.
          </p>
          <button
            className="button button--primary"
            type="button"
            onClick={() => setShowAddFlight(true)}
          >
            <span aria-hidden="true">+</span>
            Add flight
          </button>
        </div>
        <div className="metrics" aria-label="Flight summary">
          <div>
            <span>Active flights</span>
            <strong>{metrics.active}</strong>
          </div>
          <div>
            <span>Delayed</span>
            <strong>{metrics.delayed}</strong>
          </div>
          <div>
            <span>Total records</span>
            <strong>{metrics.total}</strong>
          </div>
        </div>
      </section>

      {error && (
        <div className="error-banner" role="alert">
          <strong>Something needs attention.</strong>
          <span>{error}</span>
          <button type="button" onClick={loadFlights}>Retry</button>
        </div>
      )}

      <div className="workspace">
        <section className="panel flight-panel">
          <div className="panel__header">
            <div>
              <span className="eyebrow">Live schedule</span>
              <h2>Upcoming departures</h2>
            </div>
            <span className="record-count">{flights.length} flights</span>
          </div>

          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Flight</th>
                  <th>Route</th>
                  <th>Departure</th>
                  <th>Status</th>
                  <th>Gate</th>
                  <th><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {flights.map((flight) => (
                  <FlightRow
                    key={flight.flightId}
                    flight={flight}
                    busy={busyFlight === flight.flightId}
                    onDelay={delayFlight}
                    onCancel={cancelFlight}
                    onRemove={removeFlight}
                  />
                ))}
              </tbody>
            </table>
          </div>

          {!loading && flights.length === 0 && (
            <div className="empty-state">
              <strong>No flights scheduled</strong>
              <span>Add a flight to test the event stream.</span>
            </div>
          )}

          {loading && <div className="loading-state">Loading schedule…</div>}
        </section>

        <aside className="panel activity-panel">
          <div className="panel__header">
            <div>
              <span className="eyebrow">PostgreSQL → SSE</span>
              <h2>Recent activity</h2>
            </div>
          </div>

          <ol className="activity-list">
            {events.slice(0, 10).map((event) => (
              <ActivityItem key={event.eventId} event={event} />
            ))}
          </ol>

          {events.length === 0 && (
            <div className="empty-state empty-state--compact">
              <strong>No activity yet</strong>
              <span>Changes will appear here in real time.</span>
            </div>
          )}
        </aside>
      </div>

      {showAddFlight && (
        <AddFlightForm
          onClose={() => setShowAddFlight(false)}
          onCreated={loadFlights}
        />
      )}
    </>
  );
}
