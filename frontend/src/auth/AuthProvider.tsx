import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../api/ApiError';
import { AuthContext } from './AuthContext';
import * as authApi from './authApi';
import type { AuthState, CsrfMetadata } from './authTypes';

const unavailable = 'We could not check your session. Please try again.';

async function readSession(storeCsrf: (value: CsrfMetadata) => void) {
    // The CSRF request can establish a session; finish it before probing authentication.
    const csrf = await authApi.getCsrf();
    storeCsrf(csrf);
    try {
        const user = await authApi.getSession();
        return { csrf, state: { status: 'authenticated', user } satisfies AuthState };
    } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
            return { csrf, state: { status: 'unauthenticated' } satisfies AuthState };
        }
        throw error;
    }
}

export function AuthProvider({ children }: { children: ReactNode }) {
    const [state, setState] = useState<AuthState>({ status: 'loading' });
    const [pending, setPending] = useState(false);
    const csrf = useRef<CsrfMetadata | undefined>(undefined);
    const busy = useRef(false);
    const initialRequest = useRef<ReturnType<typeof readSession> | undefined>(undefined);

    const expireSession = useCallback(() => {
        csrf.current = undefined;
        setState({ status: 'unauthenticated', message: 'Your session expired. Please sign in again.' });
    }, []);

    useEffect(() => {
        let active = true;
        // Share the initial sequence across StrictMode effect replay to avoid competing anonymous sessions.
        initialRequest.current ??= readSession((value) => { csrf.current = value; });
        void initialRequest.current.then((result) => {
            if (active) {
                csrf.current = result.csrf;
                setState(result.state);
            }
        }, () => {
            if (active) {
                setState({ status: 'error', message: unavailable });
            }
        });
        return () => { active = false; };
    }, []);

    function begin() {
        if (busy.current) return false;
        busy.current = true;
        setPending(true);
        return true;
    }

    function finish() {
        busy.current = false;
        setPending(false);
    }

    async function refreshCsrf() {
        csrf.current = undefined;
        csrf.current = await authApi.getCsrf();
        return csrf.current;
    }

    async function getMutationCsrf(refresh = false) {
        if (state.status !== 'authenticated') throw new Error('Authentication required.');
        return refresh ? refreshCsrf() : csrf.current ?? refreshCsrf();
    }

    async function retry() {
        if (state.status !== 'error' || !begin()) return;
        setState({ status: 'loading' });
        csrf.current = undefined;
        try {
            const result = await readSession((value) => { csrf.current = value; });
            csrf.current = result.csrf;
            setState(result.state);
        } catch {
            setState({ status: 'error', message: unavailable });
        } finally {
            finish();
        }
    }

    async function login(email: string, password: string) {
        if (state.status !== 'unauthenticated' || !begin()) return;
        try {
            const current = csrf.current ?? await refreshCsrf();
            try {
                await authApi.login(email, password, current);
            } catch (error) {
                if (error instanceof ApiError && error.status === 401) {
                    return 'Invalid email or password.';
                }
                if (error instanceof ApiError && error.status === 403) {
                    try {
                        await refreshCsrf();
                    } catch {
                        return 'Your session could not be refreshed. Please try signing in again.';
                    }
                    return 'Your session changed. Please try signing in again.';
                }
                throw error;
            }
            setState({ status: 'loading' });
            await refreshCsrf();
            const user = await authApi.getSession();
            setState({ status: 'authenticated', user });
        } catch {
            // The server may already have authenticated; recover by probing it, never replaying credentials.
            csrf.current = undefined;
            setState({ status: 'error', message: unavailable });
        } finally {
            finish();
        }
    }

    async function completeLogout() {
        csrf.current = undefined;
        setState({ status: 'loading' });
        try {
            await refreshCsrf();
            setState({ status: 'unauthenticated' });
        } catch {
            setState({ status: 'unauthenticated',
                message: 'You are signed out. We could not prepare a new session; please try signing in again.' });
        }
    }

    async function logout() {
        if (state.status !== 'authenticated' || !begin()) return;
        try {
            const current = csrf.current ?? await refreshCsrf();
            try {
                await authApi.logout(current);
            } catch (error) {
                if (error instanceof ApiError && error.status === 401) {
                    await completeLogout();
                    return;
                }
                if (error instanceof ApiError && error.status === 403) {
                    try {
                        await refreshCsrf();
                    } catch {
                        return 'Sign out was not completed. Please try again when the connection is available.';
                    }
                    return 'Your session changed. Please try signing out again.';
                }
                throw error;
            }
            await completeLogout();
        } catch {
            return 'We could not confirm sign out. Please try again.';
        } finally {
            finish();
        }
    }

    return <AuthContext value={{ state, pending, login, logout, retry, expireSession, getMutationCsrf }}>{children}</AuthContext>;
}
