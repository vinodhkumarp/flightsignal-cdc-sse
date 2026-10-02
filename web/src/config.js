/**
 * Build-time configuration (Vite environment variables).
 *
 * VITE_AUTH_MODE
 *   dev (default)  sign in with the API's development token issuer
 *                  (requires the API's `dev` profile)
 *   sso            use the organisation's SSO via window.flightSignalSso,
 *                  see src/auth/providers/sso-provider.js
 *
 * VITE_SHOW_FLIGHT_OPERATIONS
 *   The Flight operations page is intentionally hidden in the portfolio
 *   build. Set to "true" (e.g. in web/.env.local) to add, delay, cancel, and
 *   remove flights from the UI.
 */
export const AUTH_MODE = import.meta.env.VITE_AUTH_MODE === 'sso' ? 'sso' : 'dev';

export const SHOW_FLIGHT_OPERATIONS =
  import.meta.env.VITE_SHOW_FLIGHT_OPERATIONS === 'true';
