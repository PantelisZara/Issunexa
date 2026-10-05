import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './ApiError';
import { apiRequest } from './apiRequest';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => vi.unstubAllGlobals());

describe('apiRequest', () => {
    it.each(['1', '60', '300', '3600'])('reads safe Retry-After delta-seconds %s without retrying', async (delay) => {
        fetchMock.mockResolvedValue(Response.json({ status: 429, detail: 'Do not display raw details.' }, {
            status: 429, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': delay },
        }));
        await expect(apiRequest('/api/auth/login', { method: 'POST' })).rejects.toMatchObject({
            status: 429, retryAfterSeconds: Number(delay),
        });
        expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it.each([undefined, '0', '-1', '1.5', 'NaN', 'Infinity', '3601', '999999999999999999999',
        'Mon, 05 Oct 2026 10:00:00 GMT', '60, 120'])('ignores unusable Retry-After %s', async (delay) => {
        fetchMock.mockResolvedValue(new Response(null, {
            status: 429, headers: delay === undefined ? {} : { 'Retry-After': delay },
        }));
        await expect(apiRequest('/api/auth/login')).rejects.toMatchObject({ status: 429, retryAfterSeconds: undefined });
    });

    it('includes session credentials and parses successful JSON', async () => {
        fetchMock.mockResolvedValue(Response.json({ available: true }));

        await expect(apiRequest('/api/example')).resolves.toEqual({ available: true });
        expect(fetchMock).toHaveBeenCalledWith('/api/example', expect.objectContaining({
            credentials: 'include',
        }));
        const headers = new Headers(fetchMock.mock.calls[0]?.[1]?.headers);
        expect(headers.get('Accept')).toContain('application/json');
    });

    it('returns undefined for HTTP 204 without invoking a decoder', async () => {
        fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
        const decode = vi.fn();

        await expect(apiRequest('/api/example', { decode })).resolves.toBeUndefined();
        expect(decode).not.toHaveBeenCalled();
    });

    it('handles an empty successful body', async () => {
        fetchMock.mockResolvedValue(new Response('', { status: 200 }));
        await expect(apiRequest('/api/example')).resolves.toBeUndefined();
    });

    it('preserves Problem Detail fields and extensions in an ApiError', async () => {
        const problem = {
            type: 'about:blank', title: 'Bad Request', status: 400,
            detail: 'Invalid input.', instance: '/api/example',
            errors: [{ field: 'title', message: 'Required' }],
        };
        fetchMock.mockResolvedValue(Response.json(problem, {
            status: 400, headers: { 'Content-Type': 'application/problem+json; charset=utf-8' },
        }));

        const request = apiRequest('/api/example');
        await expect(request).rejects.toBeInstanceOf(ApiError);
        await expect(request).rejects.toMatchObject({ status: 400, problem });
    });

    it('uses HTTP status even if a problem body reports a different status', async () => {
        fetchMock.mockResolvedValue(Response.json({ status: 500 }, {
            status: 401, headers: { 'Content-Type': 'application/problem+json' },
        }));
        await expect(apiRequest('/api/example')).rejects.toMatchObject({ status: 401 });
    });

    it.each(['not json', 'null', '[]', '{"title":123}', '{"status":"400"}'])(
        'keeps a typed HTTP error when the problem body is invalid: %s', async (body) => {
            fetchMock.mockResolvedValue(new Response(body, {
                status: 400, headers: { 'Content-Type': 'application/problem+json' },
            }));
            await expect(apiRequest('/api/example')).rejects.toMatchObject({
                name: 'ApiError', status: 400, problem: undefined,
            });
        },
    );

    it('does not expose a non-JSON error body in its error message', async () => {
        fetchMock.mockResolvedValue(new Response('<html>Internal diagnostic</html>', { status: 502 }));
        await expect(apiRequest('/api/example')).rejects.toMatchObject({
            name: 'ApiError', status: 502, message: 'API request failed (HTTP 502).',
            problem: undefined,
        });
    });

    it.each([
        { 'X-CSRF-TOKEN': 'test-only-value', 'Content-Type': 'application/json' },
        new Headers({ 'X-CSRF-TOKEN': 'test-only-value', 'Content-Type': 'application/json' }),
        [['X-CSRF-TOKEN', 'test-only-value'], ['Content-Type', 'application/json']],
    ] satisfies HeadersInit[])('forwards caller headers, body, method and cancellation', async (headers) => {
        fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
        const controller = new AbortController();
        const body = JSON.stringify({ title: 'Example' });

        await apiRequest('/api/example', { method: 'POST', headers, body, signal: controller.signal });

        const options = fetchMock.mock.calls[0]?.[1];
        expect(options).toMatchObject({ method: 'POST', body, signal: controller.signal, credentials: 'include' });
        const forwarded = new Headers(options?.headers);
        expect(forwarded.get('X-CSRF-TOKEN')).toBe('test-only-value');
        expect(forwarded.get('Content-Type')).toBe('application/json');
        expect(new Headers(headers).has('Accept')).toBe(false);
    });

    it('preserves a caller-supplied Accept header', async () => {
        fetchMock.mockResolvedValue(Response.json({}));
        await apiRequest('/api/example', { headers: { Accept: 'application/json' } });
        expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get('Accept')).toBe('application/json');
    });

    it('supports narrowing untrusted JSON with a caller decoder', async () => {
        fetchMock.mockResolvedValue(Response.json({ available: true }));
        const result = await apiRequest('/api/example', {
            decode(value) {
                if (typeof value !== 'object' || value === null || !('available' in value)
                    || typeof value.available !== 'boolean') {
                    throw new TypeError('Invalid response.');
                }
                return { available: value.available };
            },
        });
        expect(result?.available).toBe(true);
    });

    it('propagates decoder failures', async () => {
        fetchMock.mockResolvedValue(Response.json({}));
        const error = new TypeError('Invalid response.');
        await expect(apiRequest('/api/example', { decode: () => { throw error; } })).rejects.toBe(error);
    });

    it('propagates malformed success JSON', async () => {
        fetchMock.mockResolvedValue(new Response('not json'));
        await expect(apiRequest('/api/example')).rejects.toBeInstanceOf(SyntaxError);
    });

    it('propagates transport failures', async () => {
        const error = new TypeError('Network unavailable');
        fetchMock.mockRejectedValue(error);
        await expect(apiRequest('/api/example')).rejects.toBe(error);
    });
});
