import { apiRequest } from '../api/apiRequest';
import type { CsrfMetadata } from './authTypes';

export type DemoMetadata =
    | { enabled: false; active: false; expiresAt: null }
    | { enabled: true; active: false; expiresAt: string | null }
    | { enabled: true; active: true; expiresAt: string };
export type DemoAction = 'start' | 'requester' | 'agent';

export function decodeDemo(value: unknown): DemoMetadata {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new TypeError('Invalid demo response.');
    const data = value as Record<string, unknown>;
    const expiry = data.expiresAt;
    if (typeof data.enabled !== 'boolean' || typeof data.active !== 'boolean'
        || (expiry !== null && (typeof expiry !== 'string'
            || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(expiry)
            || !Number.isFinite(Date.parse(expiry))))
        || (!data.enabled && (data.active || expiry !== null)) || (data.active && expiry === null)) {
        throw new TypeError('Invalid demo response.');
    }
    return { enabled: data.enabled, active: data.active, expiresAt: expiry } as DemoMetadata;
}

export async function getDemo(): Promise<DemoMetadata> {
    return decodeDemo(await apiRequest('/api/demo', { cache: 'no-store' }));
}

export async function transitionDemo(action: DemoAction, csrf: CsrfMetadata): Promise<void> {
    // Fixed routes only: no account, role or workspace selectors are sent.
    const routes = { start: '/api/demo/start', requester: '/api/demo/requester', agent: '/api/demo/agent' } as const;
    await apiRequest(routes[action], { method: 'POST', headers: { [csrf.headerName]: csrf.token } });
}
