import { ApiError, type ProblemDetail } from './ApiError';

type RequestOptions = Omit<RequestInit, 'credentials'>;
type JsonRequestOptions<T> = RequestOptions & { decode: (value: unknown) => T };
type ApiPath = `/api/${string}`;

function isProblemDetail(value: unknown): value is ProblemDetail {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
        return false;
    }
    return (!('type' in value) || typeof value.type === 'string')
        && (!('title' in value) || typeof value.title === 'string')
        && (!('status' in value) || (typeof value.status === 'number' && Number.isInteger(value.status)))
        && (!('detail' in value) || typeof value.detail === 'string')
        && (!('instance' in value) || typeof value.instance === 'string');
}

export function apiRequest<T>(path: ApiPath, options: JsonRequestOptions<T>): Promise<T | undefined>;
export function apiRequest(path: ApiPath, options?: RequestOptions): Promise<unknown>;
export async function apiRequest(
    path: ApiPath,
    options: RequestOptions & { decode?: (value: unknown) => unknown } = {},
): Promise<unknown> {
    const { decode, ...requestOptions } = options;
    const headers = new Headers(requestOptions.headers);
    if (!headers.has('Accept')) {
        headers.set('Accept', 'application/json, application/problem+json');
    }
    const response = await fetch(path, { ...requestOptions, headers, credentials: 'include' });

    if (!response.ok) {
        let problem: ProblemDetail | undefined;
        const mediaType = response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase();
        if (mediaType === 'application/problem+json') {
            const body = await response.text();
            try {
                const value: unknown = JSON.parse(body);
                if (isProblemDetail(value)) {
                    problem = value;
                }
            } catch (error) {
                if (!(error instanceof SyntaxError)) {
                    throw error;
                }
            }
        }
        throw new ApiError(response.status, problem);
    }

    if (response.status === 204) {
        return undefined;
    }
    const body = await response.text();
    if (body.trim() === '') {
        return undefined;
    }
    // JSON is untrusted until a caller narrows it or supplies a decoder.
    const value: unknown = JSON.parse(body);
    return decode ? decode(value) : value;
}
