/**
 * Organisation SSO integration seam.
 *
 * FlightSignal never handles passwords. In an SSO environment the host
 * application (or a small adapter) supplies these functions, typically
 * backed by MSAL, oidc-client-ts, Okta Auth JS, or a BFF session:
 *
 *   window.flightSignalSso = {
 *     isSignedIn: () => boolean,
 *     getAccessToken: async () => string | null,  // e.g. acquireTokenSilent
 *     signIn: async () => void,                   // e.g. loginRedirect
 *     signOut: async () => void                   // e.g. logoutRedirect
 *   };
 *
 * The access token must carry the stations claim the API is configured to
 * read (app.security.stations-claim, default "stations").
 *
 * Example with MSAL (@azure/msal-browser):
 *
 *   const msal = new PublicClientApplication({ auth: { clientId, authority } });
 *   await msal.initialize();
 *   const scopes = ['api://flightsignal/access_as_user'];
 *   window.flightSignalSso = {
 *     isSignedIn: () => msal.getAllAccounts().length > 0,
 *     getAccessToken: async () => {
 *       const account = msal.getAllAccounts()[0];
 *       if (!account) return null;
 *       const result = await msal.acquireTokenSilent({ account, scopes });
 *       return result.accessToken;
 *     },
 *     signIn: () => msal.loginRedirect({ scopes }),
 *     signOut: () => msal.logoutRedirect()
 *   };
 */
export function createSsoProvider(adapter = globalThis.flightSignalSso) {
  const required = ['isSignedIn', 'getAccessToken', 'signIn', 'signOut'];
  const missing = required.filter((name) => typeof adapter?.[name] !== 'function');
  if (missing.length > 0) {
    throw new Error(
      'SSO is enabled (VITE_AUTH_MODE=sso) but window.flightSignalSso is missing: '
        + missing.join(', ')
        + '. See src/auth/providers/sso-provider.js.'
    );
  }

  return {
    mode: 'sso',
    interactiveSignIn: false,
    isSignedIn: () => adapter.isSignedIn(),
    getAccessToken: () => adapter.getAccessToken(),
    signIn: () => adapter.signIn(),
    signOut: () => adapter.signOut()
  };
}
