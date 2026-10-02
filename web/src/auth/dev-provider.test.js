import { describe, expect, it, vi } from 'vitest';
import { decodeJwtPayload, isExpired } from './jwt.js';
import { createDevProvider } from './providers/dev-provider.js';

function fakeJwt(payload) {
  const encode = (value) => btoa(JSON.stringify(value)).replace(/=+$/, '');
  return `${encode({ alg: 'HS256' })}.${encode(payload)}.signature`;
}

function memoryStorage() {
  const values = new Map();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key)
  };
}

describe('jwt helpers', () => {
  it('decodes the payload and detects expiry', () => {
    const now = Date.parse('2026-10-02T00:00:00Z');
    const token = fakeJwt({ name: 'Priya', exp: now / 1000 + 3600 });

    expect(decodeJwtPayload(token).name).toBe('Priya');
    expect(isExpired(token, now)).toBe(false);
    expect(isExpired(token, now + 3_600_000)).toBe(true);
    expect(decodeJwtPayload('not-a-jwt')).toBeNull();
  });
});

describe('createDevProvider', () => {
  it('requests a token for the chosen stations and stores it', async () => {
    const token = fakeJwt({ exp: Date.now() / 1000 + 3600 });
    const fetchImpl = vi.fn(() => Promise.resolve(new Response(
      JSON.stringify({ accessToken: token }),
      { status: 200 }
    )));
    const provider = createDevProvider({ storage: memoryStorage(), fetchImpl });

    await provider.signIn({ name: 'Sydney Agent', stations: ['SYD'] });

    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({
      name: 'Sydney Agent',
      stations: ['SYD']
    });
    expect(provider.isSignedIn()).toBe(true);
    expect(await provider.getAccessToken()).toBe(token);

    await provider.signOut();
    expect(provider.isSignedIn()).toBe(false);
  });

  it('drops an expired token', async () => {
    const storage = memoryStorage();
    storage.setItem('flight-signal:dev-access-token', fakeJwt({ exp: Date.now() / 1000 - 10 }));

    const provider = createDevProvider({ storage });

    expect(await provider.getAccessToken()).toBeNull();
  });

  it('surfaces the API error message', async () => {
    const provider = createDevProvider({
      storage: memoryStorage(),
      fetchImpl: () => Promise.resolve(new Response(JSON.stringify({ detail: 'Select at least one station.' }), { status: 400 }))
    });

    await expect(provider.signIn({ name: 'x', stations: [] })).rejects.toThrow('Select at least one station.');
  });
});
