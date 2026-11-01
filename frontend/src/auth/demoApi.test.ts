import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { decodeDemo, getDemo, transitionDemo } from './demoApi';

const fetchMock = vi.fn<typeof fetch>();
const csrf = { token: 'synthetic-token', headerName: 'X-SYNTHETIC-CSRF' };
const expiry = '2026-11-01T12:30:00.123456Z';
beforeEach(() => { fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock); });
afterEach(() => vi.unstubAllGlobals());

describe('demo API contract', () => {
    it.each([
        { enabled: false, active: false, expiresAt: null },
        { enabled: true, active: false, expiresAt: null },
        { enabled: true, active: false, expiresAt: expiry },
        { enabled: true, active: true, expiresAt: expiry },
    ])('decodes runtime metadata and discards extra fields: %j', async (metadata) => {
        fetchMock.mockResolvedValue(Response.json({ ...metadata, workspace: 'discarded' }));
        await expect(getDemo()).resolves.toEqual(metadata);
        expect(fetchMock).toHaveBeenCalledWith('/api/demo', expect.objectContaining({ credentials: 'include', cache: 'no-store' }));
    });
    it.each([undefined, null, [], {}, { enabled: 'true', active: false, expiresAt: null },
        { enabled: false, active: true, expiresAt: expiry }, { enabled: false, active: false, expiresAt: expiry },
        { enabled: true, active: true, expiresAt: null }, { enabled: true, active: true, expiresAt: 'tomorrow' },
        { enabled: true, active: true, expiresAt: '2026-99-99T00:00:00Z' },
        { enabled: true, active: false }, { enabled: true, active: 1, expiresAt: expiry }])('rejects malformed metadata: %j', (metadata) => {
        expect(() => decodeDemo(metadata)).toThrow('Invalid demo response.');
    });
    it.each(['start', 'requester', 'agent'] as const)('posts only the fixed %s route with CSRF and an empty body', async (action) => {
        fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
        await transitionDemo(action, csrf);
        const [path, options] = fetchMock.mock.calls[0]!;
        expect(path).toBe(`/api/demo/${action}`);
        expect(options).toMatchObject({ method: 'POST', credentials: 'include' });
        expect(options?.body).toBeUndefined();
        expect(new Headers(options?.headers).get(csrf.headerName)).toBe(csrf.token);
        expect(new Headers(options?.headers).has('Authorization')).toBe(false);
    });
});
