export const ticketStatuses = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'] as const;
export const ticketPriorities = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'] as const;
export const ticketCategories = ['INCIDENT', 'SERVICE_REQUEST', 'ACCESS_REQUEST', 'OTHER'] as const;
export const ticketSortFields = ['createdAt', 'updatedAt', 'title'] as const;
export const ticketSortDirections = ['asc', 'desc'] as const;

export interface Ticket {
    id: number;
    title: string;
    description: string;
    status: typeof ticketStatuses[number];
    priority: typeof ticketPriorities[number];
    category: typeof ticketCategories[number];
    createdAt: string;
    updatedAt: string;
    assignee: { id: number; displayName: string } | null;
}

export interface TicketPage {
    content: Ticket[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    first: boolean;
    last: boolean;
}

export interface TicketQuery {
    q: string;
    status: Ticket['status'] | '';
    priority: Ticket['priority'] | '';
    category: Ticket['category'] | '';
    sortBy: typeof ticketSortFields[number];
    direction: typeof ticketSortDirections[number];
    page: number;
    size: number;
}

export function isSupported<T extends string>(values: readonly T[], value: unknown): value is T {
    return typeof value === 'string' && values.some((item) => item === value);
}
