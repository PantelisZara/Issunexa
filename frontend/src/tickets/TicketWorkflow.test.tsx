import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useNavigate } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../app/App';
import { ticket, ticketPage } from '../test/ticketFixtures';
import { ticketLabels, type Ticket } from './ticketTypes';

const fetchMock = vi.fn<typeof fetch>();
const detailMock = vi.fn<(path: string, options?: RequestInit) => Promise<Response>>();
const mutationMock = vi.fn<(path: string, options?: RequestInit) => Promise<Response>>();
const csrfMock = vi.fn<() => Promise<Response>>();
const account = { id: 8, email: 'synthetic@example.test', displayName: 'Synthetic Agent', role: 'AGENT' };
const csrfA = { token: 'synthetic-A', headerName: 'X-CUSTOM-CSRF' };
const csrfB = { token: 'synthetic-B', headerName: 'X-CUSTOM-CSRF' };
let current: Ticket;
let role: string;

function problem(status: number, title = 'Internal diagnostic') {
    return Response.json({ status, title, detail: 'Internal backend detail' }, {
        status, headers: { 'Content-Type': 'application/problem+json' },
    });
}

function Navigation() {
    const navigate = useNavigate();
    return <button onClick={() => { void navigate('/app/tickets/43'); }}>Open another ticket</button>;
}

function renderDetail() {
    return render(<MemoryRouter initialEntries={[{ pathname: '/app/tickets/42', state: { ticketListUrl: '/app/tickets?q=printer&page=2' } }]}>
        <App /><Navigation />
    </MemoryRouter>);
}

async function loaded() {
    await screen.findByRole('heading', { name: ticket.title, level: 1 });
}

async function expectTicketStatus(label: string) {
    await waitFor(() => {
        const definitions = within(screen.getByRole('region', { name: 'Ticket details' })).getAllByRole('definition');
        const status = definitions.find((definition) => definition.previousElementSibling?.textContent === 'Status');
        expect(status).toBeVisible();
        expect(status?.textContent).toBe(label);
    });
}

beforeEach(() => {
    vi.resetAllMocks();
    vi.stubGlobal('fetch', fetchMock);
    current = { ...ticket, assignee: null };
    role = 'AGENT';
    csrfMock.mockImplementation(async () => Response.json(csrfMock.mock.calls.length === 1 ? csrfA : csrfB));
    detailMock.mockImplementation(async () => Response.json(current));
    mutationMock.mockImplementation(async () => Response.json(current));
    fetchMock.mockImplementation(async (path, options) => {
        if (path === '/api/auth/csrf') return csrfMock();
        if (path === '/api/auth/session') return Response.json({ ...account, role });
        if (path === '/api/auth/logout') return new Response(null, { status: 204 });
        if (String(path).endsWith('/claim') || String(path).endsWith('/status')) return mutationMock(String(path), options);
        if (String(path).startsWith('/api/tickets?')) return Response.json(ticketPage());
        if (/\/(comments|history)\?/.test(String(path))) return Response.json(ticketPage({ content: [], totalElements: 0, totalPages: 0 }));
        return detailMock(String(path), options);
    });
});
afterEach(() => vi.unstubAllGlobals());

