import { Link, Outlet } from 'react-router';

export function AppShell() {
    return (
        <>
            <a className="skip-link" href="#main-content">Skip to content</a>
            <header className="site-header">
                <Link className="brand" to="/">Issunexa</Link>
            </header>
            <main id="main-content" className="main-content" tabIndex={-1}>
                <Outlet />
            </main>
        </>
    );
}
