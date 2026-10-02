import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useFlightEvents } from './useFlightEvents.js';

class FakeEventSource {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 2;
  static instances = [];

  constructor(url) {
    this.url = url;
    this.readyState = FakeEventSource.CONNECTING;
    this.listeners = {};
    this.onerror = null;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type, listener) {
    (this.listeners[type] ??= []).push(listener);
  }

  emit(type, data) {
    if (type === 'ready') {
      this.readyState = FakeEventSource.OPEN;
    }
    for (const listener of this.listeners[type] ?? []) {
      listener({ data: JSON.stringify(data) });
    }
  }

  fail({ closed }) {
    this.readyState = closed ? FakeEventSource.CLOSED : FakeEventSource.CONNECTING;
    this.onerror?.(new Event('error'));
  }

  close() {
    this.readyState = FakeEventSource.CLOSED;
  }

  static latest() {
    return FakeEventSource.instances.at(-1);
  }
}

const event = (eventId) => ({
  eventId: String(eventId),
  occurredAt: '2026-10-02T01:00:00Z',
  type: 'flight.gate.changed',
  severity: 'info',
  message: 'Event ' + eventId
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
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('opens the stream after the newest loaded event', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(historyPage(12, 11, 10))));

    const { result } = renderHook(() => useFlightEvents());

    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));
    expect(FakeEventSource.latest().url).toBe('/api/events/stream?after=12');
    expect(result.current.events.map((e) => e.eventId)).toEqual(['12', '11', '10']);

    act(() => FakeEventSource.latest().emit('ready', { connected: true }));
    expect(result.current.connection).toBe('connected');
  });

  it('never opens the stream without history and retries the history request', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => jsonResponse({ detail: 'down' }, 503))
      .mockImplementation(() => jsonResponse(historyPage(5)));
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useFlightEvents());

    await waitFor(() => expect(result.current.connection).toBe('reconnecting'));
    expect(FakeEventSource.instances).toHaveLength(0);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500);
    });

    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));
    expect(FakeEventSource.latest().url).toBe('/api/events/stream?after=5');
  });

  it('raises a live notification only for events it has not seen', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(historyPage(3, 2))));
    const { result } = renderHook(() => useFlightEvents());
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));
    const source = FakeEventSource.latest();

    // Replayed safety-window event the page already has.
    act(() => source.emit('flight-change', event(3)));
    expect(result.current.latestLiveEvent).toBeNull();

    act(() => source.emit('flight-change', event(4)));
    expect(result.current.latestLiveEvent.eventId).toBe('4');
    expect(result.current.events.map((e) => e.eventId)).toEqual(['4', '3', '2']);
  });

  it('reconnects manually when the browser gives up on the stream', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(historyPage(7))));
    const { result } = renderHook(() => useFlightEvents());
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));
    const first = FakeEventSource.latest();
    act(() => first.emit('flight-change', event(8)));

    act(() => first.fail({ closed: true }));
    expect(result.current.connection).toBe('reconnecting');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500);
    });

    expect(FakeEventSource.instances).toHaveLength(2);
    expect(FakeEventSource.latest().url).toBe('/api/events/stream?after=8');
  });

  it('keeps the browser reconnecting on transient errors', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(historyPage(1))));
    const { result } = renderHook(() => useFlightEvents());
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));

    act(() => FakeEventSource.latest().fail({ closed: false }));

    expect(result.current.connection).toBe('reconnecting');
    expect(FakeEventSource.instances).toHaveLength(1);
  });

  it('reloads history when the server asks for a reset', async () => {
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => jsonResponse(historyPage(2, 1)))
      .mockImplementation(() => jsonResponse(historyPage(900, 899)));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useFlightEvents());
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));

    act(() => FakeEventSource.latest().emit('reset', { reason: 'replay-limit-exceeded' }));

    await waitFor(() =>
      expect(result.current.events.map((e) => e.eventId)).toEqual(['900', '899', '2', '1'])
    );
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('closes the stream on unmount', async () => {
    vi.stubGlobal('fetch', vi.fn(() => jsonResponse(historyPage(1))));
    const { unmount } = renderHook(() => useFlightEvents());
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));

    unmount();

    expect(FakeEventSource.latest().readyState).toBe(FakeEventSource.CLOSED);
  });
});
