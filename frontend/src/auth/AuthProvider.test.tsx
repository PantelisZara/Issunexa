import { StrictMode } from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/ApiError';
import { AuthProvider } from './AuthProvider';
import * as authApi from './authApi';
import type { CsrfMetadata } from './authTypes';
import { useAuth } from './useAuth';

vi.mock('./authApi');
const api = vi.mocked(authApi);
const tokenA = { token: 'test-A', headerName: 'X-TEST-CSRF' };
const tokenB = { token: 'test-B', headerName: 'X-ROTATED-CSRF' };
const tokenC = { token: 'test-C', headerName: 'X-ANONYMOUS-CSRF' };
const account = { id: 7, email: 'alice@example.test', displayName: 'Alice', role: 'REQUESTER' as const };

function deferred<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((done) => { resolve = done; });
    return { promise, resolve };
}

function Consumer() {
    const auth = useAuth();
    return <>
        <p role="status">{auth.state.status}</p>
        {auth.state.status === 'authenticated' && <p>{auth.state.user.displayName}</p>}
        {auth.state.status === 'error' && <p role="alert">{auth.state.message}</p>}
        {auth.state.status === 'unauthenticated' && auth.state.message && <p role="alert">{auth.state.message}</p>}
        <button onClick={() => { void auth.retry(); }}>Retry</button>
        <button disabled={auth.pending} onClick={() => { void auth.login('alice@example.test', 'synthetic test password'); }}>Login</button>
        <button disabled={auth.pending} onClick={() => { void auth.logout(); }}>Logout</button>
    </>;
}

function renderProvider() {
    return render(<StrictMode><AuthProvider><Consumer /></AuthProvider></StrictMode>);
}

beforeEach(() => {
    vi.resetAllMocks();
    api.getCsrf.mockResolvedValue(tokenA);
    api.getSession.mockResolvedValue(account);
});

describe('authentication lifecycle', () => {
    it('finishes CSRF before probing session, including StrictMode effect replay', async () => {
        const csrf = deferred<CsrfMetadata>();
        api.getCsrf.mockReturnValue(csrf.promise);
        renderProvider();
        expect(screen.getByRole('status')).toHaveTextContent('loading');
        expect(api.getCsrf).toHaveBeenCalledTimes(1);
        expect(api.getSession).not.toHaveBeenCalled();
        await act(async () => csrf.resolve(tokenA));
        expect(await screen.findByText('Alice')).toBeVisible();
        expect(api.getSession).toHaveBeenCalledTimes(1);
    });

    it('treats a session 401 as confirmed unauthenticated', async () => {
        api.getSession.mockRejectedValue(new ApiError(401));
        renderProvider();
        await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('unauthenticated'));
        expect(screen.queryByText('Alice')).not.toBeInTheDocument();
    });

    it.each(['csrf', 'session'])('surfaces %s bootstrap failure and recovers on explicit retry', async (stage) => {
        if (stage === 'csrf') api.getCsrf.mockRejectedValueOnce(new TypeError('offline'));
        else api.getSession.mockRejectedValueOnce(new ApiError(500));
        renderProvider();
        expect(await screen.findByRole('alert')).toHaveTextContent('We could not check your session');
        expect(screen.getByRole('status')).toHaveTextContent('error');
        if (stage === 'csrf') expect(api.getSession).not.toHaveBeenCalled();
        await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
        expect(await screen.findByText('Alice')).toBeVisible();
    });

    it('replaces A with B before session lookup and uses B for logout, then C for the next login', async () => {
        const fresh = deferred<CsrfMetadata>();
        api.getCsrf.mockResolvedValueOnce(tokenA).mockReturnValueOnce(fresh.promise).mockResolvedValue(tokenC);
        api.getSession.mockRejectedValueOnce(new ApiError(401)).mockResolvedValue(account);
        renderProvider();
        await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('unauthenticated'));
        await userEvent.click(screen.getByRole('button', { name: 'Login' }));
        expect(api.login).toHaveBeenCalledWith('alice@example.test', 'synthetic test password', tokenA);
        expect(api.getSession).toHaveBeenCalledTimes(1);
        expect(screen.queryByText('Alice')).not.toBeInTheDocument();
        await act(async () => fresh.resolve(tokenB));
        expect(await screen.findByText('Alice')).toBeVisible();
        await userEvent.click(screen.getByRole('button', { name: 'Logout' }));
        expect(api.logout).toHaveBeenCalledWith(tokenB);
        await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('unauthenticated'));
        expect(screen.queryByText('Alice')).not.toBeInTheDocument();
        await userEvent.click(screen.getByRole('button', { name: 'Login' }));
        expect(api.login).toHaveBeenLastCalledWith('alice@example.test', 'synthetic test password', tokenC);
        expect(await screen.findByText('Alice')).toBeVisible();
    });

    it('clears the user after backend logout while anonymous CSRF is still pending', async () => {
        const fresh = deferred<CsrfMetadata>();
        api.getCsrf.mockResolvedValueOnce(tokenB).mockReturnValueOnce(fresh.promise);
        renderProvider();
        await screen.findByText('Alice');
        await userEvent.click(screen.getByRole('button', { name: 'Logout' }));
        expect(screen.queryByText('Alice')).not.toBeInTheDocument();
        expect(screen.getByRole('status')).toHaveTextContent('loading');
        await act(async () => fresh.resolve(tokenC));
        expect(screen.getByRole('status')).toHaveTextContent('unauthenticated');
    });

    it('stays signed out after CSRF refresh failure and reacquires it on the next explicit login', async () => {
        api.getCsrf.mockResolvedValueOnce(tokenB).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValue(tokenC);
        renderProvider();
        await screen.findByText('Alice');
        await userEvent.click(screen.getByRole('button', { name: 'Logout' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('You are signed out');
        expect(screen.getByRole('status')).toHaveTextContent('unauthenticated');
        expect(screen.queryByText('Alice')).not.toBeInTheDocument();
        await userEvent.click(screen.getByRole('button', { name: 'Login' }));
        expect(api.login).toHaveBeenCalledWith('alice@example.test', 'synthetic test password', tokenC);
        expect(await screen.findByText('Alice')).toBeVisible();
    });

    it.each(['csrf', 'session'])('does not complete login when post-login %s fails', async (stage) => {
        api.getSession.mockRejectedValueOnce(new ApiError(401));
        if (stage === 'csrf') api.getCsrf.mockResolvedValueOnce(tokenA).mockRejectedValueOnce(new TypeError('offline'));
        else api.getSession.mockRejectedValueOnce(new ApiError(500));
        renderProvider();
        await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('unauthenticated'));
        await userEvent.click(screen.getByRole('button', { name: 'Login' }));
        expect(await screen.findByRole('alert')).toBeVisible();
        expect(screen.getByRole('status')).toHaveTextContent('error');
        expect(screen.queryByText('Alice')).not.toBeInTheDocument();
        expect(api.login).toHaveBeenCalledTimes(1);
        await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
        expect(await screen.findByText('Alice')).toBeVisible();
        expect(api.login).toHaveBeenCalledTimes(1);
    });
});
