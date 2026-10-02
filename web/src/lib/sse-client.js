/**
 * Minimal Server-Sent Events client built on fetch.
 *
 * The browser's native EventSource cannot send an Authorization header, and
 * every FlightSignal API call - including the stream - must carry the user's
 * JWT. This client sends headers, parses the `text/event-stream` format, and
 * reports how the stream ended. Reconnection (with a fresh token) is decided
 * by the caller.
 *
 * @param url      stream URL
 * @param options  { headers, onOpen, onEvent({ type, data, id, retry }),
 *                   onError({ type: 'http'|'network'|'closed', status? }) }
 * @returns {{ close(): void }}
 */
export function openEventStream(url, { headers = {}, onOpen, onEvent, onError, fetchImpl } = {}) {
  const controller = new AbortController();
  const doFetch = fetchImpl ?? ((...args) => fetch(...args));
  let closed = false;

  const fail = (details) => {
    if (!closed) {
      closed = true;
      onError?.(details);
    }
  };

  (async () => {
    let response;
    try {
      response = await doFetch(url, {
        headers: { Accept: 'text/event-stream', ...headers },
        cache: 'no-store',
        signal: controller.signal
      });
    } catch (error) {
      fail({ type: 'network', error });
      return;
    }

    if (!response.ok || !response.body) {
      fail({ type: 'http', status: response.status });
      return;
    }

    if (closed) {
      return;
    }
    onOpen?.(response);

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    const parser = createEventStreamParser((event) => {
      if (!closed) {
        onEvent?.(event);
      }
    });

    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        parser.push(decoder.decode(value, { stream: true }));
      }
      fail({ type: 'closed' });
    } catch (error) {
      fail({ type: 'network', error });
    }
  })();

  return {
    close() {
      closed = true;
      controller.abort();
    }
  };
}

/** Incremental parser for the `text/event-stream` format (WHATWG HTML spec). */
export function createEventStreamParser(onEvent) {
  let buffer = '';
  let data = [];
  let eventType = '';
  let lastEventId = null;
  let retry = null;

  function dispatch() {
    if (data.length > 0) {
      onEvent({ type: eventType || 'message', data: data.join('\n'), id: lastEventId, retry });
    }
    data = [];
    eventType = '';
    retry = null;
  }

  function processLine(line) {
    if (line === '') {
      dispatch();
      return;
    }
    if (line.startsWith(':')) {
      return;
    }
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }

    switch (field) {
      case 'event':
        eventType = value;
        break;
      case 'data':
        data.push(value);
        break;
      case 'id':
        if (!value.includes('\0')) {
          lastEventId = value;
        }
        break;
      case 'retry':
        if (/^\d+$/.test(value)) {
          retry = Number(value);
        }
        break;
      default:
        break;
    }
  }

  return {
    push(chunk) {
      buffer += chunk;
      let match;
      while ((match = /\r\n|\r|\n/.exec(buffer)) !== null) {
        // A lone \r at the very end may be the first half of \r\n.
        if (match[0] === '\r' && match.index === buffer.length - 1) {
          break;
        }
        processLine(buffer.slice(0, match.index));
        buffer = buffer.slice(match.index + match[0].length);
      }
    }
  };
}
