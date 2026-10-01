import { Navigate, Route, Routes } from 'react-router';
import { AuthProvider } from '../auth/AuthProvider';
import { RequireAuth } from '../auth/RequireAuth';
import { AppShell } from '../components/AppShell';
import { HomePage } from '../pages/HomePage';
import { NotFoundPage } from '../pages/NotFoundPage';
import { LoginPage } from '../pages/LoginPage';

export function App() {
    return (
        <AuthProvider>
            <Routes>
                <Route element={<AppShell />}>
                    <Route index element={<Navigate to="/app" replace />} />
                    <Route path="login" element={<LoginPage />} />
                    <Route element={<RequireAuth />}>
                        <Route path="app" element={<HomePage />} />
                    </Route>
                    <Route path="*" element={<NotFoundPage />} />
                </Route>
            </Routes>
        </AuthProvider>
    );
}
