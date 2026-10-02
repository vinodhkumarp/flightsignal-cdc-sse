/** Pure helpers for the notification list. */

export function eventNumber(event) {
  return Number(event.eventId);
}

/** Merges two event lists, newest first, keeping the first copy of each ID. */
export function mergeEvents(existing, incoming) {
  const byId = new Map();

  for (const event of [...incoming, ...existing]) {
    if (!byId.has(event.eventId)) {
      byId.set(event.eventId, event);
    }
  }

  return Array.from(byId.values())
    .sort((left, right) => eventNumber(right) - eventNumber(left));
}

export function latestEventId(events, fallback = 0) {
  return events.reduce(
    (latest, event) => Math.max(latest, eventNumber(event)),
    fallback
  );
}

/** Exponential backoff with jitter, capped at 30 seconds. */
export function reconnectDelay(attempt, random = Math.random) {
  const base = Math.min(30_000, 1_000 * 2 ** attempt);
  return Math.round(base / 2 + random() * (base / 2));
}
