import { Navigate, Outlet } from 'react-router';
import { AuthStatus } from './AuthStatus';
import { useAuth } from './useAuth';

export function RequireAuth() {
    const { state } = useAuth();
    if (state.status === 'loading' || state.status === 'error') return <AuthStatus />;
    if (state.status === 'unauthenticated') return <Navigate to="/login" replace />;
    return <Outlet />;
}
