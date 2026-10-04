import { setTimeout as pollDelay } from 'node:timers/promises';

const url = `${process.env.PLAYWRIGHT_BASE_URL}/api/auth/csrf`;
const deadline = Date.now() + 90_000;
let lastFailure = 'No response yet';
while (Date.now() < deadline) {
    try {
        const response = await fetch(url, { signal: AbortSignal.timeout(3000) });
        if (response.ok) {
            const data = await response.json();
            if (typeof data.token === 'string' && data.token.length > 0
                && typeof data.headerName === 'string' && data.headerName.length > 0) {
                console.log('Nginx → backend → PostgreSQL ready; CSRF response validated.');
                process.exit(0);
            }
            lastFailure = 'CSRF response did not match the application contract';
        } else {
            lastFailure = `HTTP ${response.status}`;
        }
    } catch (error) {
        lastFailure = error instanceof Error ? error.name : 'Request failed';
    }
    await pollDelay(1000);
}
console.error(`Timed out waiting for ${url}: ${lastFailure}`);
process.exit(1);
