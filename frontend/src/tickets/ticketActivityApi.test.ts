import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/ApiError';
import { comment, commentPage, historyEntries, historyPage } from '../test/ticketFixtures';
import { createTicketComment, getTicketComments, getTicketHistory } from './ticketApi';
import { decodeTicketComment, decodeTicketCommentPage, decodeTicketHistoryEntry, decodeTicketHistoryPage } from './ticketDecoders';

const fetchMock = vi.fn<typeof fetch>();
const csrf = { token: 'synthetic-token', headerName: 'X-CUSTOM-CSRF' };
beforeEach(() => { fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock); });
afterEach(() => vi.unstubAllGlobals());

describe('Comments and history API contracts', () => {
    it.each(['comments', 'history'] as const)('GETs %s with only supported page parameters and decodes the page', async (kind) => {
        const payload = kind === 'comments' ? commentPage() : historyPage();
        fetchMock.mockResolvedValue(Response.json(payload));
        const controller = new AbortController();
        const get = kind === 'comments' ? getTicketComments : getTicketHistory;
        await expect(get('42', 2, 7, controller.signal)).resolves.toEqual(payload);
        expect(fetchMock.mock.calls[0]?.[0]).toBe(`/api/tickets/42/${kind}?page=2&size=7`);
        const options = fetchMock.mock.calls[0]?.[1];
        expect(options).toMatchObject({ credentials: 'include', cache: 'no-store', signal: controller.signal });
        expect(options?.method ?? 'GET').toBe('GET');
        expect(options?.body).toBeUndefined();
        expect(new Headers(options?.headers).has(csrf.headerName)).toBe(false);
    });

    it.each(['comments', 'history'] as const)('encodes %s identifiers and serializes default pagination', async (kind) => {
        fetchMock.mockResolvedValue(Response.json(kind === 'comments' ? commentPage([]) : historyPage([])));
        await (kind === 'comments' ? getTicketComments : getTicketHistory)('42/../auth?x=1');
        expect(fetchMock.mock.calls[0]?.[0]).toBe(`/api/tickets/42%2F..%2Fauth%3Fx%3D1/${kind}?page=0&size=20`);
    });

    it('POSTs only the body with current CSRF and decodes the server-created comment', async () => {
        fetchMock.mockResolvedValue(Response.json({ ...comment, author: { ...comment.author, email: 'discard@example.test' } }, { status: 201 }));
        const controller = new AbortController();
        await expect(createTicketComment('42/../auth', '  Body\nSecond line  ', csrf, controller.signal)).resolves.toEqual(comment);
        const [path, options] = fetchMock.mock.calls[0] ?? [];
        expect(path).toBe('/api/tickets/42%2F..%2Fauth/comments');
        expect(options).toMatchObject({ method: 'POST', credentials: 'include', signal: controller.signal });
        expect(JSON.parse(String(options?.body))).toEqual({ body: '  Body\nSecond line  ' });
        expect(new Headers(options?.headers).get(csrf.headerName)).toBe(csrf.token);
        expect(new Headers(options?.headers).get('Content-Type')).toBe('application/json');
        expect(new Headers(options?.headers).has('Authorization')).toBe(false);
    });

    it('decodes every history event with exact nullable fields and discards extra data', () => {
        expect(decodeTicketHistoryPage({ ...historyPage(), internal: true, content: historyEntries.map((entry) => ({ ...entry, internal: 'discard' })) })).toEqual(historyPage());
        for (const entry of historyEntries) expect(decodeTicketHistoryEntry(entry)).toEqual(entry);
    });

    it('preserves server order, including timestamp ties, and explicit empty page metadata', () => {
        const comments = [comment, { ...comment, id: 13 }];
        expect(decodeTicketCommentPage(commentPage(comments)).content).toEqual(comments);
        expect(decodeTicketHistoryPage(historyPage()).content.map((entry) => entry.id)).toEqual([9, 8, 7]);
        const empty = commentPage([], { page: 4, first: false, totalPages: 2, totalElements: 30 });
        expect(decodeTicketCommentPage(empty)).toEqual(empty);
    });

    it.each([
        null, [], {}, { ...comment, id: 0 }, { ...comment, id: '12' }, { ...comment, body: null },
        { ...comment, body: '' }, { ...comment, body: 'x'.repeat(4001) }, { ...comment, author: null },
        { ...comment, author: {} }, { ...comment, author: { id: 3, displayName: null } },
        { ...comment, createdAt: 'not a timestamp' }, { ...comment, createdAt: undefined },
    ])('rejects malformed comment case %#', (value) => {
        expect(() => decodeTicketComment(value)).toThrow(TypeError);
    });

    it.each([
        null, [], {}, ...[
            { id: 0 }, { id: '9' }, { actor: null }, { actor: {} }, { createdAt: 'yesterday' },
            { type: 'COMMENT_ADDED' }, { type: undefined }, { previousStatus: null }, { newStatus: null },
            { newStatus: 'NEW' }, { previousStatus: 'NEW' }, { newStatus: 'OPEN' }, { assignee: comment.author },
        ].map((change) => ({ ...historyEntries[0], ...change })),
        { ...historyEntries[1], assignee: null }, { ...historyEntries[1], assignee: {} },
        { ...historyEntries[1], newStatus: 'OPEN' }, { ...historyEntries[1], previousStatus: undefined },
        { ...historyEntries[2], newStatus: 'CLOSED' }, { ...historyEntries[2], previousStatus: 'OPEN' },
        { ...historyEntries[2], assignee: undefined },
    ])('rejects malformed history event case %#', (value) => {
        expect(() => decodeTicketHistoryEntry(value)).toThrow(TypeError);
    });

    it.each(['comments', 'history'] as const)('rejects malformed %s page metadata and entries', (kind) => {
        const decode = kind === 'comments' ? decodeTicketCommentPage : decodeTicketHistoryPage;
        const page = kind === 'comments' ? commentPage() : historyPage();
        for (const invalid of [null, {}, { ...page, content: null }, { ...page, content: [{}] },
            { ...page, page: -1 }, { ...page, size: 0 }, { ...page, size: 101 }, { ...page, totalPages: '1' },
            { ...page, totalElements: -1 }, { ...page, first: null }, { ...page, last: undefined }]) {
            expect(() => decode(invalid)).toThrow(TypeError);
        }
    });

    const operations = {
        comments: () => getTicketComments('42'), history: () => getTicketHistory('42'), create: () => createTicketComment('42', 'Body', csrf),
    };
    it.each(['comments', 'history', 'create'] as const)('rejects malformed %s success without replay', async (kind) => {
        for (const response of [Response.json({}), new Response(null, { status: 204 }), new Response('invalid JSON')]) {
            fetchMock.mockClear();
            fetchMock.mockResolvedValueOnce(response);
            await expect(operations[kind]()).rejects.toBeInstanceOf(Error);
            expect(fetchMock).toHaveBeenCalledTimes(1);
        }
    });

    it.each(['comments', 'history', 'create'] as const)('preserves %s API errors without replay', async (kind) => {
        for (const status of [400, 401, 403, 404, 500]) {
            fetchMock.mockClear();
            const problem = { status, title: 'Backend error', detail: 'Internal diagnostic' };
            fetchMock.mockResolvedValueOnce(Response.json(problem, { status, headers: { 'Content-Type': 'application/problem+json' } }));
            const request = operations[kind]();
            await expect(request).rejects.toBeInstanceOf(ApiError);
            await expect(request).rejects.toMatchObject({ status, problem });
            expect(fetchMock).toHaveBeenCalledTimes(1);
        }
    });
});
