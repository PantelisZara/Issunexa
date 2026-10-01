import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation, useNavigate } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../app/App';
import { ticket, ticketPage } from '../test/ticketFixtures';
import type { TicketPage } from './ticketTypes';

const fetchMock = vi.fn<typeof fetch>();
const account = { id: 7, email: 'synthetic@example.test', displayName: 'Synthetic Requester', role: 'REQUESTER' };

function Navigation() {
    const location = useLocation();
    const navigate = useNavigate();
    return <>
        <output aria-label="Current URL">{location.pathname}{location.search}</output>
        <button onClick={() => { void navigate(-1); }}>Back</button>
        <button onClick={() => { void navigate(1); }}>Forward</button>
    </>;
}

function renderWorkspace(search = '') {
    return render(<MemoryRouter initialEntries={[`/app/tickets${search}`]}><App /><Navigation /></MemoryRouter>);
}

function params() {
    return new URL(screen.getByLabelText('Current URL').textContent ?? '', 'http://localhost').searchParams;
}

function ticketRequests() {
    return fetchMock.mock.calls.filter(([path]) => String(path).startsWith('/api/tickets?'));
}

function respondWith(handler: (url: URL, options?: RequestInit) => Promise<Response>) {
    fetchMock.mockImplementation((path, options) => {
        if (path === '/api/auth/csrf') return Promise.resolve(Response.json({ token: 'synthetic-csrf', headerName: 'X-CSRF-TOKEN' }));
        if (path === '/api/auth/session') return Promise.resolve(Response.json(account));
        return handler(new URL(String(path), 'http://localhost'), options);
    });
}

function respondPage(overrides: Partial<TicketPage> = {}) {
    respondWith(async (url) => Response.json(ticketPage({
        page: Number(url.searchParams.get('page')), size: Number(url.searchParams.get('size')), ...overrides,
    })));
}

beforeEach(() => { fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock); respondPage(); });
afterEach(() => vi.unstubAllGlobals());

