import { flightCode } from './format.js';

export function emptyFilters() {
  return {
    flightNumber: '',
    travelDate: '',
    originAirport: '',
    destinationAirport: '',
    passengerName: '',
    bookingReference: ''
  };
}

/** Prefills the manifest search from a flight notification. */
export function filtersForEvent(event) {
  const flight = event?.flight;
  if (!flight) {
    return emptyFilters();
  }

  return {
    ...emptyFilters(),
    flightNumber: flightCode(flight),
    travelDate: flight.serviceDate,
    originAirport: flight.originAirport,
    destinationAirport: flight.destinationAirport
  };
}

export function passengerQuery(filters) {
  const parameters = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value) {
      parameters.set(key, value);
    }
  });
  return parameters.toString();
}
