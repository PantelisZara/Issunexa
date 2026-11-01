import { StrictMode, useEffect } from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/ApiError';
import { AuthProvider } from './AuthProvider';
import * as authApi from './authApi';
import * as demoApi from './demoApi';
import { useAuth } from './useAuth';
import type { AuthContextValue } from './AuthContext';

vi.mock('./authApi');
vi.mock('./demoApi');
const api = vi.mocked(authApi);
const demo = vi.mocked(demoApi);
const tokenA = { token: 'synthetic-A', headerName: 'X-CSRF' };
const tokenB = { token: 'synthetic-B', headerName: 'X-CSRF' };
const requester = { id: 1, email: 'requester@synthetic.invalid', displayName: 'Sample Requester', role: 'REQUESTER' as const };
const agent = { ...requester, id: 2, displayName: 'Sample Agent', role: 'AGENT' as const };
let auth: AuthContextValue;
function Consumer() {
    const context = useAuth();
    useEffect(() => { auth = context; }, [context]);
    return <>
        <p role="status">{context.state.status}</p>
        {context.state.status === 'authenticated' && <p>{context.state.user.role}</p>}
        {context.state.status === 'unauthenticated' && <p>{context.state.message}</p>}
        {context.state.status === 'error' && <p role="alert">{context.state.message}</p>}
        {context.demoMessage && <p role="alert">{context.demoMessage}</p>}
        <button disabled={context.pending} onClick={() => { void context.transitionDemo('start'); }}>Start</button>
        <button disabled={context.pending} onClick={() => { void context.transitionDemo('agent'); }}>Agent</button>
        <button disabled={context.pending} onClick={() => { void context.retry(); }}>Retry</button>
    </>;
}
function renderProvider() { return render(<StrictMode><AuthProvider><Consumer /></AuthProvider></StrictMode>); }
function active() { return { enabled: true, active: true, expiresAt: new Date(Date.now() + 1800000).toISOString() } as const; }
function anonymous() { api.getSession.mockRejectedValueOnce(new ApiError(401)); }
beforeEach(() => {
    vi.resetAllMocks();
    api.getCsrf.mockResolvedValue(tokenA);
    api.getSession.mockResolvedValue(requester);
    demo.getDemo.mockResolvedValue({ enabled: true, active: false, expiresAt: null });
});
afterEach(() => vi.useRealTimers());

