// Accept only the Ticket list route from navigation state, with its original query.
export function ticketListLocation(state: unknown): string {
    if (typeof state === 'object' && state !== null && 'ticketListUrl' in state
        && typeof state.ticketListUrl === 'string' && /^\/app\/tickets(?:\?[^#]*)?$/.test(state.ticketListUrl)) {
        return state.ticketListUrl;
    }
    return '/app/tickets';
}
