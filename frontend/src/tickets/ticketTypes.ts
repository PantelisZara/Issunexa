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

export type CreateTicketInput = Pick<Ticket, 'title' | 'description' | 'priority' | 'category'>;

export const ticketLabels: Record<Ticket['status'] | Ticket['priority'] | Ticket['category'], string> = {
    OPEN: 'Open', IN_PROGRESS: 'In progress', RESOLVED: 'Resolved', CLOSED: 'Closed',
    LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', URGENT: 'Urgent',
    INCIDENT: 'Incident', SERVICE_REQUEST: 'Service request', ACCESS_REQUEST: 'Access request', OTHER: 'Other',
};

export interface TicketPageData<T> {
    content: T[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    first: boolean;
    last: boolean;
}

export type TicketPage = TicketPageData<Ticket>;

export interface TicketComment {
    id: number;
    body: string;
    author: NonNullable<Ticket['assignee']>;
    createdAt: string;
}

export type TicketHistoryEntry = {
    id: number;
    actor: NonNullable<Ticket['assignee']>;
    createdAt: string;
} & (
    | { type: 'TICKET_CREATED'; previousStatus: null; newStatus: 'OPEN'; assignee: null }
    | { type: 'STATUS_CHANGED'; previousStatus: Ticket['status']; newStatus: Ticket['status']; assignee: null }
    | { type: 'ASSIGNEE_CLAIMED'; previousStatus: null; newStatus: null; assignee: NonNullable<Ticket['assignee']> }
);

export type TicketCommentPage = TicketPageData<TicketComment>;
export type TicketHistoryPage = TicketPageData<TicketHistoryEntry>;

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
