import { apiRequest } from '../api/apiRequest';
import type { CsrfMetadata } from '../auth/authTypes';
import { decodeTicket, decodeTicketPage } from './ticketDecoders';
import { serializeTicketQuery } from './ticketQuery';
import type { CreateTicketInput, Ticket, TicketPage, TicketQuery } from './ticketTypes';

export async function getTickets(query: TicketQuery, signal?: AbortSignal): Promise<TicketPage> {
    return decodeTicketPage(await apiRequest(`/api/tickets?${serializeTicketQuery(query)}`, {
        cache: 'no-store', ...(signal ? { signal } : {}),
    }));
}

export async function getTicket(id: string, signal?: AbortSignal): Promise<Ticket> {
    return decodeTicket(await apiRequest(`/api/tickets/${encodeURIComponent(id)}`, {
        cache: 'no-store', ...(signal ? { signal } : {}),
    }));
}

export async function createTicket(input: CreateTicketInput, csrf: CsrfMetadata, signal?: AbortSignal): Promise<Ticket> {
    return decodeTicket(await apiRequest('/api/tickets', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({
            title: input.title, description: input.description, priority: input.priority, category: input.category,
        }),
        ...(signal ? { signal } : {}),
    }));
}

export async function claimTicket(id: string, csrf: CsrfMetadata, signal?: AbortSignal): Promise<Ticket> {
    return decodeTicket(await apiRequest(`/api/tickets/${encodeURIComponent(id)}/claim`, {
        method: 'POST', headers: { [csrf.headerName]: csrf.token },
        ...(signal ? { signal } : {}),
    }));
}

export async function changeTicketStatus(id: string, status: Ticket['status'], csrf: CsrfMetadata, signal?: AbortSignal): Promise<Ticket> {
    return decodeTicket(await apiRequest(`/api/tickets/${encodeURIComponent(id)}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ status }),
        ...(signal ? { signal } : {}),
    }));
}
