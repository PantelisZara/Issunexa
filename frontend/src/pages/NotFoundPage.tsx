import { Link } from 'react-router';

export function NotFoundPage() {
    return (
        <section className="ticket-state">
            <h1>Page not found</h1>
            <p>The page you requested does not exist.</p>
            <Link to="/">Return to home</Link>
        </section>
    );
}
