import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/ApiError';
import { ticket, ticketPage } from '../test/ticketFixtures';
import { getTickets } from './ticketApi';
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
