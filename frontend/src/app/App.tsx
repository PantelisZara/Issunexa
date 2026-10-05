import { Navigate, Route, Routes } from 'react-router';
import { AuthProvider } from '../auth/AuthProvider';
import { RequireAuth } from '../auth/RequireAuth';
import { AppShell } from '../components/AppShell';
import { HomePage } from '../pages/HomePage';
import { NotFoundPage } from '../pages/NotFoundPage';
import { LoginPage } from '../pages/LoginPage';
import { TicketListPage } from '../tickets/TicketListPage';
import { TicketCreatePage } from '../tickets/TicketCreatePage';
import { TicketDetailPage } from '../tickets/TicketDetailPage';
import { ThemeProvider } from '../theme/ThemeProvider';

export function App() {
    return (
        <ThemeProvider>
            <AuthProvider>
                <Routes>
                    <Route element={<AppShell />}>
                        <Route index element={<Navigate to="/app" replace />} />
                        <Route path="login" element={<LoginPage />} />
                        <Route element={<RequireAuth />}>
                            <Route path="app" element={<HomePage />}>
                                <Route index element={<Navigate to="tickets" replace />} />
                                <Route path="tickets" element={<TicketListPage />} />
                                <Route path="tickets/new" element={<TicketCreatePage />} />
                                <Route path="tickets/:id" element={<TicketDetailPage />} />
                            </Route>
                        </Route>
                        <Route path="*" element={<NotFoundPage />} />
                    </Route>
                </Routes>
            </AuthProvider>
        </ThemeProvider>
    );
}
