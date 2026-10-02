import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, configureApiAuth } from '../api.js';

const AuthContext = createContext(null);

async function loadSession(provider) {
  if (!(await provider.isSignedIn())) {
    return { status: 'signedOut', user: null, error: '' };
  }
  try {
    const user = await api('/api/me');
    return { status: 'signedIn', user, error: '' };
  } catch (error) {
    if (error.status === 401) {
      await provider.signOut();
      return { status: 'signedOut', user: null, error: '' };
    }
    return { status: 'error', user: null, error: error.message };
  }
}

/**
 * Owns the signed-in user (from `GET /api/me`, i.e. what the API derived
 * from the validated JWT) and wires the token into every API call.
 */
export function AuthProvider({ provider, children }) {
  const [session, setSession] = useState({ status: 'loading', user: null, error: '' });
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    let cancelled = false;

    configureApiAuth({
      getAccessToken: () => provider.getAccessToken(),
      onUnauthorized: () => {
        Promise.resolve(provider.signOut()).finally(() => {
          if (!cancelled) {
            setSession({ status: 'signedOut', user: null, error: '' });
          }
        });
      }
    });

    loadSession(provider).then((next) => {
      if (!cancelled) {
        setSession(next);
      }
    });

    return () => {
      cancelled = true;
    };
  }, [provider, generation]);

  const signIn = useCallback(async (details) => {
    await provider.signIn(details);
    setGeneration((value) => value + 1);
  }, [provider]);

  const signOut = useCallback(async () => {
    await provider.signOut();
    setSession({ status: 'signedOut', user: null, error: '' });
  }, [provider]);

  const value = useMemo(() => ({
    ...session,
    mode: provider.mode,
    interactiveSignIn: provider.interactiveSignIn,
    signIn,
    signOut,
    retry: () => setGeneration((current) => current + 1)
  }), [session, provider, signIn, signOut]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return context;
}
