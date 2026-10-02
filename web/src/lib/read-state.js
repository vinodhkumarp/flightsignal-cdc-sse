/**
 * Per-user, per-browser notification read state. Stored in localStorage when
 * available; every access is guarded because storage can be disabled
 * (private windows, strict privacy settings).
 */

const READ_EVENT_IDS_KEY = 'flight-signal:read-event-ids';
const MAX_STORED_IDS = 1_000;

function keyFor(userId) {
  return userId ? `${READ_EVENT_IDS_KEY}:${userId}` : READ_EVENT_IDS_KEY;
}

export function loadReadEventIds(userId, storage = safeStorage()) {
  try {
    const value = JSON.parse(storage?.getItem(keyFor(userId)) || '[]');
    return new Set(Array.isArray(value) ? value.map(String) : []);
  } catch {
    return new Set();
  }
}

export function saveReadEventIds(ids, userId, storage = safeStorage()) {
  try {
    storage?.setItem(
      keyFor(userId),
      JSON.stringify(Array.from(ids).slice(-MAX_STORED_IDS))
    );
  } catch {
    // Read state still works for the current session.
  }
}

function safeStorage() {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}
