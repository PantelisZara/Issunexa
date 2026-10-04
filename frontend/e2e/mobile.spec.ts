import { expect, test, type Page } from '@playwright/test';
import { accounts, addComment, createTicket, login, logout, uniqueTitle } from './helpers/journeys';

async function expectNoOverflow(page: Page) {
    await expect.poll(() => page.evaluate(() =>
        document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
}

test.beforeAll(async ({ browser }) => {
    console.log(`Mobile Chromium ${browser.version()}`);
});

test('mobile login, navigation, workspace, and ticket controls remain usable', async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeInViewport();
    await expectNoOverflow(page);
    await login(page, accounts.requesterA);
    const navigation = page.getByRole('navigation', { name: 'Primary navigation' });
    await expect(navigation.getByRole('link', { name: 'Tickets', exact: true })).toBeInViewport();
    await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeInViewport();
    await expect(page.getByRole('link', { name: 'Create ticket', exact: true })).toBeInViewport();
    await expectNoOverflow(page);
    const ticket = await createTicket(page, uniqueTitle('mobile'));
    await expect(page.getByRole('region', { name: 'Description' }).getByText(ticket.description, { exact: true })).toBeVisible();
    await expectNoOverflow(page);
    await addComment(page, `Mobile comment for ${ticket.title}`);
    await page.getByRole('link', { name: 'Back to tickets' }).click();
    await expect(page.getByRole('region', { name: 'Ticket list' }).getByRole('link', { name: ticket.title, exact: true })).toBeVisible();
    await navigation.getByRole('link', { name: 'Tickets', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Tickets', exact: true })).toBeVisible();
    await expectNoOverflow(page);
    await logout(page);
});
