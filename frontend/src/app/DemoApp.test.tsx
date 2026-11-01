import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { ticketPage } from '../test/ticketFixtures';

const fetchMock = vi.fn<typeof fetch>();
const mutation = vi.fn<() => Promise<Response>>();
let role: 'REQUESTER' | 'AGENT' | undefined;
let expiresAt: string;
function Location() { const location = useLocation(); return <output aria-label="Current URL">{location.pathname}{location.search}</output>; }
function renderApp(path = '/login') { return render(<MemoryRouter initialEntries={[path]}><App /><Location /></MemoryRouter>); }
beforeEach(() => {
    role = undefined;
    expiresAt = new Date(Date.now() + 1800000).toISOString();
    vi.resetAllMocks();
    mutation.mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockImplementation(async (path, options) => {
        if (path === '/api/demo') return Response.json({ enabled: true, active: !!role, expiresAt: role ? expiresAt : null });
        if (path === '/api/auth/csrf') return Response.json({ token: 'synthetic-csrf', headerName: 'X-CSRF' });
        if (path === '/api/auth/session') return role ? Response.json({ id: role === 'AGENT' ? 2 : 1, email: 'sample@synthetic.invalid', displayName: 'Synthetic Visitor', role }) : new Response(null, { status: 401 });
        if (String(path).startsWith('/api/demo/') && options?.method === 'POST') {
            const response = await mutation();
            if (response.ok) role = path === '/api/demo/agent' ? 'AGENT' : 'REQUESTER';
            return response;
        }
        if (path === '/api/auth/logout') { role = undefined; return new Response(null, { status: 204 }); }
        if (String(path).startsWith('/api/tickets?')) return Response.json(ticketPage());
        throw new Error(`Unexpected route ${String(path)}`);
    });
});
afterEach(() => vi.unstubAllGlobals());

describe('demo experience', () => {
    it('provides an accessible synthetic-only start and role controls without account selectors', async () => {
        renderApp();
        expect(await screen.findByRole('heading', { name: 'Explore the synthetic demo' })).toBeVisible();
        expect(screen.queryByLabelText('Email')).not.toBeInTheDocument();
        expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
        const start = screen.getByRole('button', { name: 'Start synthetic demo' });
        start.focus();
        await userEvent.keyboard('{Enter}');
        expect(await screen.findByRole('heading', { name: 'Tickets' })).toBeVisible();
        const controls = within(screen.getByRole('region', { name: 'Synthetic demo' }));
        expect(controls.getByRole('button', { name: 'Switch to requester' })).toBeDisabled();
        expect(controls.getByRole('button', { name: 'Switch to requester' })).toHaveAttribute('aria-pressed', 'true');
        expect(controls.getByRole('button', { name: 'Switch to agent' })).toBeEnabled();
        expect(screen.getByText(/30-minute absolute lifetime/).querySelector('time')).toHaveAttribute('datetime', expiresAt);
        expect(screen.queryByRole('button', { name: /admin/i })).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'End demo' })).toBeVisible();
    });
    it('unmounts protected forms during a switch, resets the URL, and prevents repeated actions', async () => {
        role = 'REQUESTER';
        let finish!: (response: Response) => void;
        mutation.mockReturnValue(new Promise<Response>((resolve) => { finish = resolve; }));
        renderApp('/app/tickets/new?q=old');
        await screen.findByRole('heading', { name: 'Create ticket' });
        await userEvent.type(screen.getByLabelText('Title', { exact: true }), 'Old unsaved draft');
        await userEvent.click(screen.getByRole('button', { name: 'Switch to agent' }));
        expect(screen.queryByLabelText('Title', { exact: true })).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Switch to agent' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Switch to requester' })).toBeDisabled();
        expect(screen.getByLabelText('Current URL')).toHaveTextContent('/app/tickets');
        await userEvent.click(screen.getByRole('button', { name: 'Switch to agent' }));
        expect(mutation).toHaveBeenCalledTimes(1);
        await act(async () => finish(new Response(null, { status: 204 })));
        expect(await screen.findByText('Role: AGENT')).toBeVisible();
        await userEvent.click(screen.getByRole('link', { name: 'Create ticket' }));
        expect(screen.getByLabelText('Title', { exact: true })).toHaveValue('');
    });
    it('shows capacity feedback and leaves retries explicit', async () => {
        mutation.mockResolvedValue(new Response(null, { status: 429, headers: { 'Retry-After': '60' } }));
        renderApp();
        await userEvent.click(await screen.findByRole('button', { name: 'Start synthetic demo' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('wait 60 seconds');
        expect(screen.getByRole('button', { name: 'Start synthetic demo' })).toBeEnabled();
        expect(mutation).toHaveBeenCalledTimes(1);
    });
    it('ends access through ordinary logout and returns to the synthetic start', async () => {
        role = 'AGENT'; renderApp('/app/tickets');
        await userEvent.click(await screen.findByRole('button', { name: 'End demo' }));
        expect(await screen.findByRole('heading', { name: 'Explore the synthetic demo' })).toBeVisible();
        await waitFor(() => expect(screen.queryByRole('region', { name: 'Synthetic demo' })).not.toBeInTheDocument());
        expect(screen.queryByText('Role: AGENT')).not.toBeInTheDocument();
        expect(fetchMock.mock.calls.filter(([path]) => path === '/api/auth/logout')).toHaveLength(1);
    });
});
