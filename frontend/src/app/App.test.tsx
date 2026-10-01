import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { ticketPage } from '../test/ticketFixtures';

const fetchMock = vi.fn<typeof fetch>();
const csrfA = { token: 'synthetic-A', headerName: 'X-TEST-CSRF' };
const csrfB = { token: 'synthetic-B', headerName: 'X-TEST-CSRF' };
const account = { id: 7, email: 'alice@example.test', displayName: 'Alice', role: 'REQUESTER' };

function problem(status: number) {
    return Response.json({ status, detail: 'Internal details should not be displayed.' }, {
        status, headers: { 'Content-Type': 'application/problem+json' },
    });
}

function Location() {
    return <output aria-label="Current route">{useLocation().pathname}</output>;
}

function renderApp(path = '/') {
    return render(<MemoryRouter initialEntries={[path]}><App /><Location /></MemoryRouter>);
}

function anonymous() {
    fetchMock.mockResolvedValueOnce(Response.json(csrfA)).mockResolvedValueOnce(problem(401));
}

async function fillLogin() {
    const user = userEvent.setup();
    await screen.findByRole('heading', { name: 'Sign in to Issunexa' });
    await user.type(screen.getByLabelText('Email'), 'alice@example.test');
    await user.type(screen.getByLabelText('Password'), 'synthetic test password');
    return user;
}

beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', (path: RequestInfo | URL, options?: RequestInit) => {
        if (String(path).startsWith('/api/tickets?')) return Promise.resolve(Response.json(ticketPage()));
        return fetchMock(path, options);
    });
});
afterEach(() => vi.unstubAllGlobals());

