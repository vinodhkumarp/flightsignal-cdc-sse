/**
 * Thin fetch wrapper. Errors carry the server's RFC 9457 problem `detail`
 * (or legacy `error`) message and the HTTP status.
 */
export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

export async function api(path, options = {}) {
  let response;
  try {
    response = await fetch(path, {
      ...options,
      headers: {
        Accept: 'application/json, application/problem+json',
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
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

  if (!response.ok) {
    throw new ApiError(
      body.detail || body.error || `Request failed (${response.status}).`,
      response.status
    );
  }

  return body;
}
