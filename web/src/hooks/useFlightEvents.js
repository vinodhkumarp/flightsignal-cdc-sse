import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { latestEventId, mergeEvents, reconnectDelay } from '../lib/events.js';

const HISTORY_PAGE_SIZE = 30;
const OFFLINE_AFTER_ATTEMPTS = 3;

/**
 * Loads notification history and keeps it current over Server-Sent Events.
 *
 * Connection lifecycle:
 *  1. Load the newest history page. If that fails, retry with backoff; the
 *     stream is never opened without a cursor, so old events are never
 *     replayed as if they were new.
 *  2. Open `/api/events/stream?after=<newest loaded id>`. The browser
 *     reconnects transient drops itself and sends `Last-Event-ID`.
 *  3. If the browser gives up (EventSource CLOSED, e.g. the API returned
 *     502/503), reconnect manually with exponential backoff.
 *  4. On a `reset` event (too many missed events to replay) reload history.
 *
 * Events already shown (replays after a reconnect) are merged silently and
 * never re-trigger a live notification.
 *
 * @param options.onLiveEvent called once for every new live event
 * @returns connection state, events (newest first), the latest live event,
 *          and pagination helpers.
 */
export function useFlightEvents({ onLiveEvent } = {}) {
  const [events, setEvents] = useState([]);
  const [connection, setConnection] = useState('connecting');
  const [latestLiveEvent, setLatestLiveEvent] = useState(null);
  const [nextCursor, setNextCursor] = useState(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [historyError, setHistoryError] = useState('');

  const knownIds = useRef(new Set());
  const onLiveEventRef = useRef(onLiveEvent);

  useEffect(() => {
    onLiveEventRef.current = onLiveEvent;
  }, [onLiveEvent]);

  const cursor = useRef(null);
  const historyLoaded = useRef(false);

  const rememberEvents = useCallback((incoming) => {
    for (const event of incoming) {
      knownIds.current.add(String(event.eventId));
    }
    cursor.current = latestEventId(incoming, cursor.current ?? 0);
  }, []);

  const loadHistory = useCallback(async () => {
    const page = await api(`/api/events?limit=${HISTORY_PAGE_SIZE}`);
    rememberEvents(page.events);
    setEvents((current) => mergeEvents(current, page.events));
    if (!historyLoaded.current) {
      setNextCursor(page.nextCursor);
      setHasMore(page.hasMore);
    }
    historyLoaded.current = true;
    setHistoryError('');
  }, [rememberEvents]);

  useEffect(() => {
    let disposed = false;
    let eventSource = null;
    let retryTimer = null;
    let attempt = 0;

    function scheduleReconnect() {
      if (disposed) {
        return;
      }
      setConnection(attempt >= OFFLINE_AFTER_ATTEMPTS ? 'offline' : 'reconnecting');
      window.clearTimeout(retryTimer);
      retryTimer = window.setTimeout(connect, reconnectDelay(attempt));
      attempt += 1;
    }

    function handleFlightChange(message) {
      let event;
      try {
        event = JSON.parse(message.data);
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

    function openStream() {
      eventSource?.close();
      eventSource = new EventSource(`/api/events/stream?after=${cursor.current ?? 0}`);

      eventSource.addEventListener('ready', () => {
        attempt = 0;
        setConnection('connected');
      });

      eventSource.addEventListener('flight-change', handleFlightChange);

      eventSource.addEventListener('reset', () => {
        loadHistory().catch((error) => setHistoryError(error.message));
      });

      eventSource.onerror = () => {
        if (disposed) {
          return;
        }
        if (eventSource.readyState === EventSource.CLOSED) {
          eventSource.close();
          scheduleReconnect();
        } else {
          setConnection('reconnecting');
        }
      };
    }

    async function connect() {
      if (disposed) {
        return;
      }

      if (!historyLoaded.current) {
        try {
          await loadHistory();
        } catch (error) {
          if (!disposed) {
            setHistoryError(error.message);
            scheduleReconnect();
          }
          return;
        }
      }

      if (!disposed) {
        openStream();
      }
    }

    function reconnectNow() {
      if (eventSource?.readyState !== EventSource.OPEN) {
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
      eventSource?.close();
    };
  }, [loadHistory]);

  const loadOlder = useCallback(async () => {
    if (!hasMore || !nextCursor || loadingOlder) {
      return;
    }

    setLoadingOlder(true);
    try {
      const page = await api(
        `/api/events?limit=${HISTORY_PAGE_SIZE}&before=${encodeURIComponent(nextCursor)}`
      );
      rememberEvents(page.events);
      setEvents((current) => mergeEvents(current, page.events));
      setNextCursor(page.nextCursor);
      setHasMore(page.hasMore);
    } catch (error) {
      setHistoryError(error.message);
    } finally {
      setLoadingOlder(false);
    }
  }, [hasMore, loadingOlder, nextCursor, rememberEvents]);

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
