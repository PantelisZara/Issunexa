import { useEffect, useState, type SubmitEvent } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import { ApiError } from '../api/ApiError';
import { useAuth } from '../auth/useAuth';
import { getTickets } from './ticketApi';
import { readTicketQuery, serializeTicketQuery } from './ticketQuery';
import { ticketCategories, ticketLabels as labels, ticketPriorities, ticketStatuses, type TicketPage, type TicketQuery } from './ticketTypes';
import './tickets.css';

type Result = { attempt: number } & ({ status: 'success'; data: TicketPage } | { status: 'error'; message: string });

function errorMessage(error: unknown) {
    if (error instanceof ApiError && error.status === 403) return 'You do not have permission to view these tickets.';
    if (error instanceof ApiError && error.status === 400) return 'These list options could not be applied. Clear filters or try again.';
    return 'We could not load tickets. Please try again.';
}

function TicketControls({ query, change, reset }: {
    query: TicketQuery;
    change: (name: string, value: string) => void;
    reset: () => void;
}) {
    const [search, setSearch] = useState(query.q);

    function submit(event: SubmitEvent<HTMLFormElement>) {
        event.preventDefault();
        change('q', search.trim());
    }

    return (
        <div className="ticket-controls">
            <form className="ticket-search" onSubmit={submit} role="search" aria-label="Search tickets">
                <div className="ticket-field">
                    <label htmlFor="ticket-search">Search tickets</label>
                    <input id="ticket-search" type="search" maxLength={100} placeholder="Title or description"
                        value={search} onChange={(event) => setSearch(event.target.value)} />
                </div>
                <button type="submit">Search</button>
            </form>
            <div className="ticket-filters">
                {([
                    ['status', 'Status', ticketStatuses], ['priority', 'Priority', ticketPriorities],
                    ['category', 'Category', ticketCategories],
                ] as const).map(([name, label, values]) => (
                    <div className="ticket-field" key={name}>
                        <label htmlFor={`ticket-${name}`}>{label}</label>
                        <select id={`ticket-${name}`} value={query[name]} onChange={(event) => change(name, event.target.value)}>
                            <option value="">All {name === 'category' ? 'categories' : name === 'status' ? 'statuses' : 'priorities'}</option>
                            {values.map((value) => <option key={value} value={value}>{labels[value]}</option>)}
                        </select>
                    </div>
                ))}
                <div className="ticket-field">
                    <label htmlFor="ticket-sort">Sort by</label>
                    <select id="ticket-sort" value={query.sortBy} onChange={(event) => change('sortBy', event.target.value)}>
                        <option value="createdAt">Created</option>
                        <option value="updatedAt">Updated</option>
                        <option value="title">Title</option>
                    </select>
                </div>
                <div className="ticket-field">
                    <label htmlFor="ticket-direction">Sort direction</label>
                    <select id="ticket-direction" value={query.direction} onChange={(event) => change('direction', event.target.value)}>
                        <option value="desc">Descending</option>
                        <option value="asc">Ascending</option>
                    </select>
                </div>
                <div className="ticket-field">
                    <label htmlFor="ticket-size">Tickets per page</label>
                    <select id="ticket-size" value={query.size} onChange={(event) => change('size', event.target.value)}>
                        {[...new Set([10, 20, 50, 100, query.size])].sort((a, b) => a - b).map((size) => (
                            <option key={size} value={size}>{size}</option>
                        ))}
                    </select>
                </div>
            </div>
            <button type="button" className="secondary-button" onClick={() => { setSearch(''); reset(); }}>Clear filters</button>
        </div>
    );
}

