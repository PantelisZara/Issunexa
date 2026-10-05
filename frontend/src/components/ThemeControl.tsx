import { useId } from 'react';
import { isThemePreference } from '../theme/theme';
import { useTheme } from '../theme/useTheme';

export function ThemeControl() {
    const id = useId();
    const { preference, setPreference } = useTheme();
    return (
        <div className="theme-control">
            <label htmlFor={id}>Theme</label>
            <select id={id} value={preference} onChange={(event) => {
                if (isThemePreference(event.target.value)) setPreference(event.target.value);
            }}>
                <option value="system">System</option>
                <option value="light">Light</option>
                <option value="dark">Dark</option>
            </select>
        </div>
    );
}
