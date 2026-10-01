import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { decodeCsrf, decodeSession, getCsrf, getSession, login, logout } from './authApi';

const fetchMock = vi.fn<typeof fetch>();
const csrf = { token: 'synthetic-csrf', headerName: 'X-CUSTOM-CSRF' };
const account = { id: 7, email: 'alice@example.test', displayName: 'Alice', role: 'REQUESTER' };

beforeEach(() => { fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock); });
afterEach(() => vi.unstubAllGlobals());

describe('auth API', () => {
    it('decodes CSRF through the relative cookie-authenticated boundary', async () => {
        fetchMock.mockResolvedValue(Response.json(csrf));
        await expect(getCsrf()).resolves.toEqual(csrf);
        expect(fetchMock).toHaveBeenCalledWith('/api/auth/csrf', expect.objectContaining({
            credentials: 'include', cache: 'no-store',
        }));
    });

    it.each(['REQUESTER', 'AGENT', 'ADMIN'])('decodes only safe %s session fields', async (role) => {
        fetchMock.mockResolvedValue(Response.json({ ...account, role, unexpected: 'discarded' }));
        await expect(getSession()).resolves.toEqual({ ...account, role });
        expect(fetchMock).toHaveBeenCalledWith('/api/auth/session', expect.objectContaining({
            credentials: 'include', cache: 'no-store',
        }));
    });

    it.each([null, undefined, [], {}, { token: '', headerName: 'X-CSRF' },
        { token: 'test', headerName: 1 }, { token: 'test', headerName: 'bad header' },
        { token: 'bad\r\nvalue', headerName: 'X-CSRF' }])('rejects malformed CSRF: %j', (value) => {
        expect(() => decodeCsrf(value)).toThrow('Invalid CSRF response.');
    });

    it.each([null, undefined, [], {}, { ...account, id: '7' }, { ...account, id: -1 },
        { ...account, id: 1.5 }, { ...account, id: Number.MAX_SAFE_INTEGER + 1 },
        { ...account, email: '' }, { ...account, displayName: null }, { ...account, role: 'OWNER' }])(
        'rejects malformed session: %j', (value) => {
            expect(() => decodeSession(value)).toThrow('Invalid authenticated session response.');
        },
    );

    it.each([getCsrf, getSession])('rejects an unexpected empty response', async (request) => {
        fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
        await expect(request()).rejects.toBeInstanceOf(TypeError);
    });

    it('sends only JSON credentials and the server-supplied CSRF header on login', async () => {
        fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
        await login('alice@example.test', 'synthetic test password', csrf);
        const [path, options] = fetchMock.mock.calls[0] ?? [];
        expect(path).toBe('/api/auth/login');
        expect(options).toMatchObject({ method: 'POST', credentials: 'include' });
        expect(JSON.parse(String(options?.body))).toEqual({ email: 'alice@example.test', password: 'synthetic test password' });
        const headers = new Headers(options?.headers);
        expect(headers.get(csrf.headerName)).toBe(csrf.token);
        expect(headers.get('Content-Type')).toBe('application/json');
        expect(headers.has('Authorization')).toBe(false);
    });

    it('posts logout with current CSRF and no credential body', async () => {
        fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
        await logout(csrf);
        const [path, options] = fetchMock.mock.calls[0] ?? [];
        expect(path).toBe('/api/auth/logout');
        expect(options).toMatchObject({ method: 'POST', credentials: 'include' });
        expect(options?.body).toBeUndefined();
        expect(new Headers(options?.headers).get(csrf.headerName)).toBe(csrf.token);
    });
});
