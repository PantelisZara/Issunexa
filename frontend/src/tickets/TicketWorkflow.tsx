import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/ApiError';
import { useAuth } from '../auth/useAuth';
import { changeTicketStatus, claimTicket } from './ticketApi';
import { ticketLabels, type Ticket } from './ticketTypes';
import { ticketTransitions } from './ticketWorkflow';

type Action = { kind: 'claim' } | { kind: 'status'; status: Ticket['status'] };
type ActionError = { message: string; reloadRequired: boolean };

export function TicketWorkflow({ ticket, onUpdated, onRefresh }: {
    ticket: Ticket; onUpdated: (ticket: Ticket) => void; onRefresh: () => void;
}) {
    const { state, pending, expireSession, getMutationCsrf } = useAuth();
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<ActionError>();
    const [notice, setNotice] = useState('');
    const busy = useRef(false);
    const request = useRef<AbortController | undefined>(undefined);
    const refreshCsrf = useRef(false);

    useEffect(() => () => request.current?.abort(), []);

    async function mutate(action: Action) {
        if (busy.current || pending || error?.reloadRequired) return;
        busy.current = true;
        setSubmitting(true);
        setError(undefined);
        setNotice('');
        const controller = new AbortController();
        request.current = controller;
        try {
            const csrf = await getMutationCsrf(refreshCsrf.current);
            if (controller.signal.aborted) return;
            refreshCsrf.current = false;
            const updated = action.kind === 'claim'
                ? await claimTicket(String(ticket.id), csrf, controller.signal)
                : await changeTicketStatus(String(ticket.id), action.status, csrf, controller.signal);
            if (!controller.signal.aborted) {
                onUpdated(updated);
                setNotice(action.kind === 'claim' ? 'Ticket claimed.' : 'Ticket status updated.');
            }
        } catch (failure) {
            if (controller.signal.aborted) return;
            if (failure instanceof ApiError && failure.status === 401) {
                expireSession();
            } else if (failure instanceof ApiError && failure.status === 403) {
                // Authorization and CSRF failures share the backend's generic 403 contract.
                refreshCsrf.current = true;
                setError({ message: 'This action was not permitted or your session changed. Try again to refresh your session.', reloadRequired: false });
            } else if (failure instanceof ApiError && failure.status === 409) {
                setError({ message: 'The ticket changed or this action is no longer available. Refresh the ticket before trying again.', reloadRequired: true });
            } else if (failure instanceof ApiError && failure.status === 404) {
                setError({ message: 'This ticket could not be found. Refresh to check its current state.', reloadRequired: true });
            } else {
                setError({ message: 'We could not confirm the update. Refresh the ticket before trying again.', reloadRequired: true });
            }
        } finally {
            busy.current = false;
            if (!controller.signal.aborted) setSubmitting(false);
        }
    }

    if (state.status !== 'authenticated' || state.user.role === 'REQUESTER') return null;
    const disabled = submitting || pending || !!error?.reloadRequired;
    const transitions = ticketTransitions[ticket.status];
    return (
        <section className="ticket-workflow" aria-labelledby="workflow-heading" aria-busy={submitting}>
            <h2 id="workflow-heading">Agent workflow</h2>
            <div className="ticket-actions">
                {ticket.assignee === null && <button type="button" disabled={disabled}
                    onClick={() => { void mutate({ kind: 'claim' }); }}>Claim ticket</button>}
                {transitions.map((status) => <button key={status} type="button" className="secondary-button" disabled={disabled}
                    onClick={() => { void mutate({ kind: 'status', status }); }}>Set status to {ticketLabels[status]}</button>)}
            </div>
            {transitions.length === 0 && <p className="muted">This ticket has no available status transitions.</p>}
            {submitting && <p role="status">Updating ticket…</p>}
            {notice && <p role="status">{notice}</p>}
            {error && <p role="alert" className="error-message">{error.message}</p>}
            {error?.reloadRequired && <button type="button" disabled={submitting || pending} onClick={onRefresh}>Refresh ticket</button>}
        </section>
    );
}
