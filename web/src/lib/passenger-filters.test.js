import { describe, expect, it } from 'vitest';
import { filtersForEvent, passengerQuery } from './passenger-filters.js';

describe('filtersForEvent', () => {
  it('prefills the manifest search from a notification', () => {
    const filters = filtersForEvent({
      flight: {
        carrierCode: 'QF',
        flightNumber: '11',
        serviceDate: '2026-10-03',
        originAirport: 'SYD',
        destinationAirport: 'LAX'
      }
    });

    expect(filters).toMatchObject({
      flightNumber: 'QF11',
      travelDate: '2026-10-03',
      originAirport: 'SYD',
      destinationAirport: 'LAX',
      passengerName: '',
      bookingReference: ''
    });
  });

  it('returns empty filters without a flight', () => {
    expect(filtersForEvent(null).flightNumber).toBe('');
  });
});

describe('passengerQuery', () => {
  it('omits empty filters', () => {
    expect(passengerQuery({ flightNumber: 'QF11', travelDate: '2026-10-03', passengerName: '' }))
      .toBe('flightNumber=QF11&travelDate=2026-10-03');
  });
});
