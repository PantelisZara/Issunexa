import { isSupported, ticketCategories, ticketPriorities, ticketStatuses, type Ticket, type TicketPage } from './ticketTypes';

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
    if (!record(value) || !integer(value.id, 1) || typeof value.displayName !== 'string') {
        throw new TypeError('Invalid Ticket assignee response.');
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

export function decodeTicketPage(value: unknown): TicketPage {
    if (!record(value) || !Array.isArray(value.content) || !integer(value.page, 0)
        || !integer(value.size, 1) || value.size > 100 || !integer(value.totalElements, 0)
        || !integer(value.totalPages, 0) || typeof value.first !== 'boolean' || typeof value.last !== 'boolean'
        || value.content.length > value.size) {
        throw new TypeError('Invalid Ticket page response.');
    }
    return {
        content: value.content.map(decodeTicket), page: value.page, size: value.size,
        totalElements: value.totalElements, totalPages: value.totalPages, first: value.first, last: value.last,
    };
}
