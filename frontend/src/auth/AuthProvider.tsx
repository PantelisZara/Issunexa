import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../api/ApiError';
import { AuthContext } from './AuthContext';
import * as authApi from './authApi';
import * as demoApi from './demoApi';
import type { AuthState, CsrfMetadata } from './authTypes';

const unavailable = 'We could not check your session. Please try again.';

async function freshCsrf() {
    try {
        return await authApi.getCsrf();
    } catch (error) {
        // An expired demo cookie is invalidated on its first API request. Retry this safe read once.
        if (error instanceof ApiError && error.status === 401) return authApi.getCsrf();
        throw error;
    }
}

async function readSession(storeCsrf: (value: CsrfMetadata) => void) {
    // The CSRF request can establish a session; finish it before probing authentication.
    const csrf = await freshCsrf();
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

async function readAuth(storeCsrf: (value: CsrfMetadata) => void) {
    const result = await readSession(storeCsrf);
    const metadata = await demoApi.getDemo();
    if (metadata.enabled && ((result.state.status === 'authenticated'
        && (!metadata.active || result.state.user.role === 'ADMIN'))
        || (metadata.active && result.state.status !== 'authenticated'))) {
        throw new TypeError('Invalid demo session.');
    }
    return { ...result, demo: metadata };
}

export function AuthProvider({ children }: { children: ReactNode }) {
    const [state, setState] = useState<AuthState>({ status: 'loading' });
    const [pending, setPending] = useState(false);
    const [demo, setDemo] = useState<demoApi.DemoMetadata>();
    const [demoMessage, setDemoMessage] = useState<string>();
    const demoRef = useRef<demoApi.DemoMetadata | undefined>(undefined);

    const storeDemo = useCallback((value: demoApi.DemoMetadata) => {
        demoRef.current = value;
        setDemo(value);
    }, []);

    const csrf = useRef<CsrfMetadata | undefined>(undefined);
    const busy = useRef(false);
    const initialRequest = useRef<ReturnType<typeof readAuth> | undefined>(undefined);

    const expireSession = useCallback(() => {
        csrf.current = undefined;
        setDemoMessage(undefined);
        if (demoRef.current?.enabled) {
            storeDemo({ enabled: true, active: false, expiresAt: null });
            setState({ status: 'unauthenticated', message: 'Your demo session expired. Start a new synthetic demo to continue.' });
        } else setState({ status: 'unauthenticated', message: 'Your session expired. Please sign in again.' });
    }, [storeDemo]);

    useEffect(() => {
        let active = true;
        // Share the initial sequence across StrictMode effect replay to avoid competing anonymous sessions.
        initialRequest.current ??= readAuth((value) => { csrf.current = value; });
        void initialRequest.current.then((result) => {
            if (active) {
                csrf.current = result.csrf;
                storeDemo(result.demo);
                setState(result.state);
            }
        }, () => {
            if (active) {
                setState({ status: 'error', message: unavailable });
            }
        });
        return () => { active = false; };
    }, [storeDemo]);

    useEffect(() => {
        if (!demo?.enabled || !demo.active) return;
        const timer = window.setTimeout(expireSession, Math.max(0, Date.parse(demo.expiresAt) - Date.now()));
        return () => window.clearTimeout(timer);
    }, [demo, expireSession]);

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
        csrf.current = await freshCsrf();
        return csrf.current;
    }

    async function getMutationCsrf(refresh = false) {
        if (state.status !== 'authenticated' || busy.current) throw new Error('Authentication required.');
        return refresh ? refreshCsrf() : csrf.current ?? refreshCsrf();
    }

    async function retry() {
        if (state.status !== 'error' || !begin()) return;
        setState({ status: 'loading' });
        csrf.current = undefined;
        try {
            const result = await readAuth((value) => { csrf.current = value; });
            csrf.current = result.csrf;
            storeDemo(result.demo);
            setState(result.state);
        } catch {
            setState({ status: 'error', message: unavailable });
        } finally {
            finish();
        }
    }

    async function login(email: string, password: string) {
        if (demo?.enabled || state.status !== 'unauthenticated' || !begin()) return;
        try {
            const current = csrf.current ?? await refreshCsrf();
            try {
                await authApi.login(email, password, current);
            } catch (error) {
                if (error instanceof ApiError && error.status === 401) {
                    return 'Invalid email or password.';
                }
                if (error instanceof ApiError && error.status === 429) {
                    return error.retryAfterSeconds === undefined
                        ? 'Too many sign-in attempts. Please wait before trying again.'
                        : `Too many sign-in attempts. Please wait ${error.retryAfterSeconds} seconds before trying again.`;
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
        setDemoMessage(undefined);
        if (demoRef.current?.enabled) storeDemo({ enabled: true, active: false, expiresAt: null });
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

    async function transitionDemo(action: demoApi.DemoAction) {
        if (!demo?.enabled || (action === 'start' ? state.status !== 'unauthenticated' : state.status !== 'authenticated') || !begin()) return;
        setDemoMessage(undefined);
        const previousCsrf = csrf.current;
        csrf.current = undefined;
        setState({ status: 'loading' });
        let message: string | undefined;
        try {
            const token = previousCsrf ?? await freshCsrf();
            await demoApi.transitionDemo(action, token);
        } catch (error) {
            if (error instanceof ApiError && error.status === 429) {
                message = error.retryAfterSeconds === undefined
                    ? 'The synthetic demo is at capacity. Please wait before trying again.'
                    : `The synthetic demo is at capacity. Please wait ${error.retryAfterSeconds} seconds before trying again.`;
            } else if (error instanceof ApiError && error.status === 401) {
                message = 'Your demo session expired. Start a new synthetic demo to continue.';
            } else if (error instanceof ApiError && error.status === 403) {
                message = 'Your demo session changed. Check the current role before trying the action again.';
            } else {
                message = 'We could not confirm the demo action. Check the current role before trying again.';
            }
        }
        try {
            // Always retire the old token and recover persisted state through safe reads. Never replay a POST.
            const result = await readAuth((value) => { csrf.current = value; });
            csrf.current = result.csrf;
            storeDemo(result.demo);
            setState(result.state);
            setDemoMessage(message);
        } catch {
            csrf.current = undefined;
            setState({ status: 'error', message: 'We could not check the demo session. Try again to check its current state.' });
        } finally {
            finish();
        }
    }

    return <AuthContext value={{ state, demo, demoMessage, transitionDemo, pending, login, logout, retry, expireSession, getMutationCsrf }}>{children}</AuthContext>;
}