describe('Ticket workflow controls', () => {
    it('keeps REQUESTER details free of agent controls', async () => {
        role = 'REQUESTER';
        renderDetail();
        await loaded();
        expect(screen.queryByRole('region', { name: 'Agent workflow' })).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: /Claim|Set status/ })).not.toBeInTheDocument();
        expect(screen.getByText('Unassigned')).toBeVisible();
    });

    it.each(['AGENT', 'ADMIN'])('offers eligible claim and status controls for %s', async (value) => {
        role = value;
        renderDetail();
        await loaded();
        expect(screen.getByRole('button', { name: 'Claim ticket' })).toBeEnabled();
        expect(screen.getByRole('button', { name: 'Set status to In progress' })).toBeEnabled();
    });

    it.each([account.id, 999])('does not offer self-claim for a Ticket already assigned to user %s', async (id) => {
        current = { ...current, assignee: { id, displayName: 'Existing Agent' } };
        renderDetail();
        await loaded();
        expect(screen.queryByRole('button', { name: 'Claim ticket' })).not.toBeInTheDocument();
        expect(screen.getByText('Existing Agent')).toBeVisible();
        expect(screen.getByRole('button', { name: 'Set status to In progress' })).toBeEnabled();
    });

    it.each([
        ['OPEN', ['In progress']], ['IN_PROGRESS', ['Resolved']],
        ['RESOLVED', ['In progress', 'Closed']], ['CLOSED', []],
    ] as const)('offers exactly the backend-supported transitions from %s', async (status, labels) => {
        current = { ...current, status };
        renderDetail();
        await loaded();
        expect(screen.queryAllByRole('button', { name: /Set status to/ }).map((button) => button.textContent))
            .toEqual(labels.map((label) => `Set status to ${label}`));
        // Claim eligibility depends on assignment, not an invented status restriction.
        expect(screen.getByRole('button', { name: 'Claim ticket' })).toBeEnabled();
    });

    it('uses all fields from the claim response and preserves navigation without a detail reload', async () => {
        const updated = { ...current, title: 'Authoritative title', description: 'Server description', status: 'RESOLVED',
            assignee: { id: account.id, displayName: 'Server Agent' }, updatedAt: '2026-10-02T14:00:00Z' } as const;
        mutationMock.mockImplementation(async () => Response.json(updated));
        renderDetail();
        await loaded();
        await userEvent.click(screen.getByRole('button', { name: 'Claim ticket' }));
        expect(await screen.findByRole('heading', { name: updated.title })).toBeVisible();
        expect(screen.getByText('Server description')).toBeVisible();
        expect(screen.getByText('Server Agent')).toBeVisible();
        expect(screen.getByText('Resolved')).toBeVisible();
        expect(screen.getByRole('main').querySelectorAll('time')[1]).toHaveAttribute('datetime', updated.updatedAt);
        expect(screen.queryByRole('button', { name: 'Claim ticket' })).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Set status to Closed' })).toBeEnabled();
        expect(screen.getByRole('link', { name: 'Back to tickets' })).toHaveAttribute('href', '/app/tickets?q=printer&page=2');
        expect(detailMock).toHaveBeenCalledTimes(1);
        expect(mutationMock).toHaveBeenCalledTimes(1);
        expect(screen.getByRole('status')).toHaveTextContent('Ticket claimed.');
    });

    it.each([
        ['OPEN', 'IN_PROGRESS'], ['IN_PROGRESS', 'RESOLVED'], ['RESOLVED', 'IN_PROGRESS'], ['RESOLVED', 'CLOSED'],
    ] as const)('changes %s to %s and renders server state', async (from, to) => {
        current = { ...current, status: from };
        mutationMock.mockImplementation(async () => Response.json({ ...current, status: to, assignee: ticket.assignee }));
        renderDetail();
        await loaded();
        await userEvent.click(screen.getByRole('button', { name: `Set status to ${ticketLabels[to]}` }));
        await expectTicketStatus(ticketLabels[to]);
        expect(screen.getByText('Alice Agent')).toBeVisible();
        expect(screen.getByRole('status')).toHaveTextContent('Ticket status updated.');
        expect(JSON.parse(String(mutationMock.mock.calls[0]?.[1]?.body))).toEqual({ status: to });
        expect(mutationMock).toHaveBeenCalledTimes(1);
    });

    it.each(['Claim ticket', 'Set status to In progress'])('prevents duplicate and competing mutations while %s is pending', async (label) => {
        let resolve!: (value: Response) => void;
        mutationMock.mockImplementation(() => new Promise((done) => { resolve = done; }));
        renderDetail();
        await loaded();
        const button = screen.getByRole('button', { name: label });
        fireEvent.click(button);
        fireEvent.click(button);
        await screen.findByText('Updating ticket…');
        for (const control of within(screen.getByRole('region', { name: 'Agent workflow' })).getAllByRole('button')) expect(control).toBeDisabled();
        expect(mutationMock).toHaveBeenCalledTimes(1);
        await act(async () => resolve(Response.json({ ...current, assignee: ticket.assignee, status: 'IN_PROGRESS' })));
        expect(screen.getByText('Alice Agent')).toBeVisible();
        expect(screen.getByRole('button', { name: 'Set status to Resolved' })).toBeEnabled();
    });
});

