import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { SHOW_FLIGHT_OPERATIONS } from '../config.js';
import { FlightOperations } from '../features/flight-operations/FlightOperations.jsx';
import { useFlightEvents } from '../hooks/useFlightEvents.js';
import { loadReadEventIds, saveReadEventIds } from '../lib/read-state.js';
import { EventToast } from './EventToast.jsx';
import { NotificationCenter } from './NotificationCenter.jsx';
import { PassengerSearch } from './PassengerSearch.jsx';
import { StationScopeBar } from './StationScopeBar.jsx';
import { UserBadge } from './UserBadge.jsx';

const CONNECTION_LABELS = {
  connecting: 'Connecting…',
  connected: 'Live updates',
  reconnecting: 'Reconnecting…',
  offline: 'Offline · retrying',
  forbidden: 'No access'
};

/**
 * The signed-in workspace. `App` remounts it (via `key`) when the user or
 * the station filter changes, so the notification list always matches the
 * stations being viewed.
 *
 * @param user            the caller as resolved by the API from their JWT
 * @param stationFilter   stations to view (empty = all of the user's stations)
 */
export function Workspace({ user, stationFilter, onStationFilterChange, onSignOut }) {
  const [activeView, setActiveView] = useState(
    SHOW_FLIGHT_OPERATIONS ? 'flights' : 'passengers'
  );
  const [showNotificationCenter, setShowNotificationCenter] = useState(false);
  const [selectedNotification, setSelectedNotification] = useState(null);
  const [searchSeed, setSearchSeed] = useState(null);
  const [readEventIds, setReadEventIds] = useState(() => loadReadEventIds(user.subject));

  const markNotificationRead = useCallback((eventId) => {
    const id = String(eventId);
    setReadEventIds((current) => {
      if (current.has(id)) {
        return current;
      }
      const next = new Set(current);
      next.add(id);
      return next;
    });
  }, []);

  const selectedRef = useRef(null);
  const selectNotification = useCallback((event) => {
    selectedRef.current = event;
    markNotificationRead(event.eventId);
    setSelectedNotification(event);
    setSearchSeed({ event, requestedAt: Date.now() });
    setActiveView('passengers');
  }, [markNotificationRead]);

  // The first live event opens its passenger manifest automatically. Later
  // events only raise a toast so they never replace the flight being worked.
  const handleLiveEvent = useCallback((event) => {
    if (!selectedRef.current) {
      selectNotification(event);
    }
  }, [selectNotification]);

  const {
    events,
    connection,
    latestLiveEvent,
    dismissLatestEvent,
    loadOlder,
    hasMore,
    loadingOlder,
    historyError
  } = useFlightEvents({
    stations: stationFilter.length > 0 ? stationFilter : null,
    onLiveEvent: handleLiveEvent
  });

  useEffect(() => {
    saveReadEventIds(readEventIds, user.subject);
  }, [readEventIds, user.subject]);

  const unreadNotifications = useMemo(
    () => events.filter((event) => !readEventIds.has(String(event.eventId))).length,
    [events, readEventIds]
  );

  const openNotification = useCallback((event) => {
    selectNotification(event);
    setShowNotificationCenter(false);
  }, [selectNotification]);

  const markAllRead = useCallback(() => {
    setReadEventIds((current) => {
      const next = new Set(current);
      events.forEach((event) => next.add(String(event.eventId)));
      return next;
    });
  }, [events]);

  const closeNotificationCenter = useCallback(() => setShowNotificationCenter(false), []);

  return (
    <div className="app-shell">
      <header className="topbar">
        <button
          className="brand brand--button"
          type="button"
          aria-label="Open passenger search"
          onClick={() => setActiveView('passengers')}
        >
          <span className="brand__mark" aria-hidden="true">FS</span>
          <span>
            <strong>FlightSignal</strong>
            <small>Passenger care</small>
          </span>
        </button>

        <nav className="primary-nav" aria-label="Primary navigation">
          {SHOW_FLIGHT_OPERATIONS && (
            <button
              className={activeView === 'flights' ? 'is-active' : ''}
              type="button"
              aria-current={activeView === 'flights' ? 'page' : undefined}
              onClick={() => setActiveView('flights')}
            >
              Flight operations
            </button>
          )}
          <button
            className={activeView === 'passengers' ? 'is-active' : ''}
            type="button"
            aria-current={activeView === 'passengers' ? 'page' : undefined}
            onClick={() => setActiveView('passengers')}
          >
            Passenger search
          </button>
        </nav>

        <div className="topbar__actions">
          <span
            className={'connection connection--' + connection}
            role="status"
            aria-live="polite"
          >
            <span className="connection__dot" aria-hidden="true" />
            {CONNECTION_LABELS[connection] ?? connection}
          </span>

          <button
            className="notification-trigger"
            type="button"
            aria-label={
              unreadNotifications > 0
                ? `${unreadNotifications} unread notifications`
                : 'Open notification center'
            }
            onClick={() => setShowNotificationCenter(true)}
          >
            <span aria-hidden="true">Notifications</span>
            {unreadNotifications > 0 && (
              <span className="notification-trigger__count" aria-hidden="true">
                {unreadNotifications > 99 ? '99+' : unreadNotifications}
              </span>
            )}
          </button>

          <UserBadge user={user} onSignOut={onSignOut} />
        </div>
      </header>

      <StationScopeBar
        user={user}
        selected={stationFilter}
        onChange={onStationFilterChange}
      />

      {(connection === 'forbidden' || historyError) && (
        <div className="scope-alert" role="alert">
          {historyError || 'You do not have access to notifications for these stations.'}
        </div>
      )}

      <main>
        {SHOW_FLIGHT_OPERATIONS && activeView === 'flights' ? (
          <FlightOperations events={events} lastEventId={events[0]?.eventId} />
        ) : (
          <PassengerSearch
            key={searchSeed?.requestedAt ?? 'initial'}
            notification={selectedNotification}
            seedEvent={searchSeed?.event}
          />
        )}
      </main>

      <NotificationCenter
        open={showNotificationCenter}
        events={events}
        readEventIds={readEventIds}
        hasMore={hasMore}
        loadingOlder={loadingOlder}
        onClose={closeNotificationCenter}
        onSelect={openNotification}
        onLoadOlder={loadOlder}
        onMarkAllRead={markAllRead}
      />

      <EventToast event={latestLiveEvent} onDismiss={dismissLatestEvent} />
    </div>
  );
}
