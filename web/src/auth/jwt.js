/**
 * Reads a JWT payload for display and expiry checks only. The browser never
 * trusts these claims for authorisation; the API validates every token.
 */
export function decodeJwtPayload(token) {
  try {
    const [, payload] = token.split('.');
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
    const json = decodeURIComponent(
      Array.from(atob(padded), (char) => '%' + char.charCodeAt(0).toString(16).padStart(2, '0')).join('')
    );
    return JSON.parse(json);
  } catch {
    return null;
  }
}

/** True when the token expires within `skewSeconds`. */
export function isExpired(token, now = Date.now(), skewSeconds = 30) {
  const exp = decodeJwtPayload(token)?.exp;
  return typeof exp === 'number' && exp * 1000 <= now + skewSeconds * 1000;
}
