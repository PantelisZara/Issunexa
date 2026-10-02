import { useAuth } from './useAuth';

export function AuthStatus() {
    const { state, pending, retry } = useAuth();
    if (state.status === 'error') {
        return (
            <section className="auth-panel">
                <h1>Session unavailable</h1>
                <p role="alert" className="error-message">{state.message}</p>
                <button type="button" disabled={pending} onClick={() => { void retry(); }}>Try again</button>
            </section>
        );
    }
    return <section className="auth-panel loading-state" role="status"><h1>Checking your session</h1><p><span className="loading-mark" aria-hidden="true" />Please wait…</p></section>;
}