function TicketResults({ data, navigate }: { data: TicketPage; navigate: (page: number) => void }) {
    const location = useLocation();
    const ticketListUrl = `${location.pathname}${location.search}`;
    return (
        <>
            <p role="status">{data.totalElements} {data.totalElements === 1 ? 'ticket' : 'tickets'}</p>
            {data.content.length === 0 ? (
                <div className="ticket-empty">
                    <h2>No tickets found</h2>
                    <p>Try changing or clearing filters, or return to a previous page.</p>
                </div>
            ) : (
                <div className="ticket-table-scroll" role="region" aria-label="Ticket list" tabIndex={0}>
                    <table className="ticket-table">
                        <caption className="visually-hidden">Tickets matching the current search and filters</caption>
                        <thead><tr>
                            <th scope="col">Title</th><th scope="col">Status</th><th scope="col">Priority</th>
                            <th scope="col">Category</th><th scope="col">Assignee</th><th scope="col">Created</th>
                        </tr></thead>
                        <tbody>{data.content.map((ticket) => (
                            <tr key={ticket.id}>
                                <th scope="row"><Link to={`/app/tickets/${ticket.id}`} state={{ ticketListUrl }}>{ticket.title}</Link></th>
                                <td>{labels[ticket.status]}</td><td>{labels[ticket.priority]}</td><td>{labels[ticket.category]}</td>
                                <td>{ticket.assignee?.displayName ?? 'Unassigned'}</td>
                                <td><time dateTime={ticket.createdAt}>{new Date(ticket.createdAt).toLocaleString()}</time></td>
                            </tr>
                        ))}</tbody>
                    </table>
                </div>
            )}
            <nav className="ticket-pagination" aria-label="Ticket pagination">
                <button type="button" disabled={data.first} onClick={() => navigate(data.page - 1)}>Previous</button>
                <span>{data.totalPages === 0 ? 'No pages' : `Page ${data.page + 1} · ${data.totalPages} total pages`}</span>
                <button type="button" disabled={data.last} onClick={() => navigate(data.page + 1)}>Next</button>
            </nav>
        </>
    );
}

function TicketLoader({ queryString, navigate }: { queryString: string; navigate: (page: number) => void }) {
    const { expireSession } = useAuth();
    const [attempt, setAttempt] = useState(0);
    const [result, setResult] = useState<Result>();

    useEffect(() => {
        const controller = new AbortController();
        void getTickets(readTicketQuery(new URLSearchParams(queryString)), controller.signal).then((data) => {
            if (!controller.signal.aborted) setResult({ attempt, status: 'success', data });
        }, (error: unknown) => {
            if (controller.signal.aborted) return;
            if (error instanceof ApiError && error.status === 401) {
                expireSession();
            } else {
                setResult({ attempt, status: 'error', message: errorMessage(error) });
            }
        });
        return () => controller.abort();
    }, [queryString, attempt, expireSession]);

    const current = result?.attempt === attempt ? result : undefined;
    return (
        <div className="ticket-results" aria-busy={!current}>
            {!current && <p role="status">Loading tickets…</p>}
            {current?.status === 'error' && <div>
                <p role="alert" className="error-message">{current.message}</p>
                <button type="button" onClick={() => setAttempt((value) => value + 1)}>Retry</button>
            </div>}
            {current?.status === 'success' && <TicketResults data={current.data} navigate={navigate} />}
        </div>
    );
}

export function TicketListPage() {
    const [params, setParams] = useSearchParams();
    const location = useLocation();
    const query = readTicketQuery(params);
    const queryString = serializeTicketQuery(query).toString();

    function change(name: string, value: string) {
        const next = serializeTicketQuery(query);
        if (value) next.set(name, value);
        else next.delete(name);
        next.set('page', '0');
        setParams(next);
    }

    return (
        <section className="ticket-workspace" aria-labelledby="tickets-heading">
            <h1 id="tickets-heading">Tickets</h1>
            <p className="muted">Search and browse your available tickets.</p>
            <Link className="ticket-create-link" to="/app/tickets/new"
                state={{ ticketListUrl: `${location.pathname}${location.search}` }}>Create ticket</Link>
            <TicketControls key={query.q} query={query} change={change} reset={() => setParams({})} />
            <TicketLoader key={queryString} queryString={queryString} navigate={(page) => {
                const next = serializeTicketQuery(query);
                next.set('page', String(page));
                setParams(next);
            }} />
        </section>
    );
}
