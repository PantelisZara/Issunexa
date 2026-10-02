import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import { ApiError } from '../api/ApiError';
import { useAuth } from '../auth/useAuth';
import { getTicket } from './ticketApi';
import { ticketListLocation } from './ticketNavigation';
import type { Ticket } from './ticketTypes';
import { TicketBadge } from './TicketBadge';
import { TicketWorkflow } from './TicketWorkflow';
import { TicketComments } from './TicketComments';
import { TicketHistory } from './TicketHistory';
import './tickets.css';

type Result = { attempt: number } & ({ status: 'success'; ticket: Ticket } | { status: 'error'; notFound: boolean });

function TicketDetail({ id }: { id: string }) {
    const { expireSession } = useAuth();
    const [attempt, setAttempt] = useState(0);
    const [result, setResult] = useState<Result>();
    const [historyRevision, setHistoryRevision] = useState(0);
    const notFound = useCallback(() => setResult({ attempt, status: 'error', notFound: true }), [attempt]);

    useEffect(() => {
        const controller = new AbortController();
        void getTicket(id, controller.signal).then((ticket) => {
            if (!controller.signal.aborted) setResult({ attempt, status: 'success', ticket });
        }, (error: unknown) => {
            if (controller.signal.aborted) return;
            if (error instanceof ApiError && error.status === 401) {
                expireSession();
            } else {
                setResult({ attempt, status: 'error', notFound: error instanceof ApiError && error.status === 404 });
            }
        });
        return () => controller.abort();
    }, [id, attempt, expireSession]);

    const current = result?.attempt === attempt ? result : undefined;
    if (!current) return <div className="loading-state" aria-busy="true"><h1>Ticket details</h1><p role="status"><span className="loading-mark" aria-hidden="true" />Loading ticket…</p></div>;
    if (current.status === 'error') return <div className="ticket-state">
        <h1>{current.notFound ? 'Ticket not found' : 'Ticket details'}</h1>
        <p role="alert" className="error-message">{current.notFound
            ? 'This ticket could not be found.' : 'We could not load this ticket. Please try again.'}</p>
        <button type="button" onClick={() => setAttempt((value) => value + 1)}>Retry</button>
    </div>;

    const ticket = current.ticket;
    return (
        <>
            <header className="ticket-detail-heading">
                <p className="eyebrow">Ticket #{ticket.id}</p>
                <h1>{ticket.title}</h1>
                <dl className="ticket-classification">
                    <div><dt>Status</dt><dd><TicketBadge kind="status" value={ticket.status} /></dd></div>
                    <div><dt>Priority</dt><dd><TicketBadge kind="priority" value={ticket.priority} /></dd></div>
                    <div><dt>Category</dt><dd><TicketBadge kind="category" value={ticket.category} /></dd></div>
                </dl>
            </header>
            <section className="ticket-description-section" aria-labelledby="description-heading">
                <h2 id="description-heading">Description</h2>
                <p className="ticket-description">{ticket.description}</p>
            </section>
            <dl className="ticket-metadata">
                <div><dt>Assignee</dt><dd>{ticket.assignee?.displayName ?? 'Unassigned'}</dd></div>
                <div><dt>Created</dt><dd><time dateTime={ticket.createdAt}>{new Date(ticket.createdAt).toLocaleString()}</time></dd></div>
                <div><dt>Updated</dt><dd><time dateTime={ticket.updatedAt}>{new Date(ticket.updatedAt).toLocaleString()}</time></dd></div>
            </dl>
            <TicketWorkflow ticket={ticket} onUpdated={(updated) => {
                setResult({ attempt, status: 'success', ticket: updated });
                setHistoryRevision((value) => value + 1);
            }}
                onRefresh={() => setAttempt((value) => value + 1)} />
            <div className="ticket-activity-layout">
                <TicketComments ticketId={id} onNotFound={notFound} />
                <TicketHistory key={historyRevision} ticketId={id} onNotFound={notFound} />
            </div>
        </>
    );
}

export function TicketDetailPage() {
    const { id } = useParams();
    const location = useLocation();
    return (
        <section className="ticket-detail-panel" aria-label="Ticket details">
            <Link className="back-link" to={ticketListLocation(location.state)}><span aria-hidden="true">←</span> Back to tickets</Link>
            <TicketDetail key={id} id={id ?? ''} />
        </section>
    );
}
