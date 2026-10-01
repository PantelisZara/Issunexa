import type { Ticket, TicketPage } from '../tickets/ticketTypes';

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
