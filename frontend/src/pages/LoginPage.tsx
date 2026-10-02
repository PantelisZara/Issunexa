import { useState, type SubmitEvent } from 'react';
import { Navigate } from 'react-router';
import { AuthStatus } from '../auth/AuthStatus';
import { useAuth } from '../auth/useAuth';
import { Brand } from '../components/Brand';

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
        <section className="auth-panel" aria-labelledby="login-heading">
            <Brand />
            <p className="auth-product-name">Issue &amp; Service Management</p>
            <h1 id="login-heading">Sign in to Issunexa</h1>
            <p className="muted">Use your account to continue.</p>
            <form onSubmit={(event) => { void submit(event); }} aria-busy={pending}>
                <div className="form-field">
                    <label htmlFor="email">Email</label>
                    <input id="email" name="email" type="email" autoComplete="username" required maxLength={254}
                        value={email} onChange={(event) => setEmail(event.target.value)} disabled={pending}
                        aria-describedby={message ? 'login-error' : undefined} />
                </div>
                <div className="form-field">
                    <label htmlFor="password">Password</label>
                    <input id="password" name="password" type="password" autoComplete="current-password" required
                        value={password} onChange={(event) => setPassword(event.target.value)} disabled={pending}
                        aria-describedby={message ? 'login-error' : undefined} />
                </div>
                {message && <p id="login-error" role="alert" className="error-message">{message}</p>}
                <button type="submit" disabled={pending}>{pending ? 'Signing in…' : 'Sign in'}</button>
            </form>
        </section>
    );
}
