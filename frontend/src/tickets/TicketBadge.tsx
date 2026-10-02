import { ticketLabels, type Ticket } from './ticketTypes';

type Props =
    | { kind: 'status'; value: Ticket['status'] }
    | { kind: 'priority'; value: Ticket['priority'] }
    | { kind: 'category'; value: Ticket['category'] };

export function TicketBadge({ kind, value }: Props) {
    return <span className={`ticket-badge ticket-badge-${kind}`} data-value={value}>{ticketLabels[value]}</span>;
}