describe('Ticket workflow recovery', () => {
    it.each(['Claim ticket', 'Set status to In progress'])('expires the session for active %s HTTP 401', async (label) => {
        mutationMock.mockImplementation(async () => problem(401));
        renderDetail();
        await loaded();
        await userEvent.click(screen.getByRole('button', { name: label }));
        expect(await screen.findByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
        expect(screen.getByRole('alert')).toHaveTextContent('Your session expired');
        expect(screen.queryByRole('region', { name: 'Ticket details' })).not.toBeInTheDocument();
        expect(mutationMock).toHaveBeenCalledTimes(1);
    });

    it.each(['Claim ticket', 'Set status to In progress'])('handles generic %s 403 and refreshes CSRF only on explicit retry', async (label) => {
        mutationMock.mockImplementationOnce(async () => problem(403)).mockImplementation(async () => Response.json({ ...current, status: 'IN_PROGRESS', assignee: ticket.assignee }));
        renderDetail();
        await loaded();
        await userEvent.click(screen.getByRole('button', { name: label }));
        expect(await screen.findByRole('alert')).toHaveTextContent('This action was not permitted or your session changed');
        expect(screen.queryByText(/Internal/)).not.toBeInTheDocument();
        expect(csrfMock).toHaveBeenCalledTimes(1);
        expect(mutationMock).toHaveBeenCalledTimes(1);
        await userEvent.click(screen.getByRole('button', { name: label }));
        await screen.findByText('Alice Agent');
        expect(csrfMock).toHaveBeenCalledTimes(2);
        expect(mutationMock).toHaveBeenCalledTimes(2);
        expect(new Headers(mutationMock.mock.calls[0]?.[1]?.headers).get(csrfA.headerName)).toBe(csrfA.token);
        expect(new Headers(mutationMock.mock.calls[1]?.[1]?.headers).get(csrfB.headerName)).toBe(csrfB.token);
    });

    it('does not replay or expose backend details when authorization continues to deny the action', async () => {
        mutationMock.mockImplementation(async () => problem(403));
        renderDetail();
        await loaded();
        await userEvent.click(screen.getByRole('button', { name: 'Claim ticket' }));
        await screen.findByRole('alert');
        await userEvent.click(screen.getByRole('button', { name: 'Claim ticket' }));
        expect(screen.getByRole('alert')).toHaveTextContent('This action was not permitted');
        expect(mutationMock).toHaveBeenCalledTimes(2);
        expect(detailMock).toHaveBeenCalledTimes(1);
        expect(screen.queryByText(/Internal/)).not.toBeInTheDocument();
    });

    it('expires authentication if explicit CSRF recovery returns 401, without submitting the mutation', async () => {
        mutationMock.mockImplementation(async () => problem(403));
        renderDetail();
        await loaded();
        await userEvent.click(screen.getByRole('button', { name: 'Claim ticket' }));
        await screen.findByRole('alert');
        csrfMock.mockImplementation(async () => problem(401));
        await userEvent.click(screen.getByRole('button', { name: 'Claim ticket' }));
        expect(await screen.findByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
        expect(mutationMock).toHaveBeenCalledTimes(1);
    });

    it.each(['Concurrent ticket update', 'Ticket already assigned', 'Invalid ticket status transition'])('requires explicit reload after backend conflict: %s', async (title) => {
        mutationMock.mockImplementation(async () => problem(409, title));
        renderDetail();
        await loaded();
        await userEvent.click(screen.getByRole('button', { name: 'Claim ticket' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('The ticket changed');
        expect(screen.getByRole('button', { name: 'Claim ticket' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Set status to In progress' })).toBeDisabled();
        expect(mutationMock).toHaveBeenCalledTimes(1);
        expect(detailMock).toHaveBeenCalledTimes(1);
        current = { ...current, status: 'RESOLVED', assignee: { id: 999, displayName: 'Another Agent' } };
        await userEvent.click(screen.getByRole('button', { name: 'Refresh ticket' }));
        expect(await screen.findByText('Another Agent')).toBeVisible();
        expect(screen.queryByRole('button', { name: 'Claim ticket' })).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Set status to Closed' })).toBeEnabled();
        expect(screen.queryByRole('alert')).not.toBeInTheDocument();
        expect(detailMock).toHaveBeenCalledTimes(2);
        expect(mutationMock).toHaveBeenCalledTimes(1);
        mutationMock.mockImplementation(async () => Response.json({ ...current, status: 'CLOSED' }));
        await userEvent.click(screen.getByRole('button', { name: 'Set status to Closed' }));
        await expectTicketStatus('Closed');
        expect(mutationMock).toHaveBeenCalledTimes(2);
    });

    it('keeps actions unavailable if conflict refresh fails, then recovers with a successful GET', async () => {
        mutationMock.mockImplementation(async () => problem(409));
        renderDetail();
        await loaded();
        await userEvent.click(screen.getByRole('button', { name: 'Claim ticket' }));
        await screen.findByRole('alert');
        detailMock.mockImplementationOnce(async () => problem(500));
        await userEvent.click(screen.getByRole('button', { name: 'Refresh ticket' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('We could not load this ticket');
        expect(screen.queryByRole('button', { name: 'Claim ticket' })).not.toBeInTheDocument();
        await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
        expect(await screen.findByRole('button', { name: 'Claim ticket' })).toBeEnabled();
        expect(mutationMock).toHaveBeenCalledTimes(1);
        expect(detailMock).toHaveBeenCalledTimes(3);
    });

    it.each(['offline', 'malformed', 'invalid-json', 'server', 'bad-request'] as const)('requires server refresh after an unconfirmed %s update', async (failure) => {
        mutationMock.mockImplementation(async () => {
            if (failure === 'offline') throw new TypeError('Internal diagnostic');
            if (failure === 'malformed') return Response.json({ ...current, assignee: {} });
            if (failure === 'invalid-json') return new Response('Internal JSON diagnostic');
            return problem(failure === 'server' ? 500 : 400);
        });
        renderDetail();
        await loaded();
        await userEvent.click(screen.getByRole('button', { name: 'Claim ticket' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('We could not confirm the update');
        expect(screen.getByRole('button', { name: 'Claim ticket' })).toBeDisabled();
        expect(screen.queryByText(/Internal/)).not.toBeInTheDocument();
        current = { ...current, assignee: ticket.assignee };
        await userEvent.click(screen.getByRole('button', { name: 'Refresh ticket' }));
        expect(await screen.findByText('Alice Agent')).toBeVisible();
        expect(screen.queryByRole('button', { name: 'Claim ticket' })).not.toBeInTheDocument();
        expect(mutationMock).toHaveBeenCalledTimes(1);
    });

    it('uses the generic missing-ticket state after a mutation 404 and explicit refresh', async () => {
        mutationMock.mockImplementation(async () => problem(404));
        renderDetail();
        await loaded();
        await userEvent.click(screen.getByRole('button', { name: 'Claim ticket' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('This ticket could not be found');
        detailMock.mockImplementation(async () => problem(404));
        await userEvent.click(screen.getByRole('button', { name: 'Refresh ticket' }));
        expect(await screen.findByRole('heading', { name: 'Ticket not found' })).toBeVisible();
        expect(screen.queryByRole('region', { name: 'Agent workflow' })).not.toBeInTheDocument();
    });

    it.each([200, 401])('aborts and ignores an abandoned mutation HTTP %s', async (status) => {
        let resolve!: (value: Response) => void;
        mutationMock.mockImplementation(() => new Promise((done) => { resolve = done; }));
        renderDetail();
        await loaded();
        await userEvent.click(screen.getByRole('button', { name: 'Claim ticket' }));
        const signal = mutationMock.mock.calls[0]?.[1]?.signal;
        detailMock.mockImplementation(async () => Response.json({ ...current, id: 43, title: 'Current ticket' }));
        await userEvent.click(screen.getByRole('button', { name: 'Open another ticket' }));
        expect(await screen.findByRole('heading', { name: 'Current ticket' })).toBeVisible();
        expect(signal?.aborted).toBe(true);
        await act(async () => resolve(status === 200 ? Response.json(ticket) : problem(status)));
        expect(screen.getByRole('heading', { name: 'Current ticket' })).toBeVisible();
        expect(screen.queryByRole('heading', { name: 'Sign in to Issunexa' })).not.toBeInTheDocument();
        expect(screen.queryByText('Alice Agent')).not.toBeInTheDocument();
    });
});
