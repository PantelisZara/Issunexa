import { expect, type Page } from '@playwright/test';

export type Csrf = { token: string; headerName: string };
export async function demoRequest(page: Page, path: string, method = 'GET', csrf?: Csrf, body?: unknown) {
    return page.evaluate(async ({ path, method, csrf, body }) => {
        const response = await fetch(path, { method, credentials: 'include', cache: 'no-store',
            headers: { ...(csrf ? { [csrf.headerName]: csrf.token } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
            ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
        const text = await response.text();
        return { status: response.status, body: text ? JSON.parse(text) as unknown : undefined };
    }, { path, method, csrf, body });
}
export async function csrfFor(page: Page): Promise<Csrf> {
    const response = await demoRequest(page, '/api/auth/csrf');
    expect(response.status).toBe(200);
    const value = response.body as Csrf;
    expect(value.token).toBeTruthy();
    expect(value.headerName).toBeTruthy();
    return value;
}
export async function startDemo(page: Page) {
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: 'Explore the synthetic demo' })).toBeVisible();
    await expect(page.getByLabel('Password', { exact: true })).toHaveCount(0);
    await page.getByRole('button', { name: 'Start synthetic demo', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Tickets', exact: true })).toBeVisible();
    await expect(page.getByText('Role: REQUESTER', { exact: true })).toBeVisible();
}
export async function selectDemoRole(page: Page, role: 'requester' | 'agent') {
    await page.getByRole('button', { name: `Switch to ${role}`, exact: true }).click();
    await expect(page.getByText(`Role: ${role.toUpperCase()}`, { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Tickets', exact: true })).toBeVisible();
}
export async function endDemo(page: Page) {
    await page.getByRole('button', { name: 'End demo', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Explore the synthetic demo' })).toBeVisible();
    expect((await demoRequest(page, '/api/auth/session')).status).toBe(401);
    expect((await demoRequest(page, '/api/demo')).body).toMatchObject({ enabled: true, active: false });
}
