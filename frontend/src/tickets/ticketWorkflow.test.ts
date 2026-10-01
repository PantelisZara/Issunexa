import { expect, it } from 'vitest';
import { ticketTransitions } from './ticketWorkflow';

it.each([
    ['OPEN', ['IN_PROGRESS']],
    ['IN_PROGRESS', ['RESOLVED']],
    ['RESOLVED', ['IN_PROGRESS', 'CLOSED']],
    ['CLOSED', []],
] as const)('mirrors backend transitions from %s exactly', (status, allowed) => {
    expect(ticketTransitions[status]).toEqual(allowed);
});