describe('demo session lifecycle', () => {
    it('recovers an expired persisted cookie with one safe CSRF read before authentication discovery', async () => {
        api.getCsrf.mockRejectedValueOnce(new ApiError(401)).mockResolvedValue(tokenB);
        anonymous();
        renderProvider();
        await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('unauthenticated'));
        expect(api.getCsrf).toHaveBeenCalledTimes(2);
        expect(api.getSession).toHaveBeenCalledTimes(1);
        expect(api.getSession.mock.invocationCallOrder[0]).toBeGreaterThan(api.getCsrf.mock.invocationCallOrder[1]!);
        expect(demo.transitionDemo).not.toHaveBeenCalled();
    });
    it('bounds expired-cookie recovery and offers a safe explicit retry', async () => {
        api.getCsrf.mockRejectedValue(new ApiError(401));
        renderProvider();
        expect(await screen.findByRole('alert')).toHaveTextContent('could not check');
        expect(api.getCsrf).toHaveBeenCalledTimes(2);
        expect(api.getSession).not.toHaveBeenCalled();
    });
    it('starts once, fetches fresh CSRF before persisted session, and uses it for subsequent mutations', async () => {
        anonymous();
        demo.getDemo.mockResolvedValueOnce({ enabled: true, active: false, expiresAt: null }).mockResolvedValue(active());
        api.getCsrf.mockResolvedValueOnce(tokenA).mockResolvedValue(tokenB);
        renderProvider();
        await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('unauthenticated'));
        await userEvent.click(screen.getByRole('button', { name: 'Start' }));
        expect(await screen.findByText('REQUESTER')).toBeVisible();
        expect(demo.transitionDemo).toHaveBeenCalledExactlyOnceWith('start', tokenA);
        expect(api.getCsrf.mock.invocationCallOrder[1]).toBeGreaterThan(demo.transitionDemo.mock.invocationCallOrder[0]!);
        expect(api.getSession.mock.invocationCallOrder[1]).toBeGreaterThan(api.getCsrf.mock.invocationCallOrder[1]!);
        await expect(auth.getMutationCsrf()).resolves.toEqual(tokenB);
    });
    it('unmounts the old role while switching and suppresses competing actions until recovery finishes', async () => {
        demo.getDemo.mockResolvedValue(active());
        api.getSession.mockResolvedValueOnce(requester).mockResolvedValue(agent);
        let done!: () => void;
        demo.transitionDemo.mockReturnValue(new Promise<void>((resolve) => { done = resolve; }));
        renderProvider();
        await screen.findByText('REQUESTER');
        await act(async () => { void auth.transitionDemo('agent'); void auth.transitionDemo('requester'); });
        expect(screen.queryByText('REQUESTER')).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Agent' })).toBeDisabled();
        expect(demo.transitionDemo).toHaveBeenCalledTimes(1);
        await act(async () => done());
        expect(await screen.findByText('AGENT')).toBeVisible();
    });
    it('restores the persisted agent on reload without allocating a workspace', async () => {
        demo.getDemo.mockResolvedValue(active()); api.getSession.mockResolvedValue(agent);
        renderProvider();
        expect(await screen.findByText('AGENT')).toBeVisible();
        expect(demo.transitionDemo).not.toHaveBeenCalled();
    });
    it.each([
        [new ApiError(403), 'session changed'], [new ApiError(401), 'expired'],
        [new ApiError(429, undefined, 60), 'wait 60 seconds'], [new TypeError('offline'), 'could not confirm'],
    ])('recovers safe state after %s without replaying a POST', async (failure, message) => {
        anonymous(); anonymous();
        demo.transitionDemo.mockRejectedValue(failure);
        renderProvider();
        await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('unauthenticated'));
        await userEvent.click(screen.getByRole('button', { name: 'Start' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(message as string);
        expect(demo.transitionDemo).toHaveBeenCalledTimes(1);
        expect(api.getCsrf).toHaveBeenCalledTimes(2);
        expect(api.getSession).toHaveBeenCalledTimes(2);
        expect(screen.getByRole('button', { name: 'Start' })).toBeEnabled();
    });
    it('recovers an applied but unconfirmed switch using the server role and token', async () => {
        demo.getDemo.mockResolvedValue(active());
        api.getCsrf.mockResolvedValueOnce(tokenA).mockResolvedValue(tokenB);
        api.getSession.mockResolvedValueOnce(requester).mockResolvedValue(agent);
        demo.transitionDemo.mockRejectedValue(new TypeError('response lost'));
        renderProvider(); await screen.findByText('REQUESTER');
        await userEvent.click(screen.getByRole('button', { name: 'Agent' }));
        expect(await screen.findByText('AGENT')).toBeVisible();
        expect(await screen.findByRole('alert')).toHaveTextContent('could not confirm');
        await expect(auth.getMutationCsrf()).resolves.toEqual(tokenB);
        expect(demo.transitionDemo).toHaveBeenCalledTimes(1);
    });
    it('requires explicit safe recovery when the post-action session read fails', async () => {
        anonymous(); api.getSession.mockRejectedValueOnce(new ApiError(500)).mockResolvedValue(agent);
        demo.getDemo.mockResolvedValueOnce({ enabled: true, active: false, expiresAt: null }).mockResolvedValue(active());
        renderProvider();
        await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('unauthenticated'));
        await userEvent.click(screen.getByRole('button', { name: 'Start' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('Try again to check');
        await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
        expect(await screen.findByText('AGENT')).toBeVisible();
        expect(demo.transitionDemo).toHaveBeenCalledTimes(1);
    });
    it('expires locally at the server timestamp without polling, and retires the old token', async () => {
        vi.useFakeTimers();
        demo.getDemo.mockResolvedValue({ enabled: true, active: true, expiresAt: new Date(Date.now() + 1000).toISOString() });
        renderProvider();
        await act(async () => { await Promise.resolve(); });
        expect(screen.getByText('REQUESTER')).toBeVisible();
        await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
        expect(screen.queryByText('REQUESTER')).not.toBeInTheDocument();
        expect(screen.getByText(/demo session expired/)).toBeVisible();
        expect(api.getCsrf).toHaveBeenCalledTimes(1);
        expect(demo.getDemo).toHaveBeenCalledTimes(1);
        await expect(auth.getMutationCsrf()).rejects.toThrow('Authentication required');
    });
    it('rejects administrator or inconsistent active demo state', async () => {
        demo.getDemo.mockResolvedValue(active());
        api.getSession.mockResolvedValue({ ...requester, role: 'ADMIN' });
        renderProvider();
        expect(await screen.findByRole('alert')).toHaveTextContent('could not check');
        expect(screen.queryByText('ADMIN')).not.toBeInTheDocument();
    });
});
