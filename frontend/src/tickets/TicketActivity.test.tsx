import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useNavigate } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../app/App';
import { comment, commentPage, historyEntries, historyPage, ticket } from '../test/ticketFixtures';

const fetchMock = vi.fn<typeof fetch>();
const commentsMock = vi.fn<(path: string, options?: RequestInit) => Promise<Response>>();
const historyMock = vi.fn<(path: string, options?: RequestInit) => Promise<Response>>();
const createMock = vi.fn<(options?: RequestInit) => Promise<Response>>();
const workflowMock = vi.fn<() => Promise<Response>>();
const csrfMock = vi.fn<() => Promise<Response>>();
const csrfA = { token: 'synthetic-A', headerName: 'X-CUSTOM-CSRF' };
const csrfB = { token: 'synthetic-B', headerName: 'X-CUSTOM-CSRF' };
let role: string;
function problem(status: number) {
    return Response.json({ status, detail: 'Internal diagnostic', errors: [{ field: 'body', message: 'Internal field diagnostic' }] }, {
        status, headers: { 'Content-Type': 'application/problem+json' },
    });
}
function Navigation() {
    const navigate = useNavigate();
    return <button onClick={() => { void navigate('/app/tickets/43'); }}>Open another ticket</button>;
}
function renderDetail() {
    return render(<MemoryRouter initialEntries={['/app/tickets/42']}><App /><Navigation /></MemoryRouter>);
}
const comments = () => within(screen.getByRole('region', { name: 'Comments' }));
const history = () => within(screen.getByRole('region', { name: 'Lifecycle history' }));
async function loaded() { await screen.findByRole('heading', { name: ticket.title, level: 1 }); }
async function submitComment(body = 'New comment body') {
    fireEvent.change(screen.getByLabelText('New comment'), { target: { value: body } });
    await userEvent.click(screen.getByRole('button', { name: 'Add comment' }));
}
beforeEach(() => {
    vi.resetAllMocks();
    vi.stubGlobal('fetch', fetchMock);
    role = 'REQUESTER';
    commentsMock.mockImplementation(async () => Response.json(commentPage()));
    historyMock.mockImplementation(async () => Response.json(historyPage()));
    csrfMock.mockImplementation(async () => Response.json(csrfMock.mock.calls.length === 1 ? csrfA : csrfB));
    createMock.mockImplementation(async () => Response.json({ ...comment, id: 13, body: 'Server comment body' }, { status: 201 }));
    workflowMock.mockImplementation(async () => Response.json(ticket));
    fetchMock.mockImplementation(async (path, options) => {
        if (path === '/api/auth/csrf') return csrfMock();
        if (path === '/api/auth/session') return Response.json({ id: 3, email: 'synthetic@example.test', displayName: 'Riley Requester', role });
        if (path === '/api/auth/logout') return new Response(null, { status: 204 });
        const url = new URL(String(path), 'http://localhost');
        if (url.pathname.endsWith('/comments')) return options?.method === 'POST' ? createMock(options) : commentsMock(String(path), options);
        if (url.pathname.endsWith('/history')) return historyMock(String(path), options);
        if (url.pathname.endsWith('/claim') || url.pathname.endsWith('/status')) return workflowMock();
        return Response.json({ ...ticket, assignee: null, id: Number(url.pathname.split('/').at(-1)), title: url.pathname.endsWith('/43') ? 'Another ticket' : ticket.title });
    });
});
afterEach(() => vi.unstubAllGlobals());

