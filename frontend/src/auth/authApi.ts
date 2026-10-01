import { apiRequest } from '../api/apiRequest';
import type { AuthenticatedUser, CsrfMetadata } from './authTypes';

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNonblank(value: unknown): value is string {
    return typeof value === 'string' && value.trim().length > 0;
}

export function decodeCsrf(value: unknown): CsrfMetadata {
    if (!isRecord(value) || !isNonblank(value.token) || !isNonblank(value.headerName)
        || !/^[!#$%&'*+.^_`|~0-9a-z-]+$/i.test(value.headerName) || /[\r\n]/.test(value.token)) {
        throw new TypeError('Invalid CSRF response.');
    }
    return { token: value.token, headerName: value.headerName };
}

export function decodeSession(value: unknown): AuthenticatedUser {
    if (!isRecord(value) || typeof value.id !== 'number' || !Number.isSafeInteger(value.id) || value.id <= 0
        || !isNonblank(value.email) || !isNonblank(value.displayName)
        || (value.role !== 'REQUESTER' && value.role !== 'AGENT' && value.role !== 'ADMIN')) {
        throw new TypeError('Invalid authenticated session response.');
    }
    return { id: value.id, email: value.email, displayName: value.displayName, role: value.role };
}

export async function getCsrf(): Promise<CsrfMetadata> {
    return decodeCsrf(await apiRequest('/api/auth/csrf', { cache: 'no-store' }));
}

export async function getSession(): Promise<AuthenticatedUser> {
    return decodeSession(await apiRequest('/api/auth/session', { cache: 'no-store' }));
}

export async function login(email: string, password: string, csrf: CsrfMetadata): Promise<void> {
    await apiRequest('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ email, password }),
    });
}

export async function logout(csrf: CsrfMetadata): Promise<void> {
    await apiRequest('/api/auth/logout', {
        method: 'POST',
        headers: { [csrf.headerName]: csrf.token },
    });
}
