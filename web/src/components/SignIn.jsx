import { useState } from 'react';
import { KNOWN_STATIONS } from '../lib/stations.js';

const PRESETS = [
  { label: 'Sydney agent', name: 'Sydney Agent', stations: ['SYD'] },
  { label: 'Singapore agent', name: 'Singapore Agent', stations: ['SIN'] },
  { label: 'London agent', name: 'London Agent', stations: ['LHR'] },
  { label: 'Auckland agent', name: 'Auckland Agent', stations: ['AKL'] },
  { label: 'Head office', name: 'Network Control', stations: ['*'] }
];

/**
 * Development sign-in: stands in for the SSO login page. Choose who you are
 * and which stations your token should carry; the API issues a signed JWT
 * with those claims (only when it runs with the `dev` profile).
 */
export function DevSignIn({ onSignIn }) {
  const [name, setName] = useState('Sydney Agent');
  const [stations, setStations] = useState(['SYD']);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const global = stations.includes('*');

  function toggle(station) {
    setStations((current) => {
      const withoutGlobal = current.filter((item) => item !== '*');
      return withoutGlobal.includes(station)
        ? withoutGlobal.filter((item) => item !== station)
        : [...withoutGlobal, station];
    });
  }

  async function submit(event) {
    event.preventDefault();
    setSubmitting(true);
    setError('');
    try {
      await onSignIn({ name: name.trim(), stations });
    } catch (signInError) {
      setError(signInError.message);
      setSubmitting(false);
    }
  }

  return (
    <main className="sign-in">
      <form className="sign-in__card panel" onSubmit={submit}>
        <div className="brand">
          <span className="brand__mark" aria-hidden="true">FS</span>
          <span>
            <strong>FlightSignal</strong>
            <small>Development sign-in</small>
          </span>
        </div>

        <p className="sign-in__intro">
          In production this screen is replaced by your organisation&apos;s SSO. Pick an identity
          to see how notifications are limited to the stations in the user&apos;s token.
        </p>

        <div className="sign-in__presets" role="group" aria-label="Quick identities">
          {PRESETS.map((preset) => (
            <button
              key={preset.label}
              type="button"
              className="station-chip"
              onClick={() => {
                setName(preset.name);
                setStations(preset.stations);
              }}
            >
              {preset.label}
            </button>
          ))}
        </div>

        <label>
          <span>Name</span>
          <input value={name} onChange={(event) => setName(event.target.value)} required />
        </label>

        <fieldset className="sign-in__stations">
          <legend>Stations in token</legend>
          <label className="sign-in__global">
            <input
              type="checkbox"
              checked={global}
              onChange={() => setStations(global ? [] : ['*'])}
            />
            <span>All stations (head office)</span>
          </label>
          <div className="sign-in__station-grid">
            {KNOWN_STATIONS.map((station) => (
              <button
                key={station}
                type="button"
                className={'station-chip' + (!global && stations.includes(station) ? ' is-active' : '')}
                aria-pressed={!global && stations.includes(station)}
                disabled={global}
                onClick={() => toggle(station)}
              >
                {station}
              </button>
            ))}
          </div>
        </fieldset>

        {error && <p className="form-error" role="alert">{error}</p>}

        <button
          className="button button--primary"
          type="submit"
          disabled={submitting || !name.trim() || stations.length === 0}
        >
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </main>
  );
}

/** SSO mode: hand over to the organisation's identity provider. */
export function SsoSignIn({ onSignIn }) {
  const [error, setError] = useState('');

  return (
    <main className="sign-in">
      <div className="sign-in__card panel">
        <div className="brand">
          <span className="brand__mark" aria-hidden="true">FS</span>
          <span>
            <strong>FlightSignal</strong>
            <small>Passenger care</small>
          </span>
        </div>
        <p className="sign-in__intro">Sign in with your organisation account to continue.</p>
        {error && <p className="form-error" role="alert">{error}</p>}
        <button
          className="button button--primary"
          type="button"
          onClick={() => onSignIn().catch((signInError) => setError(signInError.message))}
        >
          Sign in with SSO
        </button>
      </div>
    </main>
  );
}

export function Splash({ message, onRetry }) {
  return (
    <main className="sign-in">
      <div className="sign-in__card panel" role="status">
        <p className="sign-in__intro">{message}</p>
        {onRetry && (
          <button className="button button--secondary" type="button" onClick={onRetry}>
            Retry
          </button>
        )}
      </div>
    </main>
  );
}
