import { useEffect, useState } from 'react';
import { ApiError } from '../api/ApiError';
import { useAuth } from '../auth/useAuth';
import { getTicketHistory } from './ticketApi';
import { ticketLabels, type TicketHistoryEntry, type TicketHistoryPage } from './ticketTypes';

function description(entry: TicketHistoryEntry) {
    switch (entry.type) {
        case 'TICKET_CREATED': return `Ticket created with status ${ticketLabels[entry.newStatus]}.`;
        case 'STATUS_CHANGED': return `Status changed from ${ticketLabels[entry.previousStatus]} to ${ticketLabels[entry.newStatus]}.`;
        case 'ASSIGNEE_CLAIMED': return `Ticket claimed by ${entry.assignee.displayName}.`;
    }
}

type Query = { page: number };
type Result = { query: Query } & ({ data: TicketHistoryPage } | { error: string });

export function TicketHistory({ ticketId, onNotFound }: { ticketId: string; onNotFound: () => void }) {
    const { expireSession } = useAuth();
    const [query, setQuery] = useState<Query>({ page: 0 });
    const [result, setResult] = useState<Result>();

    useEffect(() => {
        const controller = new AbortController();
        void getTicketHistory(ticketId, query.page, 20, controller.signal).then((data) => {
            if (!controller.signal.aborted) setResult({ query, data });
        }, (error: unknown) => {
            if (controller.signal.aborted) return;
            if (error instanceof ApiError && error.status === 401) expireSession();
            else if (error instanceof ApiError && error.status === 404) onNotFound();
            else setResult({ query, error: error instanceof ApiError && error.status === 403
                ? 'History access was not permitted. Please try again.' : 'We could not load history. Please try again.' });
        });
        return () => controller.abort();
    }, [ticketId, query, expireSession, onNotFound]);

    const current = result?.query === query ? result : undefined;
    return (
        <section className="ticket-activity" aria-labelledby="history-heading">
            <div className="ticket-section-heading">
                <h2 id="history-heading">Lifecycle history</h2>
                <button type="button" className="secondary-button" disabled={!current} onClick={() => setQuery({ page: 0 })}>Refresh history</button>
            </div>
            <p className="muted">Newest first. Earlier activity may not have been recorded.</p>
            {!current ? <p className="loading-state" role="status"><span className="loading-mark" aria-hidden="true" />Loading history…</p> : 'error' in current ? <>
                <p role="alert" className="error-message">{current.error}</p>
                <button type="button" onClick={() => setQuery({ ...query })}>Retry history</button>
            </> : <>
                {current.data.content.length === 0 ? <p>No lifecycle events recorded on this page.</p> : (
                    <ol className="ticket-activity-list ticket-history-list" aria-label="History events">
                        {current.data.content.map((entry) => <li key={entry.id}>
                            <p>{description(entry)}</p>
                            <p className="ticket-history-actor">By {entry.actor.displayName} · <time dateTime={entry.createdAt}>{new Date(entry.createdAt).toLocaleString()}</time></p>
                        </li>)}
                    </ol>
                )}
                <nav className="ticket-pagination" aria-label="History pagination">
                    <button type="button" className="secondary-button" disabled={current.data.first} onClick={() => setQuery({ page: current.data.page - 1 })}>Previous history</button>
                    <span>{current.data.totalPages === 0 ? 'No history pages' : `Page ${current.data.page + 1} of ${current.data.totalPages}`} · {current.data.totalElements} events</span>
                    <button type="button" className="secondary-button" disabled={current.data.last} onClick={() => setQuery({ page: current.data.page + 1 })}>Next history</button>
                </nav>
            </>}
        </section>
    );
}
