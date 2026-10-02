import { useEffect } from 'react';
import { eventIcon, formatInTimezone } from '../lib/format.js';

const TOAST_DURATION_MS = 8_000;

export function EventToast({ event, onDismiss }) {
  useEffect(() => {
    if (!event) {
      return undefined;
    }

    const timeout = window.setTimeout(onDismiss, TOAST_DURATION_MS);
    return () => window.clearTimeout(timeout);
  }, [event, onDismiss]);

  if (!event) {
    return null;
  }

  return (
    <div className={'toast toast--' + event.severity} role="status">
      <div className="toast__icon" aria-hidden="true">
        {eventIcon(event.type)}
      </div>
      <div className="toast__copy">
        <span className="eyebrow">Live flight update</span>
        <strong>{event.message}</strong>
        {event.delay && event.flight && (
          <span>
            New departure{' '}
            {formatInTimezone(event.delay.newDepartureUtc, event.flight.originTimezone)}
          </span>
        )}
      </div>
      <button
        className="icon-button"
        type="button"
        aria-label="Dismiss notification"
        onClick={onDismiss}
      >
        ×
      </button>
    </div>
  );
}
