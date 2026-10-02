import { apiRequest } from '../api/apiRequest';
import type { CsrfMetadata } from '../auth/authTypes';
import { decodeTicket, decodeTicketComment, decodeTicketCommentPage, decodeTicketHistoryPage, decodeTicketPage } from './ticketDecoders';
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

export async function getTicketComments(id: string, page = 0, size = 20, signal?: AbortSignal) {
    return decodeTicketCommentPage(await apiRequest(`/api/tickets/${encodeURIComponent(id)}/comments?${new URLSearchParams({ page: String(page), size: String(size) })}`, {
        cache: 'no-store', ...(signal ? { signal } : {}),
    }));
}

export async function createTicketComment(id: string, body: string, csrf: CsrfMetadata, signal?: AbortSignal) {
    return decodeTicketComment(await apiRequest(`/api/tickets/${encodeURIComponent(id)}/comments`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ body }),
        ...(signal ? { signal } : {}),
    }));
}

export async function getTicketHistory(id: string, page = 0, size = 20, signal?: AbortSignal) {
    return decodeTicketHistoryPage(await apiRequest(`/api/tickets/${encodeURIComponent(id)}/history?${new URLSearchParams({ page: String(page), size: String(size) })}`, {
        cache: 'no-store', ...(signal ? { signal } : {}),
    }));
}
