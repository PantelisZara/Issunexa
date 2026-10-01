export type UserRole = 'REQUESTER' | 'AGENT' | 'ADMIN';

export interface AuthenticatedUser {
    id: number;
    email: string;
    displayName: string;
    role: UserRole;
}

export interface CsrfMetadata {
    token: string;
    headerName: string;
}

export type AuthState =
    | { status: 'loading' }
    | { status: 'authenticated'; user: AuthenticatedUser }
    | { status: 'unauthenticated'; message?: string }
    | { status: 'error'; message: string };
