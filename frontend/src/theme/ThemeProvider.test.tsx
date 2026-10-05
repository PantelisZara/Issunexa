import html from '../../index.html?raw';
import { StrictMode } from 'react';
import { act, cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Brand } from '../components/Brand';
import { ThemeControl } from '../components/ThemeControl';
import { ThemeProvider } from './ThemeProvider';
import { SYSTEM_THEME_QUERY, THEME_STORAGE_KEY } from './theme';
import { useTheme } from './useTheme';

const startupScript = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
if (!startupScript) throw new Error('The initial theme script is missing.');
const applyStartupTheme = new Function(startupScript);

function mockSystemTheme(initialDark: boolean) {
    let dark = initialDark;
    const events = new EventTarget();
    const query = {
        get matches() { return dark; },
        media: SYSTEM_THEME_QUERY,
        addEventListener: vi.fn(events.addEventListener.bind(events)),
        removeEventListener: vi.fn(events.removeEventListener.bind(events)),
    };
    vi.stubGlobal('matchMedia', vi.fn(() => query));
    return {
        query,
        change(nextDark: boolean) {
            dark = nextDark;
            act(() => events.dispatchEvent(new Event('change')));
        },
    };
}

function ThemeState() {
    const { preference, resolvedTheme } = useTheme();
    return <output aria-label="Theme state">{preference}/{resolvedTheme}</output>;
}

function renderTheme() {
    return render(<StrictMode><ThemeProvider><ThemeControl /><ThemeState /><Brand /></ThemeProvider></StrictMode>);
}

function expectTheme(preference: string, resolved: 'light' | 'dark') {
    expect(screen.getByRole('combobox', { name: 'Theme' })).toHaveValue(preference);
    expect(screen.getByLabelText('Theme state')).toHaveTextContent(`${preference}/${resolved}`);
    expect(document.documentElement).toHaveAttribute('data-theme', resolved);
    expect(screen.getByRole('img', { name: 'Issunexa' })).toHaveAttribute(
        'src', `/branding/issunexa-logo-${resolved === 'dark' ? 'light' : 'dark'}.svg`,
    );
}

beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
});

describe('theme startup before the application renders', () => {
    it('runs in the document head before the application entry', () => {
        expect(html.indexOf('<script>')).toBeLessThan(html.indexOf('</head>'));
        expect(html.indexOf('<script>')).toBeLessThan(html.indexOf('/src/main.tsx'));
    });

    it.each([
        [null, false, 'light'],
        [null, true, 'dark'],
        ['system', true, 'dark'],
        ['light', true, 'light'],
        ['dark', false, 'dark'],
        ['invalid', true, 'dark'],
    ] as const)('applies saved %s with system dark=%s before React', (saved, dark, expected) => {
        if (saved !== null) localStorage.setItem(THEME_STORAGE_KEY, saved);
        mockSystemTheme(dark);
        applyStartupTheme();
        expect(document.documentElement).toHaveAttribute('data-theme', expected);
        renderTheme();
        expectTheme(saved === 'light' || saved === 'dark' ? saved : 'system', expected);
    });

    it('still resolves the OS theme when access to localStorage is blocked', () => {
        mockSystemTheme(true);
        vi.stubGlobal('localStorage', undefined);
        expect(applyStartupTheme).not.toThrow();
        expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
        renderTheme();
        expectTheme('system', 'dark');
    });

    it('falls back to light if matchMedia is unavailable', () => {
        vi.stubGlobal('matchMedia', undefined);
        applyStartupTheme();
        renderTheme();
        expectTheme('system', 'light');
    });
});

describe('theme preference and system changes', () => {
    it.each([false, true])('defaults to system, resolves dark=%s and does not save a resolved preference', (dark) => {
        mockSystemTheme(dark);
        renderTheme();
        expectTheme('system', dark ? 'dark' : 'light');
        expect(window.matchMedia).toHaveBeenCalledWith(SYSTEM_THEME_QUERY);
        expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
    });

    it.each(['', 'invalid', 'DARK', '"dark"'])('falls back to system for invalid saved value %s', (saved) => {
        localStorage.setItem(THEME_STORAGE_KEY, saved);
        mockSystemTheme(true);
        renderTheme();
        expectTheme('system', 'dark');
    });

    it.each(['light', 'dark'] as const)('restores saved %s even when the OS disagrees', (preference) => {
        localStorage.setItem(THEME_STORAGE_KEY, preference);
        const system = mockSystemTheme(preference === 'light');
        renderTheme();
        expectTheme(preference, preference);
        expect(system.query.addEventListener).not.toHaveBeenCalled();
    });

    it('follows live OS changes, keeps the preference as system and cleans up the subscription', async () => {
        const system = mockSystemTheme(false);
        const view = renderTheme();
        await userEvent.setup().selectOptions(screen.getByRole('combobox', { name: 'Theme' }), 'system');
        system.change(true);
        expectTheme('system', 'dark');
        system.change(false);
        expectTheme('system', 'light');
        expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('system');
        view.unmount();
        expect(system.query.removeEventListener.mock.calls).toEqual(system.query.addEventListener.mock.calls);
    });

    it.each(['light', 'dark'] as const)('persists %s, stops following the OS, and resumes on system', async (preference) => {
        const system = mockSystemTheme(false);
        const user = userEvent.setup();
        const view = renderTheme();
        const control = screen.getByRole('combobox', { name: 'Theme' });
        await user.selectOptions(control, preference);
        expectTheme(preference, preference);
        expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe(preference);
        system.change(true);
        expectTheme(preference, preference);
        system.change(false);
        expectTheme(preference, preference);
        view.unmount();
        renderTheme();
        expectTheme(preference, preference);
        system.change(true);
        expectTheme(preference, preference);
        await user.selectOptions(screen.getByRole('combobox', { name: 'Theme' }), 'system');
        expectTheme('system', 'dark');
        expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('system');
        system.change(false);
        expectTheme('system', 'light');
    });

    it('handles a storage read error and still allows theme selection', async () => {
        vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('Blocked'); });
        mockSystemTheme(true);
        renderTheme();
        expectTheme('system', 'dark');
        await userEvent.setup().selectOptions(screen.getByRole('combobox', { name: 'Theme' }), 'light');
        expectTheme('light', 'light');
    });

    it('keeps working when saving the preference fails', async () => {
        vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Quota exceeded'); });
        const system = mockSystemTheme(false);
        renderTheme();
        await userEvent.setup().selectOptions(screen.getByRole('combobox', { name: 'Theme' }), 'dark');
        expectTheme('dark', 'dark');
        system.change(true);
        system.change(false);
        expectTheme('dark', 'dark');
    });

    it('provides a labeled native keyboard-focusable control with a clear current option', async () => {
        mockSystemTheme(false);
        const user = userEvent.setup();
        renderTheme();
        const control = screen.getByRole('combobox', { name: 'Theme' });
        expect(control.tagName).toBe('SELECT');
        expect(within(control).getAllByRole('option').map((option) => option.textContent)).toEqual(['System', 'Light', 'Dark']);
        await user.tab();
        expect(control).toHaveFocus();
        expect(within(control).getByRole('option', { name: 'System', selected: true })).toBeInTheDocument();
        await user.selectOptions(control, 'dark');
        expect(control).toHaveFocus();
        expect(within(control).getByRole('option', { name: 'Dark', selected: true })).toBeInTheDocument();
        expectTheme('dark', 'dark');
    });
});
