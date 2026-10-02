import { isSupported, ticketCategories, ticketPriorities, ticketStatuses,
    type Ticket, type TicketComment, type TicketHistoryEntry, type TicketPageData } from './ticketTypes';

function record(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function integer(value: unknown, min: number): value is number {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= min;
}

function timestamp(value: unknown): value is string {
    return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
        && Number.isFinite(Date.parse(value));
}

function decodeAssignee(value: unknown): Ticket['assignee'] {
    if (value === null) return null;
    return decodeAccount(value);
}

function decodeAccount(value: unknown): NonNullable<Ticket['assignee']> {
    if (!record(value) || !integer(value.id, 1) || typeof value.displayName !== 'string') {
        throw new TypeError('Invalid Ticket account summary response.');
    }
    return { id: value.id, displayName: value.displayName };
}

export function decodeTicket(value: unknown): Ticket {
    if (!record(value) || !integer(value.id, 1) || typeof value.title !== 'string'
        || typeof value.description !== 'string' || !isSupported(ticketStatuses, value.status)
        || !isSupported(ticketPriorities, value.priority) || !isSupported(ticketCategories, value.category)
        || !timestamp(value.createdAt) || !timestamp(value.updatedAt)) {
        throw new TypeError('Invalid Ticket response.');
    }
    return {
        id: value.id, title: value.title, description: value.description,
        status: value.status, priority: value.priority, category: value.category,
        createdAt: value.createdAt, updatedAt: value.updatedAt,
        assignee: decodeAssignee(value.assignee),
    };
}

function decodePage<T>(value: unknown, decodeItem: (item: unknown) => T): TicketPageData<T> {
    if (!record(value) || !Array.isArray(value.content) || !integer(value.page, 0)
        || !integer(value.size, 1) || value.size > 100 || !integer(value.totalElements, 0)
        || !integer(value.totalPages, 0) || typeof value.first !== 'boolean' || typeof value.last !== 'boolean'
        || value.content.length > value.size) {
        throw new TypeError('Invalid Ticket page response.');
    }
    return {
        content: value.content.map(decodeItem), page: value.page, size: value.size,
        totalElements: value.totalElements, totalPages: value.totalPages, first: value.first, last: value.last,
    };
}

export function decodeTicketPage(value: unknown) {
    return decodePage(value, decodeTicket);
}

export function decodeTicketComment(value: unknown): TicketComment {
    if (!record(value) || !integer(value.id, 1) || typeof value.body !== 'string'
        || value.body.length === 0 || value.body.length > 4000 || !timestamp(value.createdAt)) {
        throw new TypeError('Invalid Ticket comment response.');
    }
    return { id: value.id, body: value.body, author: decodeAccount(value.author), createdAt: value.createdAt };
}

export function decodeTicketHistoryEntry(value: unknown): TicketHistoryEntry {
    if (!record(value) || !integer(value.id, 1) || !timestamp(value.createdAt)) {
        throw new TypeError('Invalid Ticket history response.');
    }
    const base = { id: value.id, actor: decodeAccount(value.actor), createdAt: value.createdAt };
    if (value.type === 'TICKET_CREATED' && value.previousStatus === null && value.newStatus === 'OPEN' && value.assignee === null) {
        return { ...base, type: value.type, previousStatus: null, newStatus: 'OPEN', assignee: null };
    }
    if (value.type === 'STATUS_CHANGED' && isSupported(ticketStatuses, value.previousStatus)
        && isSupported(ticketStatuses, value.newStatus) && value.previousStatus !== value.newStatus && value.assignee === null) {
        return { ...base, type: value.type, previousStatus: value.previousStatus, newStatus: value.newStatus, assignee: null };
    }
    if (value.type === 'ASSIGNEE_CLAIMED' && value.previousStatus === null && value.newStatus === null) {
        return { ...base, type: value.type, previousStatus: null, newStatus: null, assignee: decodeAccount(value.assignee) };
    }
    throw new TypeError('Invalid Ticket history event payload.');
}

export function decodeTicketCommentPage(value: unknown) {
    return decodePage(value, decodeTicketComment);
}

export function decodeTicketHistoryPage(value: unknown) {
    return decodePage(value, decodeTicketHistoryEntry);
}
