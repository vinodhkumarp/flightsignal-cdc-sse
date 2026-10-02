/**
 * Fetch wrapper used by every API call.
 *
 * - Adds `Authorization: Bearer <token>` from the configured auth provider.
 * - Surfaces the server's RFC 9457 problem `detail` and the HTTP status as
 *   an `ApiError`.
 * - Reports 401 responses to the auth layer (expired or revoked token).
 */
export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

let tokenSource = async () => null;
let unauthorizedHandler = () => {};

/** Called once by the auth layer. */
export function configureApiAuth({ getAccessToken, onUnauthorized }) {
  tokenSource = getAccessToken ?? (async () => null);
  unauthorizedHandler = onUnauthorized ?? (() => {});
}

export async function authHeaders() {
  const token = await tokenSource();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export function notifyUnauthorized() {
  unauthorizedHandler();
}

export async function api(path, options = {}) {
  let response;
  try {
    response = await fetch(path, {
      ...options,
      headers: {
        Accept: 'application/json, application/problem+json',
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
        ...(await authHeaders()),
        ...options.headers
      }
    });
  } catch (networkError) {
    if (networkError?.name === 'AbortError') {
      throw networkError;
    }
    throw new ApiError('The server could not be reached. Check your connection.', 0);
  }

  const body = response.status === 204
    ? {}
    : await response.json().catch(() => ({}));

  if (response.status === 401) {
    notifyUnauthorized();
  }

  if (!response.ok) {
    throw new ApiError(
      body.detail || body.error || defaultMessage(response.status),
      response.status
    );
  }

  return body;
}

function defaultMessage(status) {
  if (status === 401) return 'Your session has expired. Please sign in again.';
  if (status === 403) return 'You do not have access to this information.';
  return `Request failed (${status}).`;
}
