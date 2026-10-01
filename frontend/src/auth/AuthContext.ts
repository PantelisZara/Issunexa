import { createContext } from 'react';
import type { AuthState, CsrfMetadata } from './authTypes';

export interface AuthContextValue {
    state: AuthState;
    pending: boolean;
    login: (email: string, password: string) => Promise<string | undefined>;
    logout: () => Promise<string | undefined>;
    retry: () => Promise<void>;
    expireSession: () => void;
    getMutationCsrf: (refresh?: boolean) => Promise<CsrfMetadata>;
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined);
