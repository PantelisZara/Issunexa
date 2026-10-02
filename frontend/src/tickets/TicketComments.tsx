import { useEffect, useRef, useState, type SubmitEvent } from 'react';
import { ApiError } from '../api/ApiError';
import { useAuth } from '../auth/useAuth';
import { createTicketComment, getTicketComments } from './ticketApi';
import type { TicketComment, TicketCommentPage } from './ticketTypes';

type Query = { page: number; target: 'page' | 'latest' | 'review' };
type Result = { query: Query } & ({ data: TicketCommentPage } | { error: string });
type FormError = { message: string; requiresRefresh: boolean; invalid?: boolean };

function CommentContent({ comment }: { comment: TicketComment }) {
    return <>
        <p className="ticket-comment-meta"><strong>{comment.author.displayName}</strong> · <time dateTime={comment.createdAt}>{new Date(comment.createdAt).toLocaleString()}</time></p>
        <p className="ticket-comment-body">{comment.body}</p>
    </>;
}

export function TicketComments({ ticketId, onNotFound }: { ticketId: string; onNotFound: () => void }) {
    const { expireSession, getMutationCsrf, pending } = useAuth();
    const [query, setQuery] = useState<Query>({ page: 0, target: 'page' });
    const [result, setResult] = useState<Result>();
    const [body, setBody] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [formError, setFormError] = useState<FormError>();
    const [created, setCreated] = useState<TicketComment>();
    const busy = useRef(false);
    const request = useRef<AbortController | undefined>(undefined);
    const refreshCsrf = useRef(false);

    useEffect(() => () => request.current?.abort(), []);

    useEffect(() => {
        const controller = new AbortController();
        async function load() {
            try {
                let data = await getTicketComments(ticketId, query.page, 20, controller.signal);
                if (controller.signal.aborted) return;
                // Use fresh server metadata to find the newest page; never invent totals or insert into a paginated response.
                if (query.target !== 'page' && !data.last && data.totalPages > 0) {
                    data = await getTicketComments(ticketId, data.totalPages - 1, 20, controller.signal);
                }
                if (controller.signal.aborted) return;
                setResult({ query, data });
                if (query.target === 'review') setFormError((error) => error?.requiresRefresh
                    ? { message: 'Comments refreshed. Check whether your comment was added before submitting again.', requiresRefresh: false } : error);
            } catch (error) {
                if (controller.signal.aborted) return;
                if (error instanceof ApiError && error.status === 401) expireSession();
                else if (error instanceof ApiError && error.status === 404) onNotFound();
                else setResult({ query, error: error instanceof ApiError && error.status === 403
                    ? 'Comment access was not permitted. Please try again.' : 'We could not load comments. Please try again.' });
            }
        }
        void load();
        return () => controller.abort();
    }, [ticketId, query, expireSession, onNotFound]);

    async function submit(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        if (busy.current || pending || formError?.requiresRefresh) return;
        if (body.trim().length === 0 || body.length > 4000) {
            setFormError({ message: 'Enter a nonblank comment of at most 4,000 characters.', requiresRefresh: false, invalid: true });
            return;
        }
        busy.current = true;
        setSubmitting(true);
        setFormError(undefined);
        setCreated(undefined);
        const controller = new AbortController();
        request.current = controller;
        try {
            const csrf = await getMutationCsrf(refreshCsrf.current);
            if (controller.signal.aborted) return;
            refreshCsrf.current = false;
            const comment = await createTicketComment(ticketId, body, csrf, controller.signal);
            if (controller.signal.aborted) return;
            setCreated(comment);
            setBody('');
            setQuery({ page: 0, target: 'latest' });
        } catch (failure) {
            if (controller.signal.aborted) return;
            if (failure instanceof ApiError && failure.status === 401) expireSession();
            else if (failure instanceof ApiError && failure.status === 404) onNotFound();
            else if (failure instanceof ApiError && failure.status === 400) {
                setFormError({ message: 'We could not add the comment. Enter a nonblank comment of at most 4,000 characters.', requiresRefresh: false, invalid: true });
            } else if (failure instanceof ApiError && failure.status === 403) {
                refreshCsrf.current = true;
                setFormError({ message: 'Commenting was not permitted or your session changed. Submit again to refresh your session.', requiresRefresh: false });
            } else {
                setFormError({ message: 'We could not confirm whether the comment was added. Refresh comments and check before submitting again.', requiresRefresh: true });
            }
        } finally {
            busy.current = false;
            if (!controller.signal.aborted) setSubmitting(false);
        }
    }

    const current = result?.query === query ? result : undefined;
    const disabled = submitting || pending;
    return (
        <section className="ticket-activity" aria-labelledby="comments-heading">
            <div className="ticket-section-heading">
                <h2 id="comments-heading">Comments</h2>
                <button type="button" className="secondary-button" disabled={disabled}
                    onClick={() => setQuery({ page: 0, target: formError?.requiresRefresh ? 'review' : 'latest' })}>Refresh comments</button>
            </div>
            <p className="muted">Oldest first. Comments are plain text and cannot be edited or deleted.</p>
            {!current ? <p role="status">Loading comments…</p> : 'error' in current ? <>
                <p role="alert" className="error-message">{current.error}</p>
                <button type="button" disabled={disabled} onClick={() => setQuery({ ...query })}>Retry comments</button>
            </> : <>
                {current.data.content.length === 0 ? <p>No comments on this page.</p> : (
                    <ol className="ticket-activity-list" aria-label="Ticket comments">
                        {current.data.content.map((comment) => <li key={comment.id}><CommentContent comment={comment} /></li>)}
                    </ol>
                )}
                <nav className="ticket-pagination" aria-label="Comments pagination">
                    <button type="button" disabled={disabled || current.data.first} onClick={() => setQuery({ page: current.data.page - 1, target: 'page' })}>Previous comments</button>
                    <span>{current.data.totalPages === 0 ? 'No comment pages' : `Page ${current.data.page + 1} of ${current.data.totalPages}`} · {current.data.totalElements} comments</span>
                    <button type="button" disabled={disabled || current.data.last} onClick={() => setQuery({ page: current.data.page + 1, target: 'page' })}>Next comments</button>
                </nav>
            </>}
            {created && <div className="ticket-comment-confirmation">
                <p role="status">Comment added.</p>
                {(!current || !('data' in current) || !current.data.content.some((comment) => comment.id === created.id))
                    && <CommentContent comment={created} />}
            </div>}
            <form className="ticket-comment-form" aria-label="Add comment" aria-busy={submitting} onSubmit={(event) => { void submit(event); }}>
                <div className="form-field">
                    <label htmlFor="comment-body">New comment</label>
                    <textarea id="comment-body" name="body" rows={4} required maxLength={4000} value={body} disabled={disabled}
                        onChange={(event) => setBody(event.target.value)} aria-invalid={!!formError?.invalid}
                        aria-describedby={formError ? 'comment-help comment-error' : 'comment-help'} />
                    <p id="comment-help" className="muted">Up to 4,000 characters.</p>
                </div>
                {formError && <p id="comment-error" role="alert" className="error-message">{formError.message}</p>}
                <button type="submit" disabled={disabled || !!formError?.requiresRefresh}>{submitting ? 'Adding comment…' : 'Add comment'}</button>
            </form>
        </section>
    );
}
