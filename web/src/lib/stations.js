/** Stations offered in the development sign-in and head-office filter. */
export const KNOWN_STATIONS = [
  'SYD', 'MEL', 'BNE', 'PER', 'ADL', 'CBR', 'AKL', 'SIN', 'KUL',
  'HKG', 'NRT', 'HNL', 'LAX', 'YVR', 'JFK', 'DXB', 'LHR'
];

/** "SYD → SIN → LHR" from a flight (falls back to origin → destination). */
export function routeLabel(flight) {
  if (!flight) {
    return '';
  }
  const route = flight.routeStations?.length
    ? flight.routeStations
    : [flight.originAirport, flight.destinationAirport];
  return route.join(' → ');
}
