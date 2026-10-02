import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { configureApiAuth } from '../api.js';
import { useFlightEvents } from './useFlightEvents.js';

const streams = [];

vi.mock('../lib/sse-client.js', () => ({
  openEventStream: vi.fn((url, options) => {
    const stream = {
      url,
      options,
      closed: false,
      close() {
        this.closed = true;
      },
      emit(type, data) {
        options.onEvent({ type, data: JSON.stringify(data) });
      },
      end(details) {
        options.onError(details);
      }
    };
    streams.push(stream);
    return stream;
  })
}));

const latest = () => streams.at(-1);

const event = (eventId) => ({
  eventId: String(eventId),
  occurredAt: '2026-10-02T01:00:00Z',
  type: 'flight.gate.changed',
  severity: 'info',
  message: 'Event ' + eventId,
  stations: ['SYD']
});

function jsonResponse(body, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  }));
}

function historyPage(...ids) {
  return { events: ids.map(event), nextCursor: null, hasMore: false };
}

describe('useFlightEvents', () => {
  let onUnauthorized;

  beforeEach(() => {
    streams.length = 0;
    onUnauthorized = vi.fn();
    configureApiAuth({ getAccessToken: async () => 'token-123', onUnauthorized });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('authenticates history and stream, and resumes after the newest event', async () => {
    const fetchMock = vi.fn(() => jsonResponse(historyPage(12, 11, 10)));
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useFlightEvents());

    await waitFor(() => expect(streams).toHaveLength(1));
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer token-123');
    expect(latest().url).toBe('/api/events/stream?after=12');
    expect(latest().options.headers.Authorization).toBe('Bearer token-123');
    expect(result.current.events.map((e) => e.eventId)).toEqual(['12', '11', '10']);

    act(() => latest().emit('ready', { connected: true }));
    expect(result.current.connection).toBe('connected');
  });

  it('passes a station filter to history and stream', async () => {
    const fetchMock = vi.fn(() => jsonResponse(historyPage(3)));
    vi.stubGlobal('fetch', fetchMock);

    renderHook(() => useFlightEvents({ stations: ['SIN', 'SYD'] }));

    await waitFor(() => expect(streams).toHaveLength(1));
    expect(fetchMock.mock.calls[0][0]).toBe('/api/events?limit=30&stations=SIN%2CSYD');
    expect(latest().url).toBe('/api/events/stream?after=3&stations=SIN%2CSYD');
  });

  it('never opens the stream without history and retries the history request', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.stubGlobal('fetch', vi.fn()
      .mockImplementationOnce(() => jsonResponse({ detail: 'down' }, 503))
      .mockImplementation(() => jsonResponse(historyPage(5))));

    const { result } = renderHook(() => useFlightEvents());

    await waitFor(() => expect(result.current.connection).toBe('reconnecting'));
    expect(streams).toHaveLength(0);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500);
    });

    await waitFor(() => expect(streams).toHaveLength(1));
    expect(latest().url).toBe('/api/events/stream?after=5');
  });

  it('raises a live notification only for events it has not seen', async () => {
    const onLiveEvent = vi.fn();
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(historyPage(3, 2))));
    const { result } = renderHook(() => useFlightEvents({ onLiveEvent }));
    await waitFor(() => expect(streams).toHaveLength(1));

    act(() => latest().emit('flight-change', event(3)));
    expect(result.current.latestLiveEvent).toBeNull();

    act(() => latest().emit('flight-change', event(4)));
    expect(result.current.latestLiveEvent.eventId).toBe('4');
    expect(onLiveEvent).toHaveBeenCalledTimes(1);
    expect(result.current.events.map((e) => e.eventId)).toEqual(['4', '3', '2']);
  });

  it('reconnects with a fresh token after the stream ends', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(historyPage(7))));
    const { result } = renderHook(() => useFlightEvents());
    await waitFor(() => expect(streams).toHaveLength(1));
    act(() => latest().emit('flight-change', event(8)));

    configureApiAuth({ getAccessToken: async () => 'token-456', onUnauthorized });
    act(() => latest().end({ type: 'closed' }));
    expect(result.current.connection).toBe('reconnecting');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500);
    });

    await waitFor(() => expect(streams).toHaveLength(2));
    expect(latest().url).toBe('/api/events/stream?after=8');
    expect(latest().options.headers.Authorization).toBe('Bearer token-456');
  });

  it('hands a 401 back to the auth layer instead of retrying', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(historyPage(1))));
    renderHook(() => useFlightEvents());
    await waitFor(() => expect(streams).toHaveLength(1));

    act(() => latest().end({ type: 'http', status: 401 }));

    expect(onUnauthorized).toHaveBeenCalled();
    expect(streams).toHaveLength(1);
  });

  it('stops on 403 and reports no access', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(historyPage(1))));
    const { result } = renderHook(() => useFlightEvents());
    await waitFor(() => expect(streams).toHaveLength(1));

    act(() => latest().end({ type: 'http', status: 403 }));

    expect(result.current.connection).toBe('forbidden');
  });

  it('reloads history when the server asks for a reset', async () => {
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => jsonResponse(historyPage(2, 1)))
      .mockImplementation(() => jsonResponse(historyPage(900, 899)));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useFlightEvents());
    await waitFor(() => expect(streams).toHaveLength(1));

    act(() => latest().emit('reset', { reason: 'replay-limit-exceeded' }));

    await waitFor(() =>
      expect(result.current.events.map((e) => e.eventId)).toEqual(['900', '899', '2', '1'])
    );
  });

  it('closes the stream on unmount', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(historyPage(1))));
    const { unmount } = renderHook(() => useFlightEvents());
    await waitFor(() => expect(streams).toHaveLength(1));

    unmount();

    expect(latest().closed).toBe(true);
  });
});
