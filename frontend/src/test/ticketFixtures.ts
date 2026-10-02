import type { Ticket, TicketComment, TicketCommentPage, TicketHistoryEntry, TicketHistoryPage, TicketPage } from '../tickets/ticketTypes';

export const ticket: Ticket = {
    id: 42, title: 'Printer unavailable', description: 'Office printer is unreachable.',
    status: 'OPEN', priority: 'HIGH', category: 'INCIDENT',
    createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T11:00:00Z',
    assignee: { id: 7, displayName: 'Alice Agent' },
};

export function ticketPage(overrides: Partial<TicketPage> = {}): TicketPage {
    return {
        content: [ticket, { ...ticket, id: 43, title: 'Request access', category: 'ACCESS_REQUEST', assignee: null }],
        page: 0, size: 20, totalElements: 2, totalPages: 1, first: true, last: true,
        ...overrides,
    };
}

export const comment: TicketComment = {
    id: 12, body: 'The printer is back online.', author: { id: 3, displayName: 'Riley Requester' }, createdAt: '2026-10-02T10:00:00Z',
};

export function commentPage(content: TicketComment[] = [comment], overrides: Partial<TicketCommentPage> = {}): TicketCommentPage {
    return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, first: true, last: true, ...overrides };
}

export const historyEntries: TicketHistoryEntry[] = [
    { id: 9, type: 'STATUS_CHANGED', actor: { id: 7, displayName: 'Alice Agent' }, previousStatus: 'OPEN', newStatus: 'IN_PROGRESS', assignee: null, createdAt: '2026-10-02T09:00:00Z' },
    { id: 8, type: 'ASSIGNEE_CLAIMED', actor: { id: 7, displayName: 'Alice Agent' }, previousStatus: null, newStatus: null, assignee: { id: 7, displayName: 'Alice Agent' }, createdAt: '2026-10-02T09:00:00Z' },
    { id: 7, type: 'TICKET_CREATED', actor: { id: 3, displayName: 'Riley Requester' }, previousStatus: null, newStatus: 'OPEN', assignee: null, createdAt: '2026-10-01T10:00:00Z' },
];

export function historyPage(content: TicketHistoryEntry[] = historyEntries, overrides: Partial<TicketHistoryPage> = {}): TicketHistoryPage {
    return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0, first: true, last: true, ...overrides };
}
