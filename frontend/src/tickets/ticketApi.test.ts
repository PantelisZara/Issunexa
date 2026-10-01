import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/ApiError';
import { ticket, ticketPage } from '../test/ticketFixtures';
import { createTicket, getTicket, getTickets } from './ticketApi';
import { decodeTicketPage } from './ticketDecoders';
import { readTicketQuery } from './ticketQuery';

const fetchMock = vi.fn<typeof fetch>();
beforeEach(() => { fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock); });
afterEach(() => vi.unstubAllGlobals());

describe('Ticket API and URL contract', () => {
    it('serializes all backend parameters, escaping literal search punctuation', async () => {
        fetchMock.mockResolvedValue(Response.json(ticketPage()));
        const query = readTicketQuery(new URLSearchParams({
            q: '  login & %_\\  ', status: 'IN_PROGRESS', priority: 'URGENT', category: 'SERVICE_REQUEST',
            sortBy: 'updatedAt', direction: 'asc', page: '2', size: '10',
        }));
        const controller = new AbortController();
        await getTickets(query, controller.signal);
        const [path, options] = fetchMock.mock.calls[0] ?? [];
        expect(path).toBe('/api/tickets?q=login+%26+%25_%5C&status=IN_PROGRESS&priority=URGENT&category=SERVICE_REQUEST&sortBy=updatedAt&direction=asc&page=2&size=10');
        expect(options).toMatchObject({ credentials: 'include', cache: 'no-store', signal: controller.signal });
        expect(new Headers(options?.headers).has('Authorization')).toBe(false);
    });

    it('omits inactive filters and search, and serializes zero-based page/default size', async () => {
        fetchMock.mockResolvedValue(Response.json(ticketPage()));
        await getTickets(readTicketQuery(new URLSearchParams('q=++')));
        expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/tickets?sortBy=createdAt&direction=desc&page=0&size=20');
    });

    it('decodes a page including assigned and unassigned Tickets and discards unknown fields', async () => {
        fetchMock.mockResolvedValue(Response.json({
            ...ticketPage(), internal: 'discarded',
            content: [{ ...ticket, requester: 'discarded', assignee: { ...ticket.assignee, email: 'discarded' } }, ticketPage().content[1]],
        }));
        await expect(getTickets(readTicketQuery(new URLSearchParams()))).resolves.toEqual(ticketPage());
    });

    it.each([
        null, undefined, [], {}, { ...ticketPage(), content: null }, { ...ticketPage(), page: -1 },
        { ...ticketPage(), page: '0' }, { ...ticketPage(), size: 0 }, { ...ticketPage(), size: 101 },
        { ...ticketPage(), totalElements: -1 }, { ...ticketPage(), totalElements: Number.MAX_SAFE_INTEGER + 1 },
        { ...ticketPage(), totalPages: 1.5 }, { ...ticketPage(), first: 'true' }, { ...ticketPage(), last: null },
        ...[
            { id: '42' }, { id: 0 }, { title: null }, { description: 1 }, { status: 'NEW' },
            { priority: 'CRITICAL' }, { category: 'SUPPORT' }, { createdAt: 'not a date' }, { updatedAt: null },
            { assignee: undefined }, { assignee: {} }, { assignee: [] }, { assignee: { id: -1, displayName: 'Agent' } },
            { assignee: { id: 7, displayName: null } },
        ].map((override) => ({ ...ticketPage(), content: [{ ...ticket, ...override }] })),
    ])('rejects malformed external payload case %#', (value) => {
        expect(() => decodeTicketPage(value)).toThrow(TypeError);
    });

    it.each([new Response(null, { status: 204 }), new Response('invalid JSON'), Response.json({})])(
        'rejects malformed or empty HTTP success case %#', async (response) => {
            fetchMock.mockResolvedValue(response);
            await expect(getTickets(readTicketQuery(new URLSearchParams()))).rejects.toBeInstanceOf(Error);
        },
    );

    it('preserves structured Problem Details at the API boundary', async () => {
        const problem = { status: 400, title: 'Invalid request parameter', detail: 'Internal diagnostic' };
        fetchMock.mockImplementation(async () => Response.json(problem, { status: 400, headers: { 'Content-Type': 'application/problem+json' } }));
        await expect(getTickets(readTicketQuery(new URLSearchParams()))).rejects.toMatchObject({
            status: 400, problem,
        });
        await expect(getTickets(readTicketQuery(new URLSearchParams()))).rejects.toBeInstanceOf(ApiError);
    });

    it.each([
        'status=UNKNOWN&priority=CRITICAL&category=SUPPORT&sortBy=id&direction=ASC&page=-1&size=101&q=' + 'a'.repeat(101),
        'status=OPEN&status=CLOSED&priority=LOW&priority=HIGH&category=OTHER&category=INCIDENT&sortBy=title&sortBy=createdAt&direction=asc&direction=desc&page=1&page=2&size=10&size=20&q=one&q=two',
        'page=2147483648&size=0', 'page=1.5&size=1.5',
    ])('falls back safely for invalid/repeated URL values case %#', (search) => {
        expect(readTicketQuery(new URLSearchParams(search))).toEqual(readTicketQuery(new URLSearchParams()));
    });

    it.each([1, 37, 100])('supports backend page size %s from a shared URL', (size) => {
        expect(readTicketQuery(new URLSearchParams(`page=3&size=${size}`))).toMatchObject({ page: 3, size });
    });

    it('accepts explicit empty out-of-range pages without inventing totals', () => {
        const page = ticketPage({ content: [], page: 5, totalElements: 2, first: false, last: true });
        expect(decodeTicketPage(page)).toEqual(page);
    });
});

