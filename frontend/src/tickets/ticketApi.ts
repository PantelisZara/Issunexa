import { apiRequest } from '../api/apiRequest';
import { decodeTicketPage } from './ticketDecoders';
import { serializeTicketQuery } from './ticketQuery';
import type { TicketPage, TicketQuery } from './ticketTypes';

export async function getTickets(query: TicketQuery, signal?: AbortSignal): Promise<TicketPage> {
    return decodeTicketPage(await apiRequest(`/api/tickets?${serializeTicketQuery(query)}`, {
        cache: 'no-store', ...(signal ? { signal } : {}),
    }));
}
