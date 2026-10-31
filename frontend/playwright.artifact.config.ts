import { defineConfig, devices } from '@playwright/test';
import base from './playwright.config';

export default defineConfig({
    ...base,
    projects: [
        { name: 'chromium-artifact-contract', testMatch: 'artifact.spec.ts',
            use: { ...devices['Desktop Chrome'] } },
    ],
});
