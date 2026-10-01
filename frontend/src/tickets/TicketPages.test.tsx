import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation, useNavigate } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../app/App';
import { ticket, ticketPage } from '../test/ticketFixtures';
import { ticketCategories, ticketPriorities } from './ticketTypes';

const fetchMock = vi.fn<typeof fetch>();
const csrfMock = vi.fn<() => Promise<Response>>();
const sessionMock = vi.fn<() => Promise<Response>>();
const createMock = vi.fn<(options?: RequestInit) => Promise<Response>>();
const detailMock = vi.fn<(path: string, options?: RequestInit) => Promise<Response>>();
const csrfA = { token: 'synthetic-A', headerName: 'X-CUSTOM-CSRF' };
const csrfB = { token: 'synthetic-B', headerName: 'X-CUSTOM-CSRF' };
const account = { id: 7, email: 'synthetic@example.test', displayName: 'Synthetic User', role: 'REQUESTER' };

function problem(status: number, extra: Record<string, unknown> = {}) {
    return Response.json({ status, detail: 'Internal diagnostic', ...extra }, {
        status, headers: { 'Content-Type': 'application/problem+json' },
    });
}

function Navigation() {
    const location = useLocation();
    const navigate = useNavigate();
    return <>
        <output aria-label="Current URL">{location.pathname}{location.search}</output>
        <button onClick={() => { void navigate('/app/tickets/43'); }}>Open another ticket</button>
    </>;
}

function renderAt(path = '/app/tickets/new', state?: unknown) {
    const url = new URL(path, 'http://localhost');
    return render(<MemoryRouter initialEntries={[{ pathname: url.pathname, search: url.search, state }]}>
        <App /><Navigation />
    </MemoryRouter>);
}

async function fillForm() {
    const user = userEvent.setup();
    await screen.findByRole('heading', { name: 'Create ticket' });
    await user.type(screen.getByLabelText('Title'), ' New service request ');
    await user.type(screen.getByLabelText('Description'), 'First line\nSecond line');
    await user.selectOptions(screen.getByLabelText('Priority'), 'HIGH');
    await user.selectOptions(screen.getByLabelText('Category'), 'SERVICE_REQUEST');
    return user;
}

beforeEach(() => {
    vi.resetAllMocks();
    vi.stubGlobal('fetch', fetchMock);
    csrfMock.mockImplementation(async () => Response.json(csrfMock.mock.calls.length === 1 ? csrfA : csrfB));
    sessionMock.mockImplementation(async () => Response.json(account));
    createMock.mockImplementation(async () => Response.json({ ...ticket, id: 64, title: 'Created by server', assignee: null }, { status: 201 }));
    detailMock.mockImplementation(async (path) => Response.json({ ...ticket, id: Number(path.split('/').at(-1)) }));
    fetchMock.mockImplementation(async (path, options) => {
        if (path === '/api/auth/csrf') return csrfMock();
        if (path === '/api/auth/session') return sessionMock();
        if (path === '/api/auth/login' || path === '/api/auth/logout') return new Response(null, { status: 204 });
        if (path === '/api/tickets' && options?.method === 'POST') return createMock(options);
        if (String(path).startsWith('/api/tickets?')) return Response.json(ticketPage());
        return detailMock(String(path), options);
    });
});
afterEach(() => vi.unstubAllGlobals());

