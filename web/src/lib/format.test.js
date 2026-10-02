import { describe, expect, it } from 'vitest';
import { displayEnum, eventIcon, flightCode, timeOnly } from './format.js';

describe('format helpers', () => {
  it('chooses an icon per event type', () => {
    expect(eventIcon('flight.departure.delayed')).toBe('↗');
    expect(eventIcon('flight.cancelled')).toBe('×');
    expect(eventIcon('flight.gate.changed')).toBe('G');
    expect(eventIcon('flight.added')).toBe('+');
    expect(eventIcon(undefined)).toBe('•');
  });

  it('builds a flight code', () => {
    expect(flightCode({ carrierCode: 'QF', flightNumber: '11' })).toBe('QF11');
    expect(flightCode(null)).toBe('');
  });

  it('formats enum values for display', () => {
    expect(displayEnum('PREMIUM_ECONOMY')).toBe('Premium Economy');
    expect(displayEnum(null)).toBe('—');
  });

  it('formats a UTC instant in the airport timezone', () => {
    expect(timeOnly('2026-10-02T23:30:00Z', 'Australia/Sydney')).toBe('09:30');
    expect(timeOnly(null, 'Australia/Sydney')).toBe('—');
  });
});
