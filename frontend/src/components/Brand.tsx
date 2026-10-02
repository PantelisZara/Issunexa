export function Brand({ compact = false }: { compact?: boolean }) {
    return (
        <span className={`brand-artwork${compact ? ' brand-artwork-compact' : ''}`}>
            <img className="brand-wordmark" src="/branding/issunexa-logo-dark.svg" alt="Issunexa" />
            <img className="brand-symbol" src="/branding/issunexa-icon.svg" alt="" />
        </span>
    );
}
