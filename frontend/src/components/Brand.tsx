import { useTheme } from '../theme/useTheme';

export function Brand({ compact = false }: { compact?: boolean }) {
    const { resolvedTheme } = useTheme();
    return (
        <span className={`brand-artwork${compact ? ' brand-artwork-compact' : ''}`}>
            <img className="brand-wordmark" src={`/branding/issunexa-logo-${resolvedTheme === 'dark' ? 'light' : 'dark'}.svg`} alt="Issunexa" />
            <img className="brand-symbol" src="/branding/issunexa-icon.svg" alt="" />
        </span>
    );
}
