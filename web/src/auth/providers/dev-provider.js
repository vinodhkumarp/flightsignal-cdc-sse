import { isExpired } from '../jwt.js';

const TOKEN_KEY = 'flight-signal:dev-access-token';

/**
 * Development identity provider: obtains a JWT from the API's
 * `POST /api/dev/token` (only enabled with the `dev` profile) for a chosen
 * name and set of stations. Lets the station-scoped UI be demoed without an
 * SSO tenant. The token lives in sessionStorage, so it ends with the tab.
 */
export function createDevProvider({ storage = sessionStorageOrNull(), fetchImpl } = {}) {
  const doFetch = fetchImpl ?? ((...args) => fetch(...args));

  function storedToken() {
    let token;
    try {
      token = storage?.getItem(TOKEN_KEY) ?? null;
    } catch {
      token = null;
    }
    if (token && isExpired(token)) {
      clear();
      return null;
    }
    return token;
  }

  function clear() {
    try {
      storage?.removeItem(TOKEN_KEY);
    } catch {
      // Storage unavailable; nothing to clear.
    }
  }

  return {
    mode: 'dev',
    interactiveSignIn: true,

    isSignedIn() {
      return Boolean(storedToken());
    },

    async getAccessToken() {
      return storedToken();
    },

    async signIn({ name, stations }) {
      const response = await doFetch('/api/dev/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, stations })
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) {
        throw new Error(body.detail || 'Sign-in failed. Is the API running with the dev profile?');
      }
      try {
        storage?.setItem(TOKEN_KEY, body.accessToken);
      } catch {
        throw new Error('Session storage is unavailable in this browser.');
      }
    },

    async signOut() {
      clear();
    }
  };
}

function sessionStorageOrNull() {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}
