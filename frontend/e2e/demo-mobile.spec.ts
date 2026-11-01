import { expect, test } from '@playwright/test';
import { addComment, createTicket, uniqueTitle } from './helpers/journeys';
import { endDemo, selectDemoRole, startDemo } from './helpers/demo';

test('mobile synthetic demo controls and ticket workflow remain usable without overflow', async ({ page }) => {
    await startDemo(page);
    const controls = page.getByRole('region', { name: 'Synthetic demo', exact: true });
    await expect(controls.getByRole('button', { name: 'Switch to agent' })).toBeInViewport();
    await expect(page.getByRole('button', { name: 'End demo' })).toBeInViewport();
    const ticket = await createTicket(page, uniqueTitle('synthetic mobile'));
    await addComment(page, 'Synthetic mobile walkthrough comment.');
    await selectDemoRole(page, 'agent');
    await page.getByRole('link', { name: ticket.title, exact: true }).click();
    await page.getByRole('button', { name: 'Claim ticket', exact: true }).click();
    await expect(page.getByText('Ticket claimed.', { exact: true })).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    await endDemo(page);
});