describe('Comments and history display', () => {
    it.each(['comments', 'history'] as const)('loads %s independently while the other section and core details remain usable', async (kind) => {
        (kind === 'comments' ? commentsMock : historyMock).mockReturnValue(new Promise(() => {}));
        renderDetail();
        await loaded();
        const section = kind === 'comments' ? comments : history;
        expect(section().getByRole('status')).toHaveTextContent(`Loading ${kind}`);
        expect(await (kind === 'comments' ? history : comments)().findByRole('list')).toBeVisible();
        expect(screen.getByRole('heading', { name: ticket.title })).toBeVisible();
        expect(comments().getByRole('button', { name: 'Add comment' })).toBeEnabled();
    });

    it('renders comment author, plain text and timestamp in server order', async () => {
        commentsMock.mockImplementation(async () => Response.json(commentPage([
            { ...comment, body: 'First line\n<script>text only</script>' }, { ...comment, id: 13, body: 'Second comment' },
        ])));
        renderDetail();
        await loaded();
        const list = await comments().findByRole('list', { name: 'Ticket comments' });
        const items = within(list).getAllByRole('listitem');
        expect(items[0]).toHaveTextContent('Riley Requester');
        expect(items[0]).toHaveTextContent('First line <script>text only</script>');
        expect(items[1]).toHaveTextContent('Second comment');
        expect(list.querySelector('script')).toBeNull();
        expect(list.querySelector('time')).toHaveAttribute('datetime', comment.createdAt);
        expect(comments().queryByText('synthetic@example.test')).not.toBeInTheDocument();
        expect(comments().queryByRole('button', { name: /edit|delete/i })).not.toBeInTheDocument();
    });

    it('renders every history type with actors and timestamps in backend order, including ties', async () => {
        renderDetail();
        await loaded();
        const list = await history().findByRole('list', { name: 'History events' });
        const events = within(list).getAllByRole('listitem');
        expect(events[0]).toHaveTextContent('Status changed from Open to In progress.');
        expect(events[1]).toHaveTextContent('Ticket claimed by Alice Agent.');
        expect(events[2]).toHaveTextContent('Ticket created with status Open.');
        expect(events[0]).toHaveTextContent('By Alice Agent');
        expect(events[2]).toHaveTextContent('By Riley Requester');
        expect(list.querySelector('time')).toHaveAttribute('datetime', historyEntries[0]?.createdAt);
        expect(history().queryByText(comment.body)).not.toBeInTheDocument();
        expect(history().queryByRole('textbox')).not.toBeInTheDocument();
    });

    it('shows empty comments/history without fabricating creation events or totals', async () => {
        commentsMock.mockImplementation(async () => Response.json(commentPage([])));
        historyMock.mockImplementation(async () => Response.json(historyPage([])));
        renderDetail();
        await loaded();
        expect(await comments().findByText('No comments on this page.')).toBeVisible();
        expect(await history().findByText('No lifecycle events recorded on this page.')).toBeVisible();
        expect(history().getByText('Newest first. Earlier activity may not have been recorded.')).toBeVisible();
        expect(history().queryByText('Ticket created with status Open.')).not.toBeInTheDocument();
        expect(comments().getByText('No comment pages · 0 comments')).toBeVisible();
        for (const kind of ['comments', 'history'] as const) {
            const section = kind === 'comments' ? comments() : history();
            expect(section.getByRole('button', { name: `Previous ${kind}` })).toBeDisabled();
            expect(section.getByRole('button', { name: `Next ${kind}` })).toBeDisabled();
        }
    });

    it.each(['comments', 'history'] as const)('paginates %s using server metadata', async (kind) => {
        const mock = kind === 'comments' ? commentsMock : historyMock;
        const section = kind === 'comments' ? comments : history;
        mock.mockImplementation(async (path) => {
            const page = Number(new URL(path, 'http://localhost').searchParams.get('page'));
            const meta = { page, totalElements: 21, totalPages: 2, first: page === 0, last: page === 1 };
            return Response.json(kind === 'comments' ? commentPage([comment], meta) : historyPage(historyEntries.slice(2), meta));
        });
        renderDetail();
        await loaded();
        await section().findByRole('list');
        await userEvent.click(section().getByRole('button', { name: `Next ${kind}` }));
        expect(await section().findByText(`Page 2 of 2 · 21 ${kind === 'comments' ? 'comments' : 'events'}`)).toBeVisible();
        expect(section().getByRole('button', { name: `Next ${kind}` })).toBeDisabled();
        await userEvent.click(section().getByRole('button', { name: `Previous ${kind}` }));
        await section().findByText(`Page 1 of 2 · 21 ${kind === 'comments' ? 'comments' : 'events'}`);
        expect(mock.mock.calls.map(([path]) => path)).toEqual([
            `/api/tickets/42/${kind}?page=0&size=20`, `/api/tickets/42/${kind}?page=1&size=20`, `/api/tickets/42/${kind}?page=0&size=20`,
        ]);
    });
});

