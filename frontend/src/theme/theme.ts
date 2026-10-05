export type ThemePreference = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'issunexa.theme';
export const SYSTEM_THEME_QUERY = '(prefers-color-scheme: dark)';

export function isThemePreference(value: unknown): value is ThemePreference {
    return value === 'light' || value === 'dark' || value === 'system';
}

export function readThemePreference(): ThemePreference {
    try {
        const saved = window.localStorage.getItem(THEME_STORAGE_KEY);
        return isThemePreference(saved) ? saved : 'system';
    } catch {
        return 'system';
    }
}

export function saveThemePreference(preference: ThemePreference): void {
    try {
        window.localStorage.setItem(THEME_STORAGE_KEY, preference);
    } catch {
        // Theme changes still work for this session when storage is unavailable.
    }
}

export function systemThemeQuery(): MediaQueryList | undefined {
    return typeof window.matchMedia === 'function' ? window.matchMedia(SYSTEM_THEME_QUERY) : undefined;
}
