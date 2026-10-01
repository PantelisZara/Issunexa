import {
    isSupported, ticketCategories, ticketPriorities, ticketSortDirections, ticketSortFields, ticketStatuses,
    type TicketQuery,
} from './ticketTypes';

function integer(value: string | null, fallback: number, min: number, max: number) {
    if (value === null || !/^\d+$/.test(value)) return fallback;
    const number = Number(value);
    return Number.isSafeInteger(number) && number >= min && number <= max ? number : fallback;
}

// Invalid or repeated URL values fall back to supported defaults before reaching the API.
export function readTicketQuery(params: URLSearchParams): TicketQuery {
    const single = (name: string) => params.getAll(name).length === 1 ? params.get(name) : null;
    const q = single('q') ?? '';
    const status = single('status');
    const priority = single('priority');
    const category = single('category');
    const sortBy = single('sortBy');
    const direction = single('direction');
    return {
        q: q.length <= 100 ? q.trim() : '',
        status: isSupported(ticketStatuses, status) ? status : '',
        priority: isSupported(ticketPriorities, priority) ? priority : '',
        category: isSupported(ticketCategories, category) ? category : '',
        sortBy: isSupported(ticketSortFields, sortBy) ? sortBy : 'createdAt',
        direction: isSupported(ticketSortDirections, direction) ? direction : 'desc',
        page: integer(single('page'), 0, 0, 2147483647),
        size: integer(single('size'), 20, 1, 100),
    };
}

export function serializeTicketQuery(query: TicketQuery): URLSearchParams {
    const params = new URLSearchParams();
    if (query.q.trim()) params.set('q', query.q.trim());
    if (query.status) params.set('status', query.status);
    if (query.priority) params.set('priority', query.priority);
    if (query.category) params.set('category', query.category);
    params.set('sortBy', query.sortBy);
    params.set('direction', query.direction);
    params.set('page', String(query.page));
    params.set('size', String(query.size));
    return params;
}
