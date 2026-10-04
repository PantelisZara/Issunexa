import { randomUUID } from 'node:crypto';
import { expect, type Page } from '@playwright/test';

// Public, synthetic fixtures used only in the disposable E2E database.
export const accounts = {
    requesterA: { email: 'requester-a@e2e.invalid', displayName: 'E2E Requester A' },
    requesterB: { email: 'requester-b@e2e.invalid', displayName: 'E2E Requester B' },
    agent: { email: 'agent@e2e.invalid', displayName: 'E2E Agent' },
    admin: { email: 'admin@e2e.invalid', displayName: 'E2E Admin' },
};

export function uniqueTitle(label: string) {
    return `E2E ${label} ${randomUUID()}`;
}

export async function login(page: Page, account: typeof accounts[keyof typeof accounts]) {
    await page.goto('/app/tickets');
    await expect(page).toHaveURL(/\/login$/);
    await page.getByLabel('Email', { exact: true }).fill(account.email);
    await page.getByLabel('Password', { exact: true }).fill('E2E-only-Issunexa-032!');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Tickets', exact: true })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Current account' })).toContainText(account.displayName);
}

export async function logout(page: Page) {
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
}

export async function createTicket(page: Page, title: string, options: { priority?: string; category?: string } = {}) {
    const description = `Full-stack description for ${title}`;
    await page.goto('/app/tickets');
    await page.getByRole('link', { name: 'Create ticket', exact: true }).click();
    const form = page.getByRole('form', { name: 'Create ticket', exact: true });
    await form.getByLabel('Title', { exact: true }).fill(title);
    await form.getByLabel('Description', { exact: true }).fill(description);
    await form.getByLabel('Priority', { exact: true }).selectOption(options.priority ?? 'HIGH');
    await form.getByLabel('Category', { exact: true }).selectOption(options.category ?? 'INCIDENT');
    await form.getByRole('button', { name: 'Create ticket', exact: true }).click();
    await expect(page).toHaveURL(/\/app\/tickets\/\d+$/);
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
    return { title, description, url: page.url() };
}

export async function addComment(page: Page, body: string) {
    await page.getByLabel('New comment', { exact: true }).fill(body);
    await page.getByRole('button', { name: 'Add comment', exact: true }).click();
    await expect(page.getByRole('list', { name: 'Ticket comments' }).getByText(body, { exact: true })).toBeVisible();
}