describe('Ticket creation', () => {
    it.each(['REQUESTER', 'AGENT', 'ADMIN'])('shows only backend-supported fields for authenticated %s', async (role) => {
        sessionMock.mockImplementation(async () => Response.json({ ...account, role }));
        renderAt();
        const form = await screen.findByRole('form', { name: 'Create ticket' });
        expect(within(form).getAllByRole('textbox')).toHaveLength(2);
        expect(within(form).getAllByRole('combobox')).toHaveLength(2);
        for (const field of ['Title', 'Description', 'Priority', 'Category']) expect(within(form).getByLabelText(field)).toBeVisible();
        expect(within(form).getByLabelText('Description').tagName).toBe('TEXTAREA');
        expect(within(form).getAllByRole('option').map((option) => option.getAttribute('value'))).toEqual([
            '', ...ticketPriorities, '', ...ticketCategories,
        ]);
        expect(createMock).not.toHaveBeenCalled();
        expect(screen.getByRole('button', { name: 'Sign out' })).toBeVisible();
    });

    it('submits current CSRF once, then loads the server-created ID through the detail API', async () => {
        renderAt();
        const user = await fillForm();
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        await screen.findByRole('heading', { name: ticket.title });
        expect(screen.getByLabelText('Current URL')).toHaveTextContent('/app/tickets/64');
        expect(detailMock).toHaveBeenCalledWith('/api/tickets/64', expect.anything());
        expect(screen.queryByRole('heading', { name: 'Created by server' })).not.toBeInTheDocument();
        expect(createMock).toHaveBeenCalledTimes(1);
        const options = createMock.mock.calls[0]?.[0];
        expect(JSON.parse(String(options?.body))).toEqual({
            title: ' New service request ', description: 'First line\nSecond line', priority: 'HIGH', category: 'SERVICE_REQUEST',
        });
        expect(new Headers(options?.headers).get(csrfA.headerName)).toBe(csrfA.token);
        expect(csrfMock).toHaveBeenCalledTimes(1);
    });

    it('prevents concurrent submissions even when a second submit event bypasses the disabled button', async () => {
        createMock.mockReturnValue(new Promise(() => {}));
        renderAt();
        const user = await fillForm();
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        expect(screen.getByRole('button', { name: 'Creating ticket…' })).toBeDisabled();
        expect(screen.getByLabelText('Description')).toBeDisabled();
        fireEvent.submit(screen.getByRole('form', { name: 'Create ticket' }));
        expect(createMock).toHaveBeenCalledTimes(1);
    });

    it('maps supported backend field errors to safe accessible feedback and preserves input', async () => {
        createMock.mockImplementation(async () => problem(400, { errors: [
            { field: 'title', message: 'Internal title diagnostic' },
            { field: 'description', message: 'Internal description diagnostic' },
            { field: 'priority', message: 'Internal priority diagnostic' },
            { field: 'category', message: 'Internal category diagnostic' },
            { field: 'requester', message: 'Internal requester diagnostic' },
        ] }));
        renderAt();
        const user = await fillForm();
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('Check the ticket fields');
        expect(screen.getByLabelText('Title')).toHaveAccessibleDescription('Enter a nonblank title of at most 255 characters.');
        expect(screen.getByLabelText('Description')).toHaveAccessibleDescription('Enter a nonblank description.');
        expect(screen.getByLabelText('Priority')).toHaveAccessibleDescription('Choose a supported priority.');
        expect(screen.getByLabelText('Category')).toHaveAccessibleDescription('Choose a supported category.');
        expect(screen.queryByText(/Internal/)).not.toBeInTheDocument();
        expect(screen.getByLabelText('Title')).toHaveValue(' New service request ');
        expect(screen.getByRole('button', { name: 'Create ticket' })).toBeEnabled();
        expect(createMock).toHaveBeenCalledTimes(1);
        createMock.mockImplementation(async () => Response.json(ticket, { status: 201 }));
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        expect(await screen.findByRole('heading', { name: ticket.title })).toBeVisible();
        expect(createMock).toHaveBeenCalledTimes(2);
    });

    it.each([undefined, null, 'Internal diagnostic', [{ field: 'title', message: 123 }], [{ field: 'unknown', message: 'Internal' }]])(
        'handles malformed validation extensions safely case %#', async (errors) => {
            createMock.mockImplementation(async () => problem(400, { errors }));
            renderAt();
            const user = await fillForm();
            await user.click(screen.getByRole('button', { name: 'Create ticket' }));
            expect(await screen.findByRole('alert')).toHaveTextContent('Check the ticket fields');
            expect(screen.queryByText(/Internal/)).not.toBeInTheDocument();
            expect(screen.getByRole('button', { name: 'Create ticket' })).toBeEnabled();
        },
    );

    it.each(['offline', 'malformed', 'server'] as const)('handles %s without automatic POST replay or unsafe navigation', async (failure) => {
        createMock.mockImplementation(async () => {
            if (failure === 'offline') throw new TypeError('Internal stack trace');
            if (failure === 'malformed') return Response.json({}, { status: 201 });
            return problem(500);
        });
        renderAt();
        const user = await fillForm();
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('We could not confirm ticket creation');
        expect(screen.getByLabelText('Current URL')).toHaveTextContent('/app/tickets/new');
        expect(screen.getByLabelText('Description')).toHaveValue('First line\nSecond line');
        expect(screen.queryByText(/Internal/)).not.toBeInTheDocument();
        expect(createMock).toHaveBeenCalledTimes(1);
        expect(detailMock).not.toHaveBeenCalled();
    });

    it('refreshes rejected CSRF only on the next explicit submit and never replays the failed POST', async () => {
        createMock.mockResolvedValueOnce(problem(403));
        renderAt();
        const user = await fillForm();
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('Submit again to refresh your session');
        expect(createMock).toHaveBeenCalledTimes(1);
        expect(csrfMock).toHaveBeenCalledTimes(1);
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        expect(await screen.findByRole('heading', { name: ticket.title })).toBeVisible();
        expect(createMock).toHaveBeenCalledTimes(2);
        expect(csrfMock).toHaveBeenCalledTimes(2);
        expect(new Headers(createMock.mock.calls[1]?.[0]?.headers).get(csrfB.headerName)).toBe(csrfB.token);
    });

    it('does not send a POST if the CSRF refresh fails, and recovers on another explicit submit', async () => {
        createMock.mockResolvedValueOnce(problem(403));
        renderAt();
        const user = await fillForm();
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        await screen.findByRole('alert');
        csrfMock.mockRejectedValueOnce(new TypeError('Internal refresh diagnostic'));
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('We could not confirm ticket creation');
        expect(createMock).toHaveBeenCalledTimes(1);
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        expect(await screen.findByRole('heading', { name: ticket.title })).toBeVisible();
        expect(createMock).toHaveBeenCalledTimes(2);
        expect(new Headers(createMock.mock.calls[1]?.[0]?.headers).get(csrfB.headerName)).toBe(csrfB.token);
    });

    it('uses rotated post-login CSRF for Ticket creation', async () => {
        sessionMock.mockResolvedValueOnce(problem(401));
        renderAt();
        const user = userEvent.setup();
        await screen.findByRole('heading', { name: 'Sign in to Issunexa' });
        await user.type(screen.getByLabelText('Email'), account.email);
        await user.type(screen.getByLabelText('Password'), 'synthetic test password');
        await user.click(screen.getByRole('button', { name: 'Sign in' }));
        await user.click(await screen.findByRole('link', { name: 'Create ticket' }));
        await fillForm();
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        expect(await screen.findByRole('heading', { name: ticket.title })).toBeVisible();
        expect(new Headers(createMock.mock.calls[0]?.[0]?.headers).get(csrfB.headerName)).toBe(csrfB.token);
    });

    it('expires authentication when creation returns 401', async () => {
        createMock.mockImplementation(async () => problem(401));
        renderAt();
        const user = await fillForm();
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        expect(await screen.findByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
        expect(screen.getByRole('alert')).toHaveTextContent('Your session expired');
        expect(screen.queryByRole('form', { name: 'Create ticket' })).not.toBeInTheDocument();
        expect(screen.queryByText('Welcome, Synthetic User')).not.toBeInTheDocument();
        expect(createMock).toHaveBeenCalledTimes(1);
    });

    it.each([201, 401])('ignores a late creation HTTP %s after navigating away', async (status) => {
        let resolve!: (response: Response) => void;
        createMock.mockImplementation(() => new Promise((done) => { resolve = done; }));
        renderAt();
        const user = await fillForm();
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        const signal = createMock.mock.calls[0]?.[0]?.signal;
        await user.click(screen.getByRole('link', { name: 'Cancel' }));
        await screen.findByRole('table');
        expect(signal?.aborted).toBe(true);
        await act(async () => resolve(status === 201 ? Response.json(ticket, { status }) : problem(status)));
        expect(screen.getByLabelText('Current URL')).toHaveTextContent('/app/tickets');
        expect(screen.getByRole('table')).toBeVisible();
        expect(screen.queryByRole('heading', { name: 'Sign in to Issunexa' })).not.toBeInTheDocument();
    });
});

describe('Ticket details', () => {
    it('shows loading before receiving a Ticket', async () => {
        detailMock.mockReturnValue(new Promise(() => {}));
        renderAt('/app/tickets/42');
        expect(await screen.findByText('Loading ticket…')).toHaveAttribute('role', 'status');
        expect(screen.queryByRole('heading', { name: ticket.title })).not.toBeInTheDocument();
        expect(screen.getByRole('link', { name: 'Back to tickets' })).toHaveAttribute('href', '/app/tickets');
    });

    it.each([ticket.assignee, null])('renders backend Ticket fields with assignee %j', async (assignee) => {
        detailMock.mockImplementation(async () => Response.json({ ...ticket, assignee, description: 'First line\n<script>text only</script>' }));
        renderAt('/app/tickets/42');
        expect(await screen.findByRole('heading', { name: ticket.title, level: 1 })).toBeVisible();
        for (const text of ['Open', 'High', 'Incident', assignee?.displayName ?? 'Unassigned']) expect(screen.getByText(text)).toBeVisible();
        expect(screen.getByText(/First line/)).toHaveTextContent('<script>text only</script>');
        expect(screen.getByRole('main').querySelector('script')).toBeNull();
        const times = screen.getByRole('main').querySelectorAll('time');
        expect(times[0]).toHaveAttribute('datetime', ticket.createdAt);
        expect(times[1]).toHaveAttribute('datetime', ticket.updatedAt);
        expect(screen.queryByRole('button', { name: /claim|status|comment|delete/i })).not.toBeInTheDocument();
    });

    it('shows an indistinguishable not-found state for backend 404', async () => {
        detailMock.mockImplementation(async () => problem(404));
        renderAt('/app/tickets/42');
        expect(await screen.findByRole('heading', { name: 'Ticket not found' })).toBeVisible();
        expect(screen.getByRole('alert')).toHaveTextContent('This ticket could not be found.');
        expect(within(screen.getByRole('region', { name: 'Ticket details' })).queryByText(/Internal|not yours|requester|permission/i)).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Retry' })).toBeVisible();
    });

    it.each(['offline', 'server', 'malformed', 'invalid-json'] as const)('handles %s detail errors safely and retries', async (failure) => {
        detailMock.mockImplementation(async () => {
            if (failure === 'offline') throw new TypeError('Internal stack trace');
            if (failure === 'malformed') return Response.json({ ...ticket, assignee: {} });
            if (failure === 'invalid-json') return new Response('Internal JSON diagnostic');
            return problem(500);
        });
        renderAt('/app/tickets/42');
        expect(await screen.findByRole('alert')).toHaveTextContent('We could not load this ticket');
        expect(screen.queryByText(/Internal/)).not.toBeInTheDocument();
        detailMock.mockImplementation(async () => Response.json(ticket));
        await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
        expect(await screen.findByRole('heading', { name: ticket.title })).toBeVisible();
        expect(detailMock).toHaveBeenCalledTimes(2);
    });

    it('expires authentication when detail loading returns 401', async () => {
        detailMock.mockImplementation(async () => problem(401));
        renderAt('/app/tickets/42');
        expect(await screen.findByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
        expect(screen.getByRole('alert')).toHaveTextContent('Your session expired');
        expect(screen.getByLabelText('Current URL')).toHaveTextContent('/login');
        expect(screen.queryByText('Welcome, Synthetic User')).not.toBeInTheDocument();
    });

    it.each([200, 401])('ignores a superseded detail HTTP %s and aborts the old request', async (status) => {
        let resolve!: (response: Response) => void;
        detailMock.mockImplementation((path) => path === '/api/tickets/42'
            ? new Promise((done) => { resolve = done; })
            : Promise.resolve(Response.json({ ...ticket, id: 43, title: 'Current ticket' })));
        renderAt('/app/tickets/42');
        await screen.findByText('Loading ticket…');
        const signal = detailMock.mock.calls[0]?.[1]?.signal;
        await userEvent.click(screen.getByRole('button', { name: 'Open another ticket' }));
        expect(await screen.findByRole('heading', { name: 'Current ticket' })).toBeVisible();
        expect(signal?.aborted).toBe(true);
        await act(async () => resolve(status === 200 ? Response.json(ticket) : problem(status)));
        expect(screen.getByRole('heading', { name: 'Current ticket' })).toBeVisible();
        expect(screen.queryByRole('heading', { name: ticket.title })).not.toBeInTheDocument();
    });

    it('loads a directly opened detail page again after remounting without navigation state', async () => {
        const first = renderAt('/app/tickets/42');
        await screen.findByRole('heading', { name: ticket.title });
        first.unmount();
        renderAt('/app/tickets/42');
        expect(await screen.findByRole('heading', { name: ticket.title })).toBeVisible();
        expect(detailMock).toHaveBeenCalledTimes(2);
    });
});

describe('Ticket route integration', () => {
    const listUrl = '/app/tickets?q=printer&status=OPEN&priority=HIGH&category=INCIDENT&sortBy=title&direction=asc&page=2&size=10';

    it('opens a Ticket from the list and restores the exact list query on return', async () => {
        renderAt(listUrl);
        await userEvent.click(await screen.findByRole('link', { name: ticket.title }));
        expect(await screen.findByRole('heading', { name: ticket.title })).toBeVisible();
        expect(screen.getByRole('link', { name: 'Back to tickets' })).toHaveAttribute('href', listUrl);
        await userEvent.click(screen.getByRole('link', { name: 'Back to tickets' }));
        await screen.findByRole('table');
        expect(screen.getByLabelText('Current URL')).toHaveTextContent(listUrl);
        expect(screen.getByRole('searchbox')).toHaveValue('printer');
        expect(screen.getByLabelText('Status')).toHaveValue('OPEN');
        expect(screen.getByLabelText('Sort by')).toHaveValue('title');
        const request = fetchMock.mock.calls.filter(([path]) => String(path).startsWith('/api/tickets?')).at(-1)?.[0];
        expect(new URL(String(request), 'http://localhost').searchParams.get('page')).toBe('2');
    });

    it('opens creation from the workspace, preserves cancel and success return destinations, and logs out', async () => {
        renderAt(listUrl);
        const user = userEvent.setup();
        await user.click(await screen.findByRole('link', { name: 'Create ticket' }));
        await screen.findByRole('heading', { name: 'Create ticket' });
        expect(screen.getByRole('link', { name: 'Cancel' })).toHaveAttribute('href', listUrl);
        await fillForm();
        await user.click(screen.getByRole('button', { name: 'Create ticket' }));
        await screen.findByRole('heading', { name: ticket.title });
        expect(screen.getByRole('link', { name: 'Back to tickets' })).toHaveAttribute('href', listUrl);
        await user.click(screen.getByRole('link', { name: 'Back to tickets' }));
        await screen.findByRole('table');
        expect(screen.getByLabelText('Current URL')).toHaveTextContent(listUrl);
        await user.click(screen.getByRole('button', { name: 'Sign out' }));
        expect(await screen.findByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
        expect(screen.queryByRole('table')).not.toBeInTheDocument();
    });

    it.each(['https://example.test', '//example.test', '/login', '/app/tickets/new', '/app/tickets/42'])('ignores unsafe or unrelated return destinations: %s', async (ticketListUrl) => {
        renderAt('/app/tickets/42', { ticketListUrl });
        await screen.findByRole('heading', { name: ticket.title });
        expect(screen.getByRole('link', { name: 'Back to tickets' })).toHaveAttribute('href', '/app/tickets');
    });

    it('does not request protected Ticket APIs before session restoration completes', () => {
        sessionMock.mockReturnValue(new Promise(() => {}));
        renderAt('/app/tickets/42');
        expect(screen.getByRole('heading', { name: 'Checking your session' })).toBeVisible();
        expect(detailMock).not.toHaveBeenCalled();
        expect(createMock).not.toHaveBeenCalled();
    });
});
