/** Display helpers shared across the UI. */

export function eventIcon(type = '') {
  if (type.includes('delayed')) return '↗';
  if (type.includes('cancelled') || type.includes('removed')) return '×';
  if (type.includes('gate')) return 'G';
  if (type.includes('added')) return '+';
  return '•';
}

export function flightCode(flight) {
  return flight ? flight.carrierCode + flight.flightNumber : '';
}

export function formatInTimezone(value, timeZone) {
  if (!value) {
    return '—';
  }

  return new Intl.DateTimeFormat('en-AU', {
    timeZone,
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZoneName: 'short'
  }).format(new Date(value));
}

export function timeOnly(value, timeZone) {
  if (!value) {
    return '—';
  }

  return new Intl.DateTimeFormat('en-AU', {
    timeZone,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(new Date(value));
}

export function formatEventTime(value, { withYear = false } = {}) {
  return new Intl.DateTimeFormat('en-AU', {
    day: '2-digit',
    month: 'short',
    ...(withYear ? { year: 'numeric' } : {}),
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value));
}

export function formatClockTime(value) {
  return new Intl.DateTimeFormat('en-AU', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).format(new Date(value));
}

export function displayEnum(value) {
  if (!value) {
    return '—';
  }
  return value
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}