describe('Ticket workspace', () => {
    it('shows loading with no stale Ticket data', async () => {
        respondWith(() => new Promise(() => {}));
        renderWorkspace();
        expect(await screen.findByText('Loading tickets…')).toBeVisible();
        expect(screen.queryByRole('table')).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Sign out' })).toBeVisible();
    });

    it('renders only returned Ticket fields, assigned/unassigned states and server totals', async () => {
        respondPage({ totalElements: 41, totalPages: 3, last: false });
        renderWorkspace();
        const table = await screen.findByRole('table');
        expect(within(table).getByRole('rowheader', { name: ticket.title })).toBeVisible();
        for (const text of ['Open', 'High', 'Incident', 'Access request', 'Alice Agent', 'Unassigned']) {
            expect(within(table).getAllByText(text).length).toBeGreaterThan(0);
        }
        expect(table.querySelector('time')).toHaveAttribute('datetime', ticket.createdAt);
        expect(within(screen.getByRole('main')).getByRole('status')).toHaveTextContent('41 tickets');
        expect(screen.getByText('Page 1 · 3 total pages')).toBeVisible();
        expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled();
        expect(screen.queryByRole('link', { name: ticket.title })).not.toBeInTheDocument();
    });

    it('renders an empty result with disabled pagination', async () => {
        respondPage({ content: [], totalElements: 0, totalPages: 0 });
        renderWorkspace();
        expect(await screen.findByRole('heading', { name: 'No tickets found' })).toBeVisible();
        expect(within(screen.getByRole('main')).getByRole('status')).toHaveTextContent('0 tickets');
        expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    });

    it.each([400, 403, 500, 'offline', 'malformed'] as const)('renders a safe recoverable %s error and retries', async (failure) => {
        respondWith(async () => {
            if (failure === 'offline') throw new TypeError('Internal stack trace');
            if (failure === 'malformed') return Response.json({ internal: 'Internal diagnostic' });
            return Response.json({ detail: 'Internal diagnostic' }, { status: failure, headers: { 'Content-Type': 'application/problem+json' } });
        });
        renderWorkspace('?status=OPEN&page=2');
        expect(await screen.findByRole('alert')).toBeVisible();
        expect(screen.queryByText(/Internal/)).not.toBeInTheDocument();
        respondPage();
        await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
        expect(await screen.findByRole('table')).toBeVisible();
        expect(ticketRequests()).toHaveLength(2);
        expect(params().get('page')).toBe('2');
        expect(params().get('status')).toBe('OPEN');
    });

    it('submits search explicitly, updates the URL/request and resets the page', async () => {
        renderWorkspace('?page=3&status=OPEN&size=10');
        await screen.findByRole('table');
        const user = userEvent.setup();
        await user.type(screen.getByRole('searchbox'), ' printer & login ');
        expect(ticketRequests()).toHaveLength(1);
        await user.click(screen.getByRole('button', { name: 'Search' }));
        await screen.findByRole('table');
        expect(params().get('q')).toBe('printer & login');
        expect(params().get('page')).toBe('0');
        expect(params().get('status')).toBe('OPEN');
        expect(params().get('size')).toBe('10');
        const request = new URL(String(ticketRequests()[1]?.[0]), 'http://localhost');
        expect(request.searchParams.get('q')).toBe('printer & login');
    });

    it.each([
        ['Status', 'status', 'RESOLVED'], ['Priority', 'priority', 'URGENT'],
        ['Category', 'category', 'SERVICE_REQUEST'], ['Sort by', 'sortBy', 'title'],
        ['Sort direction', 'direction', 'asc'], ['Tickets per page', 'size', '50'],
    ])('changes %s through URL state and resets page', async (label, name, value) => {
        renderWorkspace('?page=2&q=printer');
        await screen.findByRole('table');
        await userEvent.selectOptions(screen.getByLabelText(label), value);
        await screen.findByRole('table');
        expect(params().get(name)).toBe(value);
        expect(params().get('page')).toBe('0');
        expect(params().get('q')).toBe('printer');
        expect(new URL(String(ticketRequests()[1]?.[0]), 'http://localhost').searchParams.get(name)).toBe(value);
    });

    it('removes an inactive filter from the URL and request', async () => {
        renderWorkspace('?status=OPEN&page=2');
        await screen.findByRole('table');
        await userEvent.selectOptions(screen.getByLabelText('Status'), '');
        await screen.findByRole('table');
        expect(params().has('status')).toBe(false);
        expect(String(ticketRequests()[1]?.[0])).not.toContain('status=');
        expect(params().get('page')).toBe('0');
    });

    it.each(['?q=printer&status=OPEN&priority=HIGH&category=OTHER&sortBy=title&direction=asc&page=2&size=10', '?status=OPEN'])('clears applied filters and draft search case %#', async (search) => {
        renderWorkspace(search);
        await screen.findByRole('table');
        await userEvent.type(screen.getByRole('searchbox'), ' unsent draft');
        await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
        await screen.findByRole('table');
        expect(params().toString()).toBe('');
        expect(screen.getByRole('searchbox')).toHaveValue('');
        expect(screen.getByLabelText('Sort by')).toHaveValue('createdAt');
        expect(ticketRequests().at(-1)?.[0]).toBe('/api/tickets?sortBy=createdAt&direction=desc&page=0&size=20');
    });

    it('uses response page flags and numbers for next/previous, preserving other parameters', async () => {
        respondWith(async (url) => {
            const page = Number(url.searchParams.get('page'));
            return Response.json(ticketPage({ page, totalPages: 2, totalElements: 21, first: page === 0, last: page === 1 }));
        });
        renderWorkspace('?q=printer&status=OPEN&size=20&sortBy=title&direction=asc');
        await screen.findByRole('table');
        const user = userEvent.setup();
        await user.click(screen.getByRole('button', { name: 'Next' }));
        await screen.findByText('Page 2 · 2 total pages');
        expect(params().get('page')).toBe('1');
        expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled();
        expect(params().get('q')).toBe('printer');
        expect(params().get('status')).toBe('OPEN');
        expect(params().get('size')).toBe('20');
        expect(params().get('sortBy')).toBe('title');
        expect(params().get('direction')).toBe('asc');
        await user.click(screen.getByRole('button', { name: 'Previous' }));
        await screen.findByText('Page 1 · 2 total pages');
        expect(params().get('page')).toBe('0');
    });

    it('restores applied controls through browser back/forward navigation', async () => {
        renderWorkspace('?q=printer&page=2&size=37');
        await screen.findByRole('table');
        const user = userEvent.setup();
        await user.selectOptions(screen.getByLabelText('Status'), 'CLOSED');
        await screen.findByRole('table');
        await user.clear(screen.getByRole('searchbox'));
        await user.type(screen.getByRole('searchbox'), 'access');
        await user.click(screen.getByRole('button', { name: 'Search' }));
        await screen.findByRole('table');
        await user.click(screen.getByRole('button', { name: 'Back' }));
        await screen.findByRole('table');
        expect(screen.getByRole('searchbox')).toHaveValue('printer');
        expect(screen.getByLabelText('Status')).toHaveValue('CLOSED');
        await user.click(screen.getByRole('button', { name: 'Back' }));
        await screen.findByRole('table');
        expect(screen.getByLabelText('Status')).toHaveValue('');
        expect(params().get('page')).toBe('2');
        expect(screen.getByLabelText('Tickets per page')).toHaveValue('37');
        await user.click(screen.getByRole('button', { name: 'Forward' }));
        await screen.findByRole('table');
        expect(screen.getByLabelText('Status')).toHaveValue('CLOSED');
    });

    it.each([200, 401])('ignores a superseded HTTP %s response and aborts its request', async (status) => {
        let resolve!: (response: Response) => void;
        let originalSignal: AbortSignal | null | undefined;
        respondWith((url, options) => {
            if (!url.searchParams.has('status')) {
                originalSignal = options?.signal;
                return new Promise((done) => { resolve = done; });
            }
            return Promise.resolve(Response.json(ticketPage({ content: [{ ...ticket, title: 'Current result' }] })));
        });
        renderWorkspace();
        await screen.findByText('Loading tickets…');
        await userEvent.selectOptions(screen.getByLabelText('Status'), 'RESOLVED');
        expect(await screen.findByText('Current result')).toBeVisible();
        expect(originalSignal?.aborted).toBe(true);
        await act(async () => resolve(status === 200 ? Response.json(ticketPage()) : new Response(null, { status })));
        expect(screen.queryByText(ticket.title)).not.toBeInTheDocument();
        expect(screen.getByText('Current result')).toBeVisible();
        expect(screen.queryByRole('heading', { name: 'Sign in to Issunexa' })).not.toBeInTheDocument();
    });

    it('shows loading when navigating back during another pending request', async () => {
        renderWorkspace();
        await screen.findByRole('table');
        respondWith(() => new Promise(() => {}));
        await userEvent.selectOptions(screen.getByLabelText('Status'), 'CLOSED');
        expect(await screen.findByText('Loading tickets…')).toBeVisible();
        await userEvent.click(screen.getByRole('button', { name: 'Back' }));
        expect(screen.getByText('Loading tickets…')).toBeVisible();
        expect(screen.queryByRole('table')).not.toBeInTheDocument();
        expect(ticketRequests()).toHaveLength(3);
    });

    it('renders the returned server order and content without applying client filters or sorting', async () => {
        respondPage({ content: [
            { ...ticket, id: 1, title: 'Zebra', status: 'OPEN' },
            { ...ticket, id: 2, title: 'Alpha', status: 'CLOSED' },
        ] });
        renderWorkspace('?status=RESOLVED&sortBy=title&direction=asc');
        const table = await screen.findByRole('table');
        expect(within(table).getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual(['Zebra', 'Alpha']);
    });

    it('returns to login on Ticket 401 and clears protected account/list content', async () => {
        respondWith(async () => new Response(null, { status: 401 }));
        renderWorkspace();
        expect(await screen.findByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
        await waitFor(() => expect(screen.getByLabelText('Current URL')).toHaveTextContent('/login'));
        expect(screen.getByRole('alert')).toHaveTextContent('Your session expired');
        expect(screen.queryByRole('heading', { name: 'Tickets' })).not.toBeInTheDocument();
        expect(screen.queryByText('Welcome, Synthetic Requester')).not.toBeInTheDocument();
        expect(ticketRequests()).toHaveLength(1);
    });
});
