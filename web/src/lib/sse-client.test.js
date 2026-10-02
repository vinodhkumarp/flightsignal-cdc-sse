import { describe, expect, it, vi } from 'vitest';
import { createEventStreamParser, openEventStream } from './sse-client.js';

function collect(...chunks) {
  const events = [];
  const parser = createEventStreamParser((event) => events.push(event));
  chunks.forEach((chunk) => parser.push(chunk));
  return events;
}

function streamResponse(chunks, status = 200) {
  const encoder = new TextEncoder();
  const body = new ReadableStream({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    }
  });
  return Promise.resolve(new Response(body, { status }));
}

describe('createEventStreamParser', () => {
  it('parses named events with id, retry, and multi-line data', () => {
    const events = collect('id: 7\nevent: flight-change\nretry: 3000\ndata: {"a":\ndata: 1}\n\n');

    expect(events).toEqual([
      { type: 'flight-change', data: '{"a":\n1}', id: '7', retry: 3000 }
    ]);
  });

  it('handles events split across chunks and CRLF line endings', () => {
    const events = collect('event: rea', 'dy\r\ndata: {"connected":true}\r', '\n\r\n');

    expect(events).toEqual([
      { type: 'ready', data: '{"connected":true}', id: null, retry: null }
    ]);
  });

  it('ignores comments (heartbeats) and events without data', () => {
    expect(collect(':keepalive\n\n', 'event: empty\n\n')).toEqual([]);
  });

  it('defaults the event type to message', () => {
    expect(collect('data: hi\n\n')[0].type).toBe('message');
  });
});

describe('openEventStream', () => {
  it('sends headers, delivers events, and reports the end of the stream', async () => {
    const fetchImpl = vi.fn(() => streamResponse(['event: ready\ndata: {}\n\n']));
    const onEvent = vi.fn();
    const onError = vi.fn();

    openEventStream('/api/events/stream', {
      headers: { Authorization: 'Bearer t' },
      onEvent,
      onError,
      fetchImpl
    });

    await vi.waitFor(() => expect(onError).toHaveBeenCalledWith({ type: 'closed' }));
    expect(fetchImpl.mock.calls[0][1].headers).toMatchObject({
      Accept: 'text/event-stream',
      Authorization: 'Bearer t'
    });
    expect(onEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'ready' }));
  });

  it('reports HTTP errors with their status', async () => {
    const onError = vi.fn();

    openEventStream('/x', { onError, fetchImpl: () => streamResponse([], 401) });

    await vi.waitFor(() => expect(onError).toHaveBeenCalledWith({ type: 'http', status: 401 }));
  });

  it('stays silent after close()', async () => {
    const onError = vi.fn();
    const stream = openEventStream('/x', {
      onError,
      fetchImpl: () => new Promise(() => {})
    });

    stream.close();
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(onError).not.toHaveBeenCalled();
  });
});
