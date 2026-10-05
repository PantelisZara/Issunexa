import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test('existing and unknown identifiers receive the same throttling contract through Nginx', async ({ page }) => {
    let loginRequests = 0;
    page.on('request', (request) => {
        if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/auth/login') loginRequests++;
    });
    const throttledBodies: unknown[] = [];
    for (const email of ['throttle@e2e.invalid', `missing-${randomUUID()}@e2e.invalid`]) {
        await page.goto('/login');
        await expect(page.getByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
        await page.getByLabel('Email', { exact: true }).fill(email);
        for (let attempt = 0; attempt < 5; attempt++) {
            await page.getByLabel('Password', { exact: true }).fill('wrong E2E password');
            const response = page.waitForResponse((response) => response.request().method() === 'POST'
                && new URL(response.url()).pathname === '/api/auth/login');
            await page.getByRole('button', { name: 'Sign in', exact: true }).click();
            expect((await response).status()).toBe(401);
            await expect(page.getByRole('alert')).toHaveText('Invalid email or password.');
            await expect(page.getByLabel('Password', { exact: true })).toHaveValue('');
        }
        // Even valid credentials cannot bypass an exhausted account budget.
        await page.getByLabel('Password', { exact: true }).fill('E2E-only-Issunexa-032!');
        const response = page.waitForResponse((response) => response.request().method() === 'POST'
            && new URL(response.url()).pathname === '/api/auth/login');
        await page.getByRole('button', { name: 'Sign in', exact: true }).click();
        const result = await response;
        expect(result.status()).toBe(429);
        expect(result.headers()['content-type']).toContain('application/problem+json');
        expect(result.headers()['cache-control']).toContain('no-store');
        const delay = result.headers()['retry-after'];
        expect(delay).toMatch(/^[1-9][0-9]*$/);
        expect(Number(delay)).toBeLessThanOrEqual(300);
        throttledBodies.push(await result.json());
        await expect(page.getByRole('alert')).toHaveText(
            `Too many sign-in attempts. Please wait ${delay} seconds before trying again.`);
        await expect(page.getByLabel('Password', { exact: true })).toHaveValue('');
        await expect(page).toHaveURL(/\/login$/);
        await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeEnabled();
    }
    expect(throttledBodies[0]).toEqual(throttledBodies[1]);
    expect(loginRequests).toBe(12);
});

test('source throttling counts real peers through Nginx and ignores spoofed direct-backend headers', async ({ request, page }) => {
    // Runs after the normal journeys. Source budget persists across browser sessions and account changes.
    for (const origin of ['', 'http://backend:8080']) {
        const csrfResponse = await request.get(`${origin}/api/auth/csrf`);
        expect(csrfResponse.status()).toBe(200);
        const csrf = await csrfResponse.json() as { headerName: string; token: string };
        for (let attempt = 0; attempt < 35; attempt++) {
            // Nginx proxies /api/*; parameters on the first segment are only routable directly to Spring.
            const path = attempt % 3 === 0 ? (origin ? `/api;attempt=${attempt}/auth/login`
                : `/api/auth/login;attempt=${attempt};mode=test`)
                : attempt % 3 === 1 ? `/api/auth;attempt=${attempt}/login` : `/api/auth/login;attempt=${attempt}`;
            const rejected = await request.post(`${origin}${path}`, {
                headers: { [csrf.headerName]: csrf.token },
                data: { email: 'requester-a@e2e.invalid', password: 'E2E-only-Issunexa-032!' },
            });
            // Production StrictHttpFirewall rejects before CSRF/authentication, on both actual HTTP routes.
            expect(rejected.status()).toBe(400);
        }
        expect((await request.get(`${origin}/api/auth/session`)).status()).toBe(401);
    }
    let throttled = false;
    for (let attempt = 0; attempt <= 30; attempt++) {
        const result = await request.post('/api/auth/login', {
            headers: { 'X-Real-IP': `203.0.113.${attempt}`, 'X-Forwarded-For': `203.0.113.${attempt}`,
                Forwarded: `for=203.0.113.${attempt}` },
        });
        if (result.status() === 429) {
            throttled = true;
            expect(result.headers()['content-type']).toContain('application/problem+json');
            expect(result.headers()['cache-control']).toContain('no-store');
            const delay = Number(result.headers()['retry-after']);
            expect(delay).toBeGreaterThanOrEqual(1);
            expect(delay).toBeLessThanOrEqual(60);
            expect(await result.json()).toEqual({ type: 'about:blank', title: 'Too many requests', status: 429,
                detail: 'Too many sign-in attempts. Please try again later.', instance: '/api/auth/login' });
            break;
        }
        expect(result.status()).toBe(403);
    }
    expect(throttled).toBe(true);
    // Nginx forwards the runner's actual IP. The same runner's direct TCP connection must share that budget.
    const direct = await request.post('http://backend:8080/api/auth/login', {
        headers: { 'X-Real-IP': '198.51.100.1', 'X-Forwarded-For': '198.51.100.2', Forwarded: 'for=198.51.100.3' },
    });
    expect(direct.status()).toBe(429);
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: 'Sign in to Issunexa' })).toBeVisible();
    await page.getByLabel('Email', { exact: true }).fill('requester-a@e2e.invalid');
    await page.getByLabel('Password', { exact: true }).fill('E2E-only-Issunexa-032!');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('alert')).toHaveText(/Too many sign-in attempts\. Please wait [0-9]+ seconds before trying again\./);
    await expect(page.getByLabel('Password', { exact: true })).toHaveValue('');
    await expect(page).toHaveURL(/\/login$/);
});
