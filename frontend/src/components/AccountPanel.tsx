import { useState } from 'react';
import { useAuth } from '../auth/useAuth';

export function AccountPanel() {
    const { state, pending, logout } = useAuth();
    const [error, setError] = useState<string | undefined>();
    if (state.status !== 'authenticated') return null;

    return (
        <section className="account-panel" aria-label="Current account">
            <div className="account-identity">
                <p className="account-name">Welcome, {state.user.displayName}</p>
                <p className="account-role">Role: {state.user.role}</p>
            </div>
            <button className="secondary-button" type="button" disabled={pending} onClick={() => {
                setError(undefined);
                void logout().then(setError);
            }}>{pending ? 'Signing out…' : 'Sign out'}</button>
            {error && <p role="alert" className="error-message">{error}</p>}
        </section>
    );
}
