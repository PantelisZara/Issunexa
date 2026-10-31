import { expect, test } from '@playwright/test';

test('compiled UI routes and fingerprinted assets are served by the Java artifact', async ({ request }) => {
    for (const path of ['/', '/login', '/app', '/app/tickets', '/app/tickets/new', '/app/tickets/42']) {
        const response = await request.get(path);
        expect(response.status()).toBe(200);
        expect(response.headers()['content-type']).toContain('text/html');
        expect(await response.text()).toContain('<div id="root"></div>');
        expect(response.headers()['cache-control']).toContain('no-store');
        expect(response.headers()['referrer-policy']).toBe('strict-origin-when-cross-origin');
    }
    const html = await (await request.get('/')).text();
    const asset = /src="(\/assets\/[^"]+\.js)"/.exec(html)?.[1];
    if (!asset) throw new Error('Production entry script was not found.');
    const script = await request.get(asset);
    expect(script.status()).toBe(200);
    expect(script.headers()['content-type']).toMatch(/(?:java|ecma)script/);
    expect(script.headers()['cache-control']).toContain('immutable');
    expect(script.headers()['x-content-type-options']).toBe('nosniff');
});

test('missing assets and protected API paths never become successful HTML', async ({ request }) => {
    for (const path of ['/assets/missing.js', '/branding/missing.svg']) {
        expect((await request.get(path)).status()).toBe(404);
    }
    for (const path of ['/api/auth/session', '/api/missing', '/not-an-application-route']) {
        const response = await request.get(path);
        expect(response.status()).toBe(401);
        expect(response.headers()['content-type']).toContain('application/problem+json');
        expect(await response.text()).not.toContain('<div id="root">');
    }
    expect((await request.post('/app/tickets', { data: {} })).status()).toBe(403);
});