describe('Comment append', () => {
    it.each(['REQUESTER', 'AGENT', 'ADMIN'])('offers commenting to authenticated %s', async (value) => {
        role = value;
        renderDetail();
        await loaded();
        expect(comments().getByRole('form', { name: 'Add comment' })).toBeVisible();
        expect(comments().getByLabelText('New comment')).toHaveAttribute('maxlength', '4000');
        expect(comments().getByLabelText('New comment')).toBeRequired();
        if (value === 'REQUESTER') expect(screen.queryByRole('region', { name: 'Agent workflow' })).not.toBeInTheDocument();
    });

    it('retains the draft and blocks duplicate submits while pending, then clears on decoded success and shows server data', async () => {
        let resolve!: (value: Response) => void;
        createMock.mockImplementation(() => new Promise((done) => { resolve = done; }));
        renderDetail();
        await loaded();
        await submitComment('  Submitted draft  ');
        expect(comments().getByLabelText('New comment')).toHaveValue('  Submitted draft  ');
        expect(comments().getByLabelText('New comment')).toBeDisabled();
        expect(comments().getByRole('button', { name: 'Adding comment…' })).toBeDisabled();
        fireEvent.submit(comments().getByRole('form', { name: 'Add comment' }));
        expect(createMock).toHaveBeenCalledTimes(1);
        expect(JSON.parse(String(createMock.mock.calls[0]?.[0]?.body))).toEqual({ body: '  Submitted draft  ' });
        const created = { ...comment, id: 13, body: 'Canonical server body', author: { id: 8, displayName: 'Server Author' } };
        commentsMock.mockImplementation(async () => Response.json(commentPage([comment, created])));
        await act(async () => resolve(Response.json(created, { status: 201 })));
        expect(await comments().findByText('Canonical server body')).toBeVisible();
        expect(comments().getByText('Server Author')).toBeVisible();
        expect(comments().getByLabelText('New comment')).toHaveValue('');
        expect(comments().getByRole('status')).toHaveTextContent('Comment added.');
        expect(comments().getAllByText('Canonical server body')).toHaveLength(1);
        expect(commentsMock).toHaveBeenCalledTimes(2);
        expect(historyMock).toHaveBeenCalledTimes(1);
    });

    it('finds the newest comments page from fresh server metadata after append', async () => {
        renderDetail();
        await loaded();
        await comments().findByText(comment.body);
        const created = { ...comment, id: 13, body: 'Server comment body' };
        commentsMock.mockImplementation(async (path) => {
            const page = Number(new URL(path, 'http://localhost').searchParams.get('page'));
            return Response.json(commentPage(page === 2 ? [created] : [comment], { page, totalElements: 41, totalPages: 3, first: page === 0, last: page === 2 }));
        });
        await submitComment();
        expect(await comments().findByText('Page 3 of 3 · 41 comments')).toBeVisible();
        expect(comments().getByRole('list')).toHaveTextContent('Server comment body');
        expect(commentsMock.mock.calls.at(-1)?.[0]).toBe('/api/tickets/42/comments?page=2&size=20');
        expect(createMock).toHaveBeenCalledTimes(1);
    });

    it('retains confirmed returned data if the comments reload fails', async () => {
        renderDetail();
        await loaded();
        await comments().findByText(comment.body);
        commentsMock.mockImplementation(async () => problem(500));
        await submitComment();
        expect(await comments().findByRole('alert')).toHaveTextContent('We could not load comments');
        expect(comments().getByText('Server comment body')).toBeVisible();
        expect(comments().getByRole('status')).toHaveTextContent('Comment added.');
        expect(comments().getByLabelText('New comment')).toHaveValue('');
        expect(createMock).toHaveBeenCalledTimes(1);
    });

    it.each(['   \n\t', 'x'.repeat(4001)])('rejects blank/oversized input case %# before mutation', async (body) => {
        renderDetail();
        await loaded();
        fireEvent.change(comments().getByLabelText('New comment'), { target: { value: body } });
        fireEvent.submit(comments().getByRole('form', { name: 'Add comment' }));
        expect(comments().getByRole('alert')).toHaveTextContent('at most 4,000 characters');
        expect(createMock).not.toHaveBeenCalled();
    });

    it('accepts the backend maximum length and retains the draft with safe backend 400 feedback', async () => {
        createMock.mockImplementation(async () => problem(400));
        renderDetail();
        await loaded();
        const body = 'x'.repeat(4000);
        await submitComment(body);
        expect(await comments().findByRole('alert')).toHaveTextContent('We could not add the comment');
        expect(comments().getByLabelText('New comment')).toHaveValue(body);
        expect(comments().getByLabelText('New comment')).toHaveAttribute('aria-invalid', 'true');
        expect(comments().queryByText(/Internal/)).not.toBeInTheDocument();
        expect(createMock).toHaveBeenCalledTimes(1);
    });

    it('recovers from generic 403 with fresh CSRF only on the next explicit submission', async () => {
        createMock.mockImplementationOnce(async () => problem(403)).mockImplementation(async () => Response.json(comment, { status: 201 }));
        renderDetail();
        await loaded();
        await submitComment();
        expect(await comments().findByRole('alert')).toHaveTextContent('Commenting was not permitted or your session changed');
        expect(comments().getByLabelText('New comment')).toHaveValue('New comment body');
        expect(createMock).toHaveBeenCalledTimes(1);
        expect(csrfMock).toHaveBeenCalledTimes(1);
        await userEvent.click(comments().getByRole('button', { name: 'Add comment' }));
        await comments().findByText('Comment added.');
        expect(csrfMock).toHaveBeenCalledTimes(2);
        expect(createMock).toHaveBeenCalledTimes(2);
        expect(new Headers(createMock.mock.calls[0]?.[0]?.headers).get(csrfA.headerName)).toBe(csrfA.token);
        expect(new Headers(createMock.mock.calls[1]?.[0]?.headers).get(csrfB.headerName)).toBe(csrfB.token);
    });

    it.each(['offline', 'server', 'malformed', 'invalid-json'] as const)('requires explicit review after uncertain %s outcome without replay or draft loss', async (failure) => {
        createMock.mockImplementation(async () => {
            if (failure === 'offline') throw new TypeError('Internal stack trace');
            if (failure === 'malformed') return Response.json({ ...comment, author: null }, { status: 201 });
            if (failure === 'invalid-json') return new Response('Internal JSON diagnostic', { status: 201 });
            return problem(500);
        });
        renderDetail();
        await loaded();
        await submitComment();
        expect(await comments().findByRole('alert')).toHaveTextContent('We could not confirm whether the comment was added');
        expect(comments().getByRole('button', { name: 'Add comment' })).toBeDisabled();
        expect(comments().getByLabelText('New comment')).toHaveValue('New comment body');
        expect(createMock).toHaveBeenCalledTimes(1);
        commentsMock.mockImplementation(async () => Response.json(commentPage([{ ...comment, body: 'New comment body' }])));
        await userEvent.click(comments().getByRole('button', { name: 'Refresh comments' }));
        expect(await comments().findByText('New comment body', { selector: 'p' })).toBeVisible();
        expect(comments().getByRole('alert')).toHaveTextContent('Check whether your comment was added');
        expect(comments().getByRole('button', { name: 'Add comment' })).toBeEnabled();
        expect(createMock).toHaveBeenCalledTimes(1);
        expect(comments().queryByText(/Internal/)).not.toBeInTheDocument();
    });

    it('keeps uncertain submission blocked until a failed refresh is successfully retried', async () => {
        createMock.mockImplementation(async () => problem(500));
        renderDetail();
        await loaded();
        await submitComment();
        await comments().findByRole('alert');
        commentsMock.mockImplementationOnce(async () => problem(500));
        await userEvent.click(comments().getByRole('button', { name: 'Refresh comments' }));
        await comments().findByText('We could not load comments. Please try again.');
        expect(comments().getByRole('button', { name: 'Add comment' })).toBeDisabled();
        await userEvent.click(comments().getByRole('button', { name: 'Retry comments' }));
        await comments().findByText(comment.body);
        expect(comments().getByRole('button', { name: 'Add comment' })).toBeEnabled();
        expect(createMock).toHaveBeenCalledTimes(1);
    });
});

