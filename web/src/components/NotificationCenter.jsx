import { useEffect, useRef } from 'react';
import { eventIcon, flightCode, formatEventTime } from '../lib/format.js';

export function NotificationCenter({
  open,
  events,
  readEventIds,
  hasMore,
  loadingOlder,
  onClose,
  onSelect,
  onLoadOlder,
  onMarkAllRead
}) {
  const closeButton = useRef(null);
  const onCloseRef = useRef(onClose);

  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  // Focus management and Escape handling run once per opening.
  useEffect(() => {
    if (!open) {
      return undefined;
    }

    const previouslyFocused = document.activeElement;
    closeButton.current?.focus();

    function closeOnEscape(event) {
      if (event.key === 'Escape') {
        onCloseRef.current();
      }
    }

    window.addEventListener('keydown', closeOnEscape);
    return () => {
      window.removeEventListener('keydown', closeOnEscape);
      previouslyFocused?.focus?.();
    };
  }, [open]);

  if (!open) {
    return null;
  }

  return (
    <div
      className="notification-backdrop"
      role="presentation"
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <aside
        className="notification-center"
        role="dialog"
        aria-modal="true"
        aria-labelledby="notification-center-title"
      >
        <header className="notification-center__header">
          <div>
            <span className="eyebrow">Flight disruption history</span>
            <h2 id="notification-center-title">Notification center</h2>
            <p>{events.length} notifications loaded</p>
            {onMarkAllRead && events.some((event) => !readEventIds.has(String(event.eventId))) && (
              <button
                className="text-button"
                type="button"
                onClick={onMarkAllRead}
              >
                Mark all as read
              </button>
            )}
          </div>
          <button
            ref={closeButton}
            className="icon-button"
            type="button"
            aria-label="Close notification center"
            onClick={onClose}
          >
            ×
          </button>
        </header>

        <div className="notification-center__body">
          {events.length === 0 && (
            <div className="empty-state">
              <strong>No notifications yet</strong>
              <span>Flight changes will be collected here.</span>
            </div>
          )}

          <ol className="notification-list">
            {events.map((event) => {
              const unread = !readEventIds.has(String(event.eventId));
              return (
                <li key={event.eventId}>
                  <button
                    className={
                      'notification-item' +
                      (unread ? ' notification-item--unread' : '')
                    }
                    type="button"
                    onClick={() => onSelect(event)}
                  >
                    <span
                      className={
                        'activity-icon activity-icon--' + event.severity
                      }
                      aria-hidden="true"
                    >
                      {eventIcon(event.type)}
                    </span>
                    <span className="notification-item__content">
                      <span className="notification-item__meta">
                        <strong>{flightCode(event.flight) || 'Flight update'}</strong>
                        <time dateTime={event.occurredAt}>
                          {formatEventTime(event.occurredAt)}
                        </time>
                      </span>
                      <span className="notification-item__message">
                        {event.message}
                      </span>
                      <span className="notification-item__action">
                        Open passenger search
                        <span aria-hidden="true">→</span>
                      </span>
                    </span>
                    {unread && (
                      <span className="notification-item__unread" aria-label="Unread">
                        New
                      </span>
                    )}
                  </button>
                </li>
              );
            })}
          </ol>

          {hasMore && (
            <div className="notification-center__footer">
              <button
                className="button button--secondary"
                type="button"
                disabled={loadingOlder}
                onClick={onLoadOlder}
              >
                {loadingOlder ? 'Loading…' : 'Load older notifications'}
              </button>
            </div>
          )}
        </div>
      </aside>
    </div>
  );
}
