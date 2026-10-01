import { useState, type SubmitEvent } from 'react';
import { Navigate } from 'react-router';
import { AuthStatus } from '../auth/AuthStatus';
import { useAuth } from '../auth/useAuth';

export function LoginPage() {
    const { state, pending, login } = useAuth();
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState<string | undefined>();

    if (state.status === 'authenticated') return <Navigate to="/app" replace />;
    if (state.status === 'loading' || state.status === 'error') return <AuthStatus />;

    async function submit(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        if (pending) return;
        setError(undefined);
        const message = await login(email, password);
        setPassword('');
        setError(message);
    }

    const message = error ?? state.message;
    return (
        <section className="auth-panel">
            <h1>Sign in to Issunexa</h1>
            <form onSubmit={(event) => { void submit(event); }} aria-busy={pending}>
                <div className="form-field">
                    <label htmlFor="email">Email</label>
                    <input id="email" name="email" type="email" autoComplete="username" required maxLength={254}
                        value={email} onChange={(event) => setEmail(event.target.value)} disabled={pending} />
                </div>
                <div className="form-field">
                    <label htmlFor="password">Password</label>
                    <input id="password" name="password" type="password" autoComplete="current-password" required
                        value={password} onChange={(event) => setPassword(event.target.value)} disabled={pending} />
                </div>
                <div aria-live="polite" aria-atomic="true">
                    {message && <p role="alert" className="error-message">{message}</p>}
                </div>
                <button type="submit" disabled={pending}>{pending ? 'Signing in…' : 'Sign in'}</button>
            </form>
        </section>
    );
}