describe('Activity failure isolation and workflow integration', () => {
    it('refreshes history after claim/status success while preserving the comment draft', async () => {
        role = 'AGENT';
        historyMock.mockImplementation(async () => Response.json(historyPage(historyEntries.slice(2))));
        renderDetail();
        await loaded();
        await history().findByText('Ticket created with status Open.');
        fireEvent.change(comments().getByLabelText('New comment'), { target: { value: 'Keep this draft' } });
        historyMock.mockImplementation(async () => Response.json(historyPage(historyEntries.slice(1))));
        await userEvent.click(screen.getByRole('button', { name: 'Claim ticket' }));
        expect(await history().findByText('Ticket claimed by Alice Agent.')).toBeVisible();
        workflowMock.mockImplementation(async () => Response.json({ ...ticket, status: 'IN_PROGRESS' }));
        historyMock.mockImplementation(async () => Response.json(historyPage()));
        await userEvent.click(screen.getByRole('button', { name: 'Set status to In progress' }));
        expect(await history().findByText('Status changed from Open to In progress.')).toBeVisible();
        expect(comments().getByLabelText('New comment')).toHaveValue('Keep this draft');
        expect(commentsMock).toHaveBeenCalledTimes(1);
        expect(historyMock).toHaveBeenCalledTimes(3);
        await userEvent.click(history().getByRole('button', { name: 'Refresh history' }));
        await history().findByRole('list');
        expect(historyMock).toHaveBeenCalledTimes(4);
    });

    it.each(['comments', 'history'] as const)('handles %s load errors safely and retries independently', async (kind) => {
        const mock = kind === 'comments' ? commentsMock : historyMock;
        const section = kind === 'comments' ? comments : history;
        mock.mockImplementation(async () => problem(500));
        renderDetail();
        await loaded();
        expect(await section().findByRole('alert')).toHaveTextContent(`We could not load ${kind}`);
        expect(screen.getByRole('heading', { name: ticket.title })).toBeVisible();
        expect(section().queryByText(/Internal/)).not.toBeInTheDocument();
        mock.mockImplementation(async () => Response.json(kind === 'comments' ? commentPage() : historyPage()));
        await userEvent.click(section().getByRole('button', { name: `Retry ${kind}` }));
        expect(await section().findByRole('list')).toBeVisible();
        expect(mock).toHaveBeenCalledTimes(2);
        expect((kind === 'comments' ? historyMock : commentsMock)).toHaveBeenCalledTimes(1);
    });

    it.each(['comments', 'history'] as const)('handles malformed/offline/403 %s responses safely', async (kind) => {
        const mock = kind === 'comments' ? commentsMock : historyMock;
        const section = kind === 'comments' ? comments : history;
        mock.mockImplementationOnce(async () => Response.json({ content: [{}] }))
            .mockImplementationOnce(async () => { throw new TypeError('Internal stack trace'); })
            .mockImplementationOnce(async () => problem(403));
        renderDetail();
        await loaded();
        await section().findByRole('alert');
        await userEvent.click(section().getByRole('button', { name: `Retry ${kind}` }));
        await section().findByRole('alert');
        await userEvent.click(section().getByRole('button', { name: `Retry ${kind}` }));
        expect(await section().findByRole('alert')).toHaveTextContent('access was not permitted');
        expect(screen.getByRole('heading', { name: ticket.title })).toBeVisible();
        expect(section().queryByText(/Internal/)).not.toBeInTheDocument();
    });

    it.each(['comments', 'history', 'create'] as const)('expires the session on active %s 401', async (operation) => {
        const mock = operation === 'comments' ? commentsMock : operation === 'history' ? historyMock : createMock;
        mock.mockImplementation(async () => problem(401));
        renderDetail();
        if (operation === 'create') { await loaded(); await submitComment(); }
        expect(await screen.findByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
        expect(screen.getByRole('alert')).toHaveTextContent('Your session expired');
        expect(screen.queryByRole('region', { name: 'Ticket details' })).not.toBeInTheDocument();
    });

    it.each(['comments', 'history', 'create'] as const)('uses indistinguishable not-found behavior for %s 404', async (operation) => {
        const mock = operation === 'comments' ? commentsMock : operation === 'history' ? historyMock : createMock;
        mock.mockImplementation(async () => problem(404));
        renderDetail();
        if (operation === 'create') { await loaded(); await submitComment(); }
        expect(await screen.findByRole('heading', { name: 'Ticket not found' })).toBeVisible();
        expect(screen.getByRole('alert')).toHaveTextContent('This ticket could not be found.');
        expect(screen.queryByRole('heading', { name: ticket.title })).not.toBeInTheDocument();
        expect(screen.queryByRole('region', { name: 'Comments' })).not.toBeInTheDocument();
    });

    it.each(['comments', 'history'] as const)('ignores abandoned %s read successes and 401 responses', async (kind) => {
        for (const status of [200, 401]) {
            let resolve!: (value: Response) => void;
            const mock = kind === 'comments' ? commentsMock : historyMock;
            mock.mockImplementation((path) => path.includes('/42/') ? new Promise((done) => { resolve = done; })
                : Promise.resolve(Response.json(kind === 'comments' ? commentPage([]) : historyPage([]))));
            const view = renderDetail();
            await loaded();
            const signal = mock.mock.calls.at(-1)?.[1]?.signal;
            await userEvent.click(screen.getByRole('button', { name: 'Open another ticket' }));
            await screen.findByRole('heading', { name: 'Another ticket' });
            expect(signal?.aborted).toBe(true);
            await act(async () => resolve(status === 401 ? problem(401) : Response.json(kind === 'comments' ? commentPage() : historyPage())));
            expect(screen.getByRole('heading', { name: 'Another ticket' })).toBeVisible();
            expect((kind === 'comments' ? comments() : history()).queryByRole('list')).not.toBeInTheDocument();
            view.unmount();
        }
    });

    it.each([201, 401])('ignores abandoned comment mutation HTTP %s', async (status) => {
        let resolve!: (value: Response) => void;
        createMock.mockImplementation(() => new Promise((done) => { resolve = done; }));
        renderDetail();
        await loaded();
        await submitComment();
        const signal = createMock.mock.calls[0]?.[0]?.signal;
        await userEvent.click(screen.getByRole('button', { name: 'Open another ticket' }));
        await screen.findByRole('heading', { name: 'Another ticket' });
        expect(signal?.aborted).toBe(true);
        await act(async () => resolve(status === 201 ? Response.json(comment, { status }) : problem(status)));
        expect(screen.getByRole('heading', { name: 'Another ticket' })).toBeVisible();
        expect(comments().queryByText('Comment added.')).not.toBeInTheDocument();
        expect(comments().getByLabelText('New comment')).toHaveValue('');
    });
});
