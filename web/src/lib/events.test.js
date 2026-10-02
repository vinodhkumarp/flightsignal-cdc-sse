import { describe, expect, it } from 'vitest';
import { latestEventId, mergeEvents, reconnectDelay } from './events.js';

const event = (eventId, message = 'm' + eventId) => ({ eventId: String(eventId), message });

describe('mergeEvents', () => {
  it('sorts newest first and removes duplicates, preferring incoming copies', () => {
    const merged = mergeEvents(
      [event(1, 'old'), event(3)],
      [event(2), event(1, 'new')]
    );

    expect(merged.map((e) => e.eventId)).toEqual(['3', '2', '1']);
    expect(merged.find((e) => e.eventId === '1').message).toBe('new');
  });

  it('compares IDs numerically, not lexically', () => {
    const merged = mergeEvents([event(9)], [event(10)]);
    expect(merged.map((e) => e.eventId)).toEqual(['10', '9']);
  });
});

describe('latestEventId', () => {
  it('returns the highest ID or the fallback', () => {
    expect(latestEventId([event(4), event(12), event(7)])).toBe(12);
    expect(latestEventId([], 5)).toBe(5);
  });
});

describe('reconnectDelay', () => {
  it('grows exponentially with jitter and is capped at 30 seconds', () => {
    expect(reconnectDelay(0, () => 0)).toBe(500);
    expect(reconnectDelay(0, () => 1)).toBe(1000);
    expect(reconnectDelay(3, () => 1)).toBe(8000);
    expect(reconnectDelay(10, () => 1)).toBe(30000);
  });
});
