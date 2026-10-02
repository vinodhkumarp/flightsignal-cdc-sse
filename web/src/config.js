/**
 * Feature flags.
 *
 * The Flight operations page is intentionally hidden in the portfolio build.
 * Enable it locally with VITE_SHOW_FLIGHT_OPERATIONS=true (e.g. in
 * web/.env.local) to add, delay, cancel, and remove flights from the UI.
 */
export const SHOW_FLIGHT_OPERATIONS =
  import.meta.env.VITE_SHOW_FLIGHT_OPERATIONS === 'true';
