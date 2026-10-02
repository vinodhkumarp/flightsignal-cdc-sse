import { useCallback, useEffect, useRef, useState } from 'react';
import { api, authHeaders, notifyUnauthorized } from '../api.js';
import { latestEventId, mergeEvents, reconnectDelay } from '../lib/events.js';
import { openEventStream } from '../lib/sse-client.js';

const HISTORY_PAGE_SIZE = 30;
const OFFLINE_AFTER_ATTEMPTS = 3;

function withStations(path, stations) {
  if (!stations || stations.length === 0) {
    return path;
  }
  const separator = path.includes('?') ? '&' : '?';
  return `${path}${separator}stations=${encodeURIComponent(stations.join(','))}`;
}

/**
 * Loads notification history and keeps it current over Server-Sent Events,
 * for the signed-in user's stations (optionally narrowed by `stations`).
 *
 * Every request, including the stream, carries the user's JWT. The API
 * filters by the stations in that token, so this hook never receives
 * another station's flights.
 *
 * Connection lifecycle:
 *  1. Load the newest history page (retrying with backoff on failure). The
 *     stream is never opened without a cursor.
 *  2. Open `/api/events/stream?after=<newest id>` with a fresh token.
 *  3. Whenever the stream ends (network drop, server restart, token expiry)
 *     reconnect with backoff and a fresh token, resuming from the newest ID.
 *  4. 401 hands control back to the auth layer; 403 stops (no access).
 *  5. On a `reset` event (too many missed events) reload history.
 *
 * The caller should remount the component using this hook (via `key`) when
 * the station filter changes, which starts a clean list.
 */
export function useFlightEvents({ stations = null, onLiveEvent } = {}) {
  const [events, setEvents] = useState([]);
  const [connection, setConnection] = useState('connecting');
  const [latestLiveEvent, setLatestLiveEvent] = useState(null);
  const [nextCursor, setNextCursor] = useState(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [historyError, setHistoryError] = useState('');

  const knownIds = useRef(new Set());
  const cursor = useRef(null);
  const historyLoaded = useRef(false);
  const onLiveEventRef = useRef(onLiveEvent);
  const stationsKey = stations?.join(',') ?? '';

  useEffect(() => {
    onLiveEventRef.current = onLiveEvent;
  }, [onLiveEvent]);

  const rememberEvents = useCallback((incoming) => {
    for (const event of incoming) {
      knownIds.current.add(String(event.eventId));
    }
    cursor.current = latestEventId(incoming, cursor.current ?? 0);
  }, []);

  const loadHistory = useCallback(async () => {
    const filter = stationsKey ? stationsKey.split(',') : null;
    const page = await api(withStations(`/api/events?limit=${HISTORY_PAGE_SIZE}`, filter));
    rememberEvents(page.events);
    setEvents((current) => mergeEvents(current, page.events));
    if (!historyLoaded.current) {
      setNextCursor(page.nextCursor);
      setHasMore(page.hasMore);
    }
    historyLoaded.current = true;
    setHistoryError('');
  }, [rememberEvents, stationsKey]);

  useEffect(() => {
    let disposed = false;
    let stream = null;
    let retryTimer = null;
    let attempt = 0;
    let live = false;
    const filter = stationsKey ? stationsKey.split(',') : null;

    function scheduleReconnect() {
      if (disposed) {
        return;
      }
      setConnection(attempt >= OFFLINE_AFTER_ATTEMPTS ? 'offline' : 'reconnecting');
      window.clearTimeout(retryTimer);
      retryTimer = window.setTimeout(connect, reconnectDelay(attempt));
      attempt += 1;
    }

    function handleFlightChange(data) {
      let event;
      try {
        event = JSON.parse(data);
      } catch {
        return;
      }

      const id = String(event.eventId);
      cursor.current = Math.max(cursor.current ?? 0, Number(id));
      if (knownIds.current.has(id)) {
        return;
      }

      knownIds.current.add(id);
      setEvents((current) => mergeEvents(current, [event]));
      setLatestLiveEvent(event);
      onLiveEventRef.current?.(event);
    }

    function handleStreamEnd(details) {
      live = false;
      if (disposed) {
        return;
      }
      if (details.type === 'http' && details.status === 401) {
        notifyUnauthorized();
        return;
      }
      if (details.type === 'http' && details.status === 403) {
        setConnection('forbidden');
        return;
      }
      scheduleReconnect();
    }

    async function openStream() {
      stream?.close();
      const headers = await authHeaders();
      if (disposed) {
        return;
      }
      const url = withStations(`/api/events/stream?after=${cursor.current ?? 0}`, filter);
      stream = openEventStream(url, {
        headers,
        onEvent: (message) => {
          if (message.type === 'ready') {
            attempt = 0;
            live = true;
            setConnection('connected');
          } else if (message.type === 'flight-change') {
            handleFlightChange(message.data);
          } else if (message.type === 'reset') {
            loadHistory().catch((error) => setHistoryError(error.message));
          }
        },
        onError: handleStreamEnd
      });
    }

    async function connect() {
      if (disposed) {
        return;
      }

      if (!historyLoaded.current) {
        try {
          await loadHistory();
        } catch (error) {
          if (disposed) {
            return;
          }
          if (error.status === 401) {
            return;
          }
          if (error.status === 403) {
            setHistoryError(error.message);
            setConnection('forbidden');
            return;
          }
          setHistoryError(error.message);
          scheduleReconnect();
          return;
        }
      }

      await openStream();
    }

    function reconnectNow() {
      if (!live) {
        attempt = 0;
        window.clearTimeout(retryTimer);
        connect();
      }
    }

    connect();
    window.addEventListener('online', reconnectNow);

    return () => {
      disposed = true;
      window.clearTimeout(retryTimer);
      window.removeEventListener('online', reconnectNow);
      stream?.close();
    };
  }, [loadHistory, stationsKey]);

  const loadOlder = useCallback(async () => {
    if (!hasMore || !nextCursor || loadingOlder) {
      return;
    }

    setLoadingOlder(true);
    try {
      const filter = stationsKey ? stationsKey.split(',') : null;
      const page = await api(withStations(
        `/api/events?limit=${HISTORY_PAGE_SIZE}&before=${encodeURIComponent(nextCursor)}`,
        filter
      ));
      rememberEvents(page.events);
      setEvents((current) => mergeEvents(current, page.events));
      setNextCursor(page.nextCursor);
      setHasMore(page.hasMore);
    } catch (error) {
      setHistoryError(error.message);
    } finally {
      setLoadingOlder(false);
    }
  }, [hasMore, loadingOlder, nextCursor, rememberEvents, stationsKey]);

  const dismissLatestEvent = useCallback(() => setLatestLiveEvent(null), []);

  return {
    events,
    connection,
    latestLiveEvent,
    dismissLatestEvent,
    loadOlder,
    hasMore,
    loadingOlder,
    historyError
  };
}
