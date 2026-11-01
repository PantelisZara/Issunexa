import { expect, test } from '@playwright/test';
import { addComment, createTicket, uniqueTitle } from './helpers/journeys';
import { csrfFor, demoRequest, endDemo, selectDemoRole, startDemo } from './helpers/demo';

// Three admissions here plus one mobile admission stay below the real five/source/minute limit.
test('independent visitors complete their own workflows and cannot read or mutate another workspace', async ({ page, browser }) => {
    const otherContext = await browser.newContext();
    const other = await otherContext.newPage();
    try {
        await startDemo(page);
        const ticket = await createTicket(page, uniqueTitle('synthetic requester'));
        const ticketPath = new URL(ticket.url).pathname;
        await addComment(page, 'Synthetic requester: please investigate this sample issue.');
        await startDemo(other);
        await expect(other.getByRole('link', { name: ticket.title, exact: true })).toHaveCount(0);
        await other.goto(ticket.url);
        await expect(other.getByRole('heading', { name: 'Ticket not found', exact: true })).toBeVisible();
        await selectDemoRole(other, 'agent');
        const otherCsrf = await csrfFor(other);
        for (const suffix of ['', '/comments', '/history']) {
            expect((await demoRequest(other, `/api${ticketPath.slice(4)}${suffix}`)).status).toBe(404);
        }
        const apiPath = `/api${ticketPath.slice(4)}`;
        expect((await demoRequest(other, `${apiPath}/comments`, 'POST', otherCsrf, { body: 'Synthetic cross-visitor attempt' })).status).toBe(404);
        expect((await demoRequest(other, `${apiPath}/claim`, 'POST', otherCsrf)).status).toBe(404);
        expect((await demoRequest(other, `${apiPath}/status`, 'PATCH', otherCsrf, { status: 'IN_PROGRESS' })).status).toBe(404);
        await selectDemoRole(page, 'agent');
        await page.getByRole('link', { name: ticket.title, exact: true }).click();
        await page.getByRole('button', { name: 'Claim ticket', exact: true }).click();
        await expect(page.getByText('Ticket claimed.', { exact: true })).toBeVisible();
        await page.getByRole('button', { name: 'Set status to In progress', exact: true }).click();
        await expect(page.getByRole('list', { name: 'History events' }).getByText('Status changed from Open to In progress.', { exact: true })).toBeVisible();
        await addComment(page, 'Synthetic agent: the sample issue is being investigated.');
        await selectDemoRole(page, 'requester');
        await page.getByRole('link', { name: ticket.title, exact: true }).click();
        await page.reload();
        await expect(page.getByRole('heading', { name: ticket.title, exact: true })).toBeVisible();
        await expect(page.getByRole('region', { name: 'Ticket details', exact: true })
            .getByRole('definition').filter({ hasText: /^In progress$/ })).toBeVisible();
        await expect(page.getByRole('region', { name: 'Agent workflow' })).toHaveCount(0);
        await expect(page.getByRole('list', { name: 'Ticket comments' }).getByText('Synthetic agent: the sample issue is being investigated.', { exact: true })).toBeVisible();
        await endDemo(page);
        // Ending one visitor does not end the other's authenticated workspace.
        expect((await demoRequest(other, '/api/auth/session')).status).toBe(200);
        await endDemo(other);
    } finally { await otherContext.close(); }
});

test('runtime discovery allocates no session; start and switch rotate cookies and retire old CSRF without selectors', async ({ page, context, browser }) => {
    const discovery = await context.request.get('/api/demo');
    expect(discovery.status()).toBe(200);
    expect(await discovery.json()).toEqual({ enabled: true, active: false, expiresAt: null });
    expect(await context.cookies()).toHaveLength(0);
    await page.goto('/login');
    await expect(page.getByRole('button', { name: 'Start synthetic demo' })).toBeVisible();
    const anonymousCsrf = await csrfFor(page);
    const anonymousCookies = await context.cookies();
    await page.getByRole('button', { name: 'Start synthetic demo' }).click();
    await expect(page.getByText('Role: REQUESTER', { exact: true })).toBeVisible();
    const requesterCookies = await context.cookies();
    expect(requesterCookies.find((cookie) => cookie.name === 'JSESSIONID')?.value)
        .not.toBe(anonymousCookies.find((cookie) => cookie.name === 'JSESSIONID')?.value);
    const requesterCsrf = await csrfFor(page);
    expect(requesterCsrf.token).not.toBe(anonymousCsrf.token);
    expect((await demoRequest(page, '/api/demo/agent', 'POST', anonymousCsrf)).status).toBe(403);
    expect((await demoRequest(page, '/api/demo/agent', 'POST', requesterCsrf, { role: 'ADMIN' })).status).toBe(400);
    const before = (await demoRequest(page, '/api/demo')).body;
    await selectDemoRole(page, 'agent');
    const agentCookies = await context.cookies();
    expect(agentCookies.find((cookie) => cookie.name === 'JSESSIONID')?.value)
        .not.toBe(requesterCookies.find((cookie) => cookie.name === 'JSESSIONID')?.value);
    const agentCsrf = await csrfFor(page);
    expect(agentCsrf.token).not.toBe(requesterCsrf.token);
    expect((await demoRequest(page, '/api/demo/requester', 'POST', requesterCsrf)).status).toBe(403);
    await page.reload();
    await expect(page.getByText('Role: AGENT', { exact: true })).toBeVisible();
    expect((await demoRequest(page, '/api/demo')).body).toEqual(before);
    const replayContext = await browser.newContext({ storageState: { cookies: requesterCookies, origins: [] } });
    try {
        const replay = await replayContext.newPage();
        await replay.goto('/login');
        await expect(replay.getByRole('heading', { name: 'Explore the synthetic demo' })).toBeVisible();
        expect((await demoRequest(replay, '/api/auth/session')).status).toBe(401);
        expect((await demoRequest(replay, '/api/demo')).body).toMatchObject({ active: false });
    } finally { await replayContext.close(); }
    await endDemo(page);
    expect((await demoRequest(page, '/api/demo/agent', 'POST', agentCsrf)).status).toBe(403);
});
