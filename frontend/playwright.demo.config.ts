import { defineConfig, devices } from '@playwright/test';
import base from './playwright.config';

export default defineConfig({
    ...base,
    projects: [
        { name: 'chromium-demo-desktop', testMatch: 'demo.spec.ts', use: { ...devices['Desktop Chrome'] } },
        { name: 'chromium-demo-mobile', testMatch: 'demo-mobile.spec.ts', use: { ...devices['Pixel 7'] } },
    ],
});
