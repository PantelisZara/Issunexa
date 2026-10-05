import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
    testDir: './e2e',
    tsconfig: './tsconfig.e2e.json',
    fullyParallel: false,
    workers: 1,
    retries: 0,
    forbidOnly: true,
    timeout: 60_000,
    expect: { timeout: 10_000 },
    reporter: [['list'], ['html', { open: 'never', outputFolder: './e2e-artifacts/playwright-report' }]],
    outputDir: './e2e-artifacts/test-results',
    use: {
        baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://frontend:8080',
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
        video: 'off',
    },
    projects: [
        { name: 'chromium-desktop', testMatch: 'journeys.spec.ts', use: { ...devices['Desktop Chrome'] } },
        { name: 'chromium-mobile', testMatch: 'mobile.spec.ts', use: { ...devices['Pixel 7'] } },
        { name: 'chromium-auth-throttle', testMatch: 'auth-throttle.spec.ts', use: { ...devices['Desktop Chrome'] } },
    ],
});
