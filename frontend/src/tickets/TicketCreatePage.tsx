import { useEffect, useRef, useState, type SubmitEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { ApiError } from '../api/ApiError';
import { useAuth } from '../auth/useAuth';
import { createTicket } from './ticketApi';
import { ticketListLocation } from './ticketNavigation';
import { isSupported, ticketCategories, ticketLabels, ticketPriorities, type CreateTicketInput } from './ticketTypes';
import './tickets.css';

const fieldMessages = {
    title: 'Enter a nonblank title of at most 255 characters.',
    description: 'Enter a nonblank description.',
    priority: 'Choose a supported priority.',
    category: 'Choose a supported category.',
};

type FormError = { message: string; fields?: Partial<Record<keyof CreateTicketInput, string>> };

function validationError(error: ApiError): FormError {
    const fields: FormError['fields'] = {};
    const errors = error.problem?.errors;
    if (Array.isArray(errors)) {
        for (const entry of errors) {
            const item: unknown = entry;
            if (typeof item === 'object' && item !== null && 'field' in item && 'message' in item
                && typeof item.message === 'string' && isSupported(['title', 'description', 'priority', 'category'] as const, item.field)) {
                fields[item.field] = fieldMessages[item.field];
            }
        }
    }
    return { message: 'We could not create the ticket. Check the ticket fields and try again.', fields };
}

export function TicketCreatePage() {
    const { getMutationCsrf, expireSession, pending } = useAuth();
    const location = useLocation();
    const returnTo = ticketListLocation(location.state);
    const navigate = useNavigate();
    const [title, setTitle] = useState('');
    const [description, setDescription] = useState('');
    const [priority, setPriority] = useState('');
    const [category, setCategory] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<FormError>();
    const busy = useRef(false);
    const request = useRef<AbortController | undefined>(undefined);
    const refreshCsrf = useRef(false);

    useEffect(() => () => request.current?.abort(), []);

    async function submit(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        if (busy.current || pending) return;
        if (!isSupported(ticketPriorities, priority) || !isSupported(ticketCategories, category)) {
            setError({ message: 'Choose a priority and category.' });
            return;
        }
        busy.current = true;
        setSubmitting(true);
        setError(undefined);
        const controller = new AbortController();
        request.current = controller;
        try {
            const csrf = await getMutationCsrf(refreshCsrf.current);
            if (controller.signal.aborted) return;
            refreshCsrf.current = false;
            const ticket = await createTicket({ title, description, priority, category }, csrf, controller.signal);
            if (!controller.signal.aborted) {
                await navigate(`/app/tickets/${ticket.id}`, { replace: true, state: { ticketListUrl: returnTo } });
            }
        } catch (failure) {
            if (controller.signal.aborted) return;
            if (failure instanceof ApiError && failure.status === 401) {
                expireSession();
            } else if (failure instanceof ApiError && failure.status === 400) {
                setError(validationError(failure));
            } else if (failure instanceof ApiError && failure.status === 403) {
                refreshCsrf.current = true;
                setError({ message: 'Your session changed or creation was not permitted. Submit again to refresh your session.' });
            } else {
                setError({ message: 'We could not confirm ticket creation. Check the ticket list before trying again.' });
            }
        } finally {
            busy.current = false;
            if (!controller.signal.aborted) setSubmitting(false);
        }
    }

    const disabled = submitting || pending;
    return (
        <section className="ticket-form-panel" aria-labelledby="create-heading">
            <Link to={returnTo}>Back to tickets</Link>
            <h1 id="create-heading">Create ticket</h1>
            <p className="muted">All fields are required.</p>
            <form aria-label="Create ticket" aria-busy={submitting} onSubmit={(event) => { void submit(event); }}>
                <div className="form-field">
                    <label htmlFor="create-title">Title</label>
                    <input id="create-title" name="title" required maxLength={255} value={title}
                        onChange={(event) => setTitle(event.target.value)} disabled={disabled}
                        aria-invalid={!!error?.fields?.title} aria-describedby={error?.fields?.title ? 'title-error' : undefined} />
                    {error?.fields?.title && <p id="title-error" className="error-message">{error.fields.title}</p>}
                </div>
                <div className="form-field">
                    <label htmlFor="create-description">Description</label>
                    <textarea id="create-description" name="description" required rows={7} value={description}
                        onChange={(event) => setDescription(event.target.value)} disabled={disabled}
                        aria-invalid={!!error?.fields?.description} aria-describedby={error?.fields?.description ? 'description-error' : undefined} />
                    {error?.fields?.description && <p id="description-error" className="error-message">{error.fields.description}</p>}
                </div>
                {([
                    ['priority', 'Priority', ticketPriorities, priority, setPriority],
                    ['category', 'Category', ticketCategories, category, setCategory],
                ] as const).map(([name, label, values, value, setValue]) => (
                    <div className="form-field ticket-field" key={name}>
                        <label htmlFor={`create-${name}`}>{label}</label>
                        <select id={`create-${name}`} name={name} required value={value} disabled={disabled}
                            onChange={(event) => setValue(event.target.value)} aria-invalid={!!error?.fields?.[name]}
                            aria-describedby={error?.fields?.[name] ? `${name}-error` : undefined}>
                            <option value="">Choose {name}</option>
                            {values.map((option) => <option key={option} value={option}>{ticketLabels[option]}</option>)}
                        </select>
                        {error?.fields?.[name] && <p id={`${name}-error`} className="error-message">{error.fields[name]}</p>}
                    </div>
                ))}
                {error && <p role="alert" className="error-message">{error.message}</p>}
                <div className="ticket-actions">
                    <button type="submit" disabled={disabled}>{submitting ? 'Creating ticket…' : 'Create ticket'}</button>
                    <Link to={returnTo}>Cancel</Link>
                </div>
            </form>
        </section>
    );
}
