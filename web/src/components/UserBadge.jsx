export function UserBadge({ user, onSignOut }) {
  const scope = user.allStations ? 'All stations' : user.stations.join(' · ');

  return (
    <div className="user-badge">
      <span className="user-badge__avatar" aria-hidden="true">
        {initials(user.name)}
      </span>
      <span className="user-badge__text">
        <strong>{user.name}</strong>
        <small title="Stations from your sign-in token">{scope || 'No stations'}</small>
      </span>
      {onSignOut && (
        <button className="text-button" type="button" onClick={onSignOut}>
          Sign out
        </button>
      )}
    </div>
  );
}

function initials(name = '') {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('') || '?';
}
