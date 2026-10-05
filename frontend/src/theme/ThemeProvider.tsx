import { useCallback, useLayoutEffect, useState, useSyncExternalStore, type ReactNode } from 'react';
import { ThemeContext } from './ThemeContext';
import { readThemePreference, saveThemePreference, systemThemeQuery, type ResolvedTheme, type ThemePreference } from './theme';

export function ThemeProvider({ children }: { children: ReactNode }) {
    const [preference, setStoredPreference] = useState(readThemePreference);
    const [mediaQuery] = useState(systemThemeQuery);
    const subscribe = useCallback((onChange: () => void) => {
        if (preference !== 'system' || !mediaQuery) return () => {};
        mediaQuery.addEventListener('change', onChange);
        return () => mediaQuery.removeEventListener('change', onChange);
    }, [preference, mediaQuery]);
    const getSnapshot = useCallback((): ResolvedTheme => {
        if (preference !== 'system') return preference;
        return mediaQuery?.matches ? 'dark' : 'light';
    }, [preference, mediaQuery]);
    const resolvedTheme = useSyncExternalStore(subscribe, getSnapshot);

    useLayoutEffect(() => {
        document.documentElement.dataset.theme = resolvedTheme;
    }, [resolvedTheme]);

    function setPreference(next: ThemePreference) {
        saveThemePreference(next);
        setStoredPreference(next);
    }

    return (
        <ThemeContext value={{ preference, resolvedTheme, setPreference }}>
            {children}
        </ThemeContext>
    );
}