describe('Ticket creation and detail API', () => {
    const csrf = { token: 'synthetic-current-csrf', headerName: 'X-CUSTOM-CSRF' };
    const input = { title: ' New ticket ', description: 'Line one\nLine two', priority: 'HIGH', category: 'SERVICE_REQUEST' } as const;

    it('POSTs only backend-supported fields with current CSRF and decodes the created Ticket', async () => {
        fetchMock.mockResolvedValue(Response.json({ ...ticket, assignee: null }, { status: 201 }));
        const controller = new AbortController();
        await expect(createTicket({ ...input, ...{ id: 99, status: 'CLOSED', requester: 7, assignee: 7 } }, csrf, controller.signal))
            .resolves.toEqual({ ...ticket, assignee: null });
        const [path, options] = fetchMock.mock.calls[0] ?? [];
        expect(path).toBe('/api/tickets');
        expect(options).toMatchObject({ method: 'POST', credentials: 'include', signal: controller.signal });
        expect(JSON.parse(String(options?.body))).toEqual(input);
        const headers = new Headers(options?.headers);
        expect(headers.get(csrf.headerName)).toBe(csrf.token);
        expect(headers.get('Content-Type')).toBe('application/json');
        expect(headers.has('Authorization')).toBe(false);
    });

    it.each([ticket.assignee, null])('GETs and decodes a Ticket with assignee %j', async (assignee) => {
        fetchMock.mockResolvedValue(Response.json({ ...ticket, assignee, requester: 'discarded' }));
        const controller = new AbortController();
        await expect(getTicket('42', controller.signal)).resolves.toEqual({ ...ticket, assignee });
        expect(fetchMock).toHaveBeenCalledWith('/api/tickets/42', expect.objectContaining({
            cache: 'no-store', credentials: 'include', signal: controller.signal,
        }));
        expect(fetchMock.mock.calls[0]?.[1]?.body).toBeUndefined();
        expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).has(csrf.headerName)).toBe(false);
    });

    it('encodes detail path input and preserves a full Long route identifier', async () => {
        fetchMock.mockImplementation(async () => Response.json(ticket));
        await getTicket('9223372036854775807');
        expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/tickets/9223372036854775807');
        await getTicket('42/../auth?query=value');
        expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/tickets/42%2F..%2Fauth%3Fquery%3Dvalue');
    });

    it.each(['create', 'detail'] as const)('rejects malformed %s response payloads', async (operation) => {
        for (const payload of [null, {}, { ...ticket, id: '42' }, { ...ticket, category: 'SUPPORT' }, { ...ticket, assignee: {} }]) {
            fetchMock.mockResolvedValueOnce(Response.json(payload, { status: operation === 'create' ? 201 : 200 }));
            const request = operation === 'create' ? createTicket(input, csrf) : getTicket('42');
            await expect(request).rejects.toBeInstanceOf(TypeError);
        }
    });

    it.each(['create', 'detail'] as const)('rejects empty or invalid JSON %s success without fabricating a Ticket', async (operation) => {
        for (const response of [new Response(null, { status: 204 }), new Response('invalid JSON')]) {
            fetchMock.mockResolvedValueOnce(response);
            await expect(operation === 'create' ? createTicket(input, csrf) : getTicket('42')).rejects.toBeInstanceOf(Error);
        }
    });

    it.each([
        ['create', 400], ['create', 403], ['create', 401], ['create', 500],
        ['detail', 404], ['detail', 401], ['detail', 500],
    ] as const)('preserves %s HTTP %s as ApiError without replaying requests', async (operation, status) => {
        const problem = { status, detail: 'Internal diagnostic' };
        fetchMock.mockResolvedValue(Response.json(problem, { status, headers: { 'Content-Type': 'application/problem+json' } }));
        const request = operation === 'create' ? createTicket(input, csrf) : getTicket('42');
        await expect(request).rejects.toBeInstanceOf(ApiError);
        await expect(request).rejects.toMatchObject({ status, problem });
        expect(fetchMock).toHaveBeenCalledTimes(1);
    });
});
