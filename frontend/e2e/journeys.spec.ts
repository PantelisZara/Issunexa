import { expect, test } from '@playwright/test';
import { accounts, addComment, createTicket, login, logout, uniqueTitle } from './helpers/journeys';

test.beforeAll(async ({ browser }) => {
    console.log(`Desktop Chromium ${browser.version()}`);
});

test('authentication redirects, restores the session on reload, and logs out', async ({ page }) => {
    await login(page, accounts.requesterA);
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Tickets', exact: true })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Current account' })).toContainText(accounts.requesterA.displayName);
    await logout(page);
    await page.goto('/app/tickets');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
});

test('requester creates a ticket, comments, reads history, and returns to the list', async ({ page }) => {
    await login(page, accounts.requesterA);
    const ticket = await createTicket(page, uniqueTitle('requester'));
    const detail = page.getByRole('region', { name: 'Ticket details', exact: true });
    await expect(detail.getByText(ticket.description, { exact: true })).toBeVisible();
    await expect(detail.getByRole('definition').filter({ hasText: /^Open$/ })).toBeVisible();
    await expect(detail.getByRole('definition').filter({ hasText: /^High$/ })).toBeVisible();
    await expect(detail.getByRole('definition').filter({ hasText: /^Incident$/ })).toBeVisible();
    await expect(detail.getByRole('region', { name: 'Agent workflow' })).toHaveCount(0);
    await expect(detail.getByRole('button', { name: 'Claim ticket' })).toHaveCount(0);
    await addComment(page, `Requester comment for ${ticket.title}`);
    await expect(page.getByRole('list', { name: 'History events' })
        .getByText('Ticket created with status Open.', { exact: true })).toBeVisible();
    await detail.getByRole('link', { name: 'Back to tickets' }).click();
    await expect(page.getByRole('region', { name: 'Ticket list' })
        .getByRole('link', { name: ticket.title, exact: true })).toBeVisible();
    await logout(page);
});

test('requester cannot list or open another requester’s ticket', async ({ page }) => {
    await login(page, accounts.requesterA);
    const ticket = await createTicket(page, uniqueTitle('private'));
    await logout(page);
    await login(page, accounts.requesterB);
    await expect(page.getByRole('heading', { name: 'No tickets found' })).toBeVisible();
    await expect(page.getByRole('link', { name: ticket.title, exact: true })).toHaveCount(0);
    await page.goto(ticket.url);
    await expect(page.getByRole('heading', { name: 'Ticket not found', exact: true })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveText('This ticket could not be found.');
    await expect(page.getByRole('heading', { name: ticket.title, exact: true })).toHaveCount(0);
    await logout(page);
});

test('agent claims, changes status, comments, and reloads persisted state', async ({ page }) => {
    await login(page, accounts.agent);
    const ticket = await createTicket(page, uniqueTitle('agent'));
    const detail = page.getByRole('region', { name: 'Ticket details', exact: true });
    await detail.getByRole('button', { name: 'Claim ticket', exact: true }).click();
    await expect(detail.getByRole('definition').filter({ hasText: /^E2E Agent$/ })).toBeVisible();
    await detail.getByRole('button', { name: 'Set status to In progress', exact: true }).click();
    await expect(detail.getByRole('definition').filter({ hasText: /^In progress$/ })).toBeVisible();
    const comment = `Agent comment for ${ticket.title}`;
    await addComment(page, comment);
    const history = page.getByRole('list', { name: 'History events' });
    await expect(history.getByText('Ticket claimed by E2E Agent.', { exact: true })).toBeVisible();
    await expect(history.getByText('Status changed from Open to In progress.', { exact: true })).toBeVisible();
    await page.reload();
    await expect(detail.getByRole('heading', { name: ticket.title, exact: true })).toBeVisible();
    await expect(detail.getByRole('definition').filter({ hasText: /^E2E Agent$/ })).toBeVisible();
    await expect(detail.getByRole('definition').filter({ hasText: /^In progress$/ })).toBeVisible();
    await expect(page.getByRole('list', { name: 'Ticket comments' }).getByText(comment, { exact: true })).toBeVisible();
    await expect(history.getByText('Status changed from Open to In progress.', { exact: true })).toBeVisible();
    await logout(page);
});

test('admin accesses a ticket and performs a staff workflow action', async ({ page }) => {
    await login(page, accounts.requesterA);
    const ticket = await createTicket(page, uniqueTitle('admin'));
    await logout(page);
    await login(page, accounts.admin);
    await page.getByRole('region', { name: 'Ticket list' }).getByRole('link', { name: ticket.title, exact: true }).click();
    const workflow = page.getByRole('region', { name: 'Agent workflow' });
    await expect(workflow.getByRole('button', { name: 'Set status to In progress', exact: true })).toBeEnabled();
    await workflow.getByRole('button', { name: 'Claim ticket', exact: true }).click();
    await expect(page.getByRole('region', { name: 'Ticket details', exact: true })
        .getByRole('definition').filter({ hasText: /^E2E Admin$/ })).toBeVisible();
    await logout(page);
});

test('server filtering and application return navigation preserve exact query state', async ({ page }) => {
    await login(page, accounts.requesterA);
    const group = uniqueTitle('query');
    const first = await createTicket(page, `${group} first`);
    const second = await createTicket(page, `${group} second`);
    const excluded = await createTicket(page, `${group} excluded`, { priority: 'LOW' });
    await page.getByRole('link', { name: 'Back to tickets' }).click();
    await page.getByRole('searchbox', { name: 'Search tickets' }).fill(group);
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    await page.getByLabel('Priority', { exact: true }).selectOption('HIGH');
    await expect(page).toHaveURL((url) => url.searchParams.get('q') === group
        && url.searchParams.get('priority') === 'HIGH' && url.searchParams.get('page') === '0');
    const list = page.getByRole('region', { name: 'Ticket list' });
    await expect(list.getByRole('link')).toHaveCount(2);
    await expect(list.getByRole('link', { name: first.title, exact: true })).toBeVisible();
    await expect(list.getByRole('link', { name: second.title, exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: excluded.title, exact: true })).toHaveCount(0);
    const listUrl = page.url();
    await list.getByRole('link', { name: first.title, exact: true }).click();
    await expect(page.getByRole('heading', { name: first.title, exact: true })).toBeVisible();
    await page.getByRole('link', { name: 'Back to tickets' }).click();
    await expect(page).toHaveURL(listUrl);
    await expect(page.getByRole('searchbox', { name: 'Search tickets' })).toHaveValue(group);
    await expect(page.getByLabel('Priority', { exact: true })).toHaveValue('HIGH');
    await expect(list.getByRole('link')).toHaveCount(2);
    await logout(page);
});
