import { useState } from 'react';
import { Outlet } from 'react-router';
import { useAuth } from '../auth/useAuth';

export function HomePage() {
    const { state, pending, logout } = useAuth();
    const [error, setError] = useState<string | undefined>();
    if (state.status !== 'authenticated') return null;

    return (
        <>
            <section className="account-panel" aria-label="Current account">
                <p>Welcome, {state.user.displayName}</p>
                <p>Role: {state.user.role}</p>
                {error && <p role="alert" className="error-message">{error}</p>}
                <button type="button" disabled={pending} onClick={() => {
                    setError(undefined);
                    void logout().then(setError);
                }}>{pending ? 'Signing out…' : 'Sign out'}</button>
            </section>
            <Outlet />
        </>
    );
}
