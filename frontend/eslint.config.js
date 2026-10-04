import js from '@eslint/js';
import { defineConfig, globalIgnores } from 'eslint/config';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

export default defineConfig([
    globalIgnores(['dist', 'coverage', 'playwright-report', 'test-results', 'e2e-artifacts']),
    {
        files: ['**/*.{ts,tsx}'],
        extends: [js.configs.recommended, tseslint.configs.recommended],
    },
    {
        files: ['src/**/*.{ts,tsx}'],
        extends: [reactHooks.configs.flat.recommended, reactRefresh.configs.vite],
        languageOptions: { globals: globals.browser },
    },
    {
        files: ['*.js', 'e2e/**/*.mjs'],
        extends: [js.configs.recommended],
        languageOptions: { globals: globals.node },
    },
]);