describe('authentication routes and form', () => {
    it.each(['/', '/app', '/app/tickets'])('redirects anonymous %s to an accessible login form', async (path) => {
        anonymous();
        renderApp(path);
        const main = screen.getByRole('main');
        expect(await within(main).findByRole('heading', { name: 'Sign in to Issunexa', level: 1 })).toBeVisible();
        expect(screen.getByLabelText('Current route')).toHaveTextContent('/login');
        expect(screen.getByRole('banner')).toBeVisible();
        expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
        expect(screen.getByLabelText('Email')).toHaveAttribute('type', 'email');
        expect(screen.getByLabelText('Email')).toHaveAttribute('autocomplete', 'username');
        expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password');
        expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password');
        const user = userEvent.setup();
        await user.tab();
        expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveFocus();
        await user.tab();
        await user.tab();
        expect(screen.getByLabelText('Email')).toHaveFocus();
        await user.tab();
        expect(screen.getByLabelText('Password')).toHaveFocus();
    });

    it('shows loading without exposing protected content or redirecting prematurely', () => {
        fetchMock.mockReturnValue(new Promise(() => {}));
        renderApp('/app');
        expect(screen.getByRole('heading', { name: 'Checking your session' })).toBeVisible();
        expect(screen.getByLabelText('Current route')).toHaveTextContent('/app');
        expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
        expect(screen.queryByRole('heading', { name: 'Tickets' })).not.toBeInTheDocument();
    });

    it.each(['/app', '/login', '/app/tickets'])('restores the backend session from %s and renders account information', async (path) => {
        fetchMock.mockResolvedValueOnce(Response.json(csrfB)).mockResolvedValueOnce(Response.json(account));
        renderApp(path);
        expect(await screen.findByText('Welcome, Alice')).toBeVisible();
        expect(screen.getByText('Role: REQUESTER')).toBeVisible();
        expect(await screen.findByRole('heading', { name: 'Tickets' })).toBeVisible();
        expect(screen.getByLabelText('Current route')).toHaveTextContent('/app/tickets');
    });

    it('preserves not-found routing and keyboard navigation back into authentication', async () => {
        anonymous();
        renderApp('/missing');
        expect(screen.getByRole('heading', { name: 'Page not found' })).toBeVisible();
        const user = userEvent.setup();
        await user.tab(); await user.tab(); await user.tab();
        expect(screen.getByRole('link', { name: 'Return to home' })).toHaveFocus();
        await user.keyboard('{Enter}');
        expect(await screen.findByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
    });

    it('prevents duplicate submission while a credential POST is pending', async () => {
        anonymous();
        fetchMock.mockReturnValueOnce(new Promise(() => {}));
        renderApp('/login');
        const user = await fillLogin();
        await user.click(screen.getByRole('button', { name: 'Sign in' }));
        expect(screen.getByRole('button', { name: 'Signing in…' })).toBeDisabled();
        expect(screen.getByLabelText('Password')).toBeDisabled();
        await user.click(screen.getByRole('button', { name: 'Signing in…' }));
        expect(fetchMock.mock.calls.filter(([path]) => path === '/api/auth/login')).toHaveLength(1);
    });

    it('uses one generic invalid-credential message and clears the entered password', async () => {
        anonymous(); fetchMock.mockResolvedValueOnce(problem(401));
        renderApp('/login');
        const user = await fillLogin();
        await user.click(screen.getByRole('button', { name: 'Sign in' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.');
        expect(screen.getByLabelText('Password')).toHaveValue('');
        expect(screen.queryByText(/Internal details/)).not.toBeInTheDocument();
        expect(screen.getByLabelText('Current route')).toHaveTextContent('/login');
    });

    it.each([true, false])('handles stale login CSRF without replaying credentials; refresh succeeds: %s', async (refreshSucceeds) => {
        anonymous(); fetchMock.mockResolvedValueOnce(problem(403));
        if (refreshSucceeds) fetchMock.mockResolvedValueOnce(Response.json(csrfB));
        else fetchMock.mockRejectedValueOnce(new TypeError('offline'));
        renderApp('/login');
        const user = await fillLogin();
        await user.click(screen.getByRole('button', { name: 'Sign in' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(/try signing in again/i);
        expect(fetchMock.mock.calls.filter(([path]) => path === '/api/auth/login')).toHaveLength(1);
        expect(screen.getByLabelText('Current route')).toHaveTextContent('/login');
        if (!refreshSucceeds) fetchMock.mockResolvedValueOnce(Response.json(csrfB));
        fetchMock.mockResolvedValueOnce(problem(401));
        await user.type(screen.getByLabelText('Password'), 'synthetic retry password');
        await user.click(screen.getByRole('button', { name: 'Sign in' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.');
        const posts = fetchMock.mock.calls.filter(([path]) => path === '/api/auth/login');
        expect(new Headers(posts[1]?.[1]?.headers).get(csrfB.headerName)).toBe(csrfB.token);
    });

    it('completes login with fresh CSRF, renders the user, and signs out through the backend', async () => {
        anonymous();
        fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))
            .mockResolvedValueOnce(Response.json(csrfB)).mockResolvedValueOnce(Response.json(account))
            .mockResolvedValueOnce(new Response(null, { status: 204 }))
            .mockResolvedValueOnce(Response.json({ ...csrfA, token: 'synthetic-C' }));
        renderApp('/login');
        const user = await fillLogin();
        await user.click(screen.getByRole('button', { name: 'Sign in' }));
        expect(await screen.findByText('Welcome, Alice')).toBeVisible();
        expect(await screen.findByRole('heading', { name: 'Tickets' })).toBeVisible();
        expect(screen.getByLabelText('Current route')).toHaveTextContent('/app/tickets');
        await user.click(screen.getByRole('button', { name: 'Sign out' }));
        expect(await screen.findByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
        expect(screen.getByLabelText('Current route')).toHaveTextContent('/login');
        expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
            '/api/auth/csrf', '/api/auth/session', '/api/auth/login', '/api/auth/csrf',
            '/api/auth/session', '/api/auth/logout', '/api/auth/csrf',
        ]);
        expect(new Headers(fetchMock.mock.calls[5]?.[1]?.headers).get(csrfB.headerName)).toBe(csrfB.token);
    });

    it('keeps account UI after an unconfirmed logout and offers retry', async () => {
        fetchMock.mockResolvedValueOnce(Response.json(csrfB)).mockResolvedValueOnce(Response.json(account))
            .mockRejectedValueOnce(new TypeError('offline'));
        renderApp('/app');
        await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('We could not confirm sign out');
        expect(screen.getByText('Welcome, Alice')).toBeVisible();
    });

    it('refreshes stale logout CSRF and requires a new explicit sign-out attempt', async () => {
        fetchMock.mockResolvedValueOnce(Response.json(csrfA)).mockResolvedValueOnce(Response.json(account))
            .mockResolvedValueOnce(problem(403)).mockResolvedValueOnce(Response.json(csrfB))
            .mockResolvedValueOnce(new Response(null, { status: 204 })).mockResolvedValueOnce(Response.json(csrfA));
        renderApp('/app');
        await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('try signing out again');
        expect(fetchMock.mock.calls.filter(([path]) => path === '/api/auth/logout')).toHaveLength(1);
        await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
        expect(await screen.findByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
        const posts = fetchMock.mock.calls.filter(([path]) => path === '/api/auth/logout');
        expect(new Headers(posts[1]?.[1]?.headers).get(csrfB.headerName)).toBe(csrfB.token);
    });

    it('recognizes an expired session during logout and leaves the protected route', async () => {
        fetchMock.mockResolvedValueOnce(Response.json(csrfB)).mockResolvedValueOnce(Response.json(account))
            .mockResolvedValueOnce(problem(401)).mockResolvedValueOnce(Response.json(csrfA));
        renderApp('/app');
        await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
        await waitFor(() => expect(screen.getByLabelText('Current route')).toHaveTextContent('/login'));
        expect(screen.queryByText('Welcome, Alice')).not.toBeInTheDocument();
    });
});
