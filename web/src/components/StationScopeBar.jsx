import { KNOWN_STATIONS } from '../lib/stations.js';

/**
 * Shows which stations' notifications are on screen and lets the user narrow
 * the view to some of their stations. The choices come from the user's JWT
 * claims (via /api/me); the API enforces the same limits, so this is a view
 * filter, not the security boundary.
 */
export function StationScopeBar({ user, selected, onChange }) {
  const choices = user.allStations ? KNOWN_STATIONS : user.stations;

  if (!user.allStations && choices.length === 0) {
    return (
      <div className="scope-bar scope-bar--empty" role="status">
        Your account has no stations assigned, so no flight notifications are shown.
        Ask your administrator to add stations to your profile.
      </div>
    );
  }

  function toggle(station) {
    const next = selected.includes(station)
      ? selected.filter((item) => item !== station)
      : [...selected, station].sort();
    onChange(next);
  }

  const showingAll = selected.length === 0;

  return (
    <div className="scope-bar" role="group" aria-label="Station filter">
      <span className="scope-bar__label">
        {user.allStations ? 'Network view' : 'Your stations'}
      </span>
      <button
        type="button"
        className={'station-chip' + (showingAll ? ' is-active' : '')}
        aria-pressed={showingAll}
        onClick={() => onChange([])}
      >
        {user.allStations ? 'All stations' : 'All mine'}
      </button>
      {choices.map((station) => (
        <button
          key={station}
          type="button"
          className={'station-chip' + (selected.includes(station) ? ' is-active' : '')}
          aria-pressed={selected.includes(station)}
          onClick={() => toggle(station)}
        >
          {station}
        </button>
      ))}
    </div>
  );
}
