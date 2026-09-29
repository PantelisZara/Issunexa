export interface ProblemDetail {
    type?: string;
    title?: string;
    status?: number;
    detail?: string;
    instance?: string;
    [extension: string]: unknown;
}

export class ApiError extends Error {
    readonly status: number;
    readonly problem: ProblemDetail | undefined;

    constructor(status: number, problem?: ProblemDetail) {
        // Keep raw server content out of the default error message.
        super(`API request failed (HTTP ${status}).`);
        this.name = 'ApiError';
        this.status = status;
        this.problem = problem;
    }
}
