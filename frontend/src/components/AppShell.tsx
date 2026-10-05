import { Link, NavLink, Outlet } from 'react-router';
import { useAuth } from '../auth/useAuth';
import { AccountPanel } from './AccountPanel';
import { Brand } from './Brand';
import { ThemeControl } from './ThemeControl';

export function AppShell() {
    const { state } = useAuth();
    const authenticated = state.status === 'authenticated';
    return (
        <>
            <a className="skip-link" href="#main-content">Skip to content</a>
            <header className="site-header">
                <div className="site-header-inner">
                    <Link className="brand" to="/" aria-label="Issunexa home"><Brand compact={!authenticated} /></Link>
                    {authenticated ? <>
                        <nav className="primary-navigation" aria-label="Primary navigation">
                            <NavLink to="/app/tickets">Tickets</NavLink>
                        </nav>
                        <AccountPanel />
                    </> : <p className="site-product-name">Issue &amp; Service Management</p>}
                    <ThemeControl />
                </div>
            </header>
            <main id="main-content" className={`main-content${authenticated ? '' : ' public-content'}`} tabIndex={-1}>
                <Outlet />
            </main>
        </>
    );
}
