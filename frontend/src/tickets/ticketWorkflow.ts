import type { Ticket } from './ticketTypes';

// Mirror Ticket.changeStatus for UX only; the backend still validates every mutation.
export const ticketTransitions: Readonly<Record<Ticket['status'], readonly Ticket['status'][]>> = {
    OPEN: ['IN_PROGRESS'],
    IN_PROGRESS: ['RESOLVED'],
    RESOLVED: ['IN_PROGRESS', 'CLOSED'],
    CLOSED: [],
};
