#!/usr/bin/env bash
set -Eeuo pipefail

artifact=false
if [[ ${1:-} == --artifact ]]; then
    artifact=true
    shift
fi

frontend_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
repo_dir=$(cd -- "$frontend_dir/.." && pwd)
cd -- "$frontend_dir"

if [[ $(node -p 'process.versions.node.split(".")[0]') != 24 ]]; then
    printf 'E2E tooling requires Node 24.\n' >&2
    exit 1
fi
command -v docker >/dev/null || { printf 'Docker is required.\n' >&2; exit 1; }
docker info >/dev/null
docker compose version >/dev/null
docker buildx version >/dev/null

# A fresh name prevents stale or concurrent runs from sharing database state.
export PLAYWRIGHT_VERSION
PLAYWRIGHT_VERSION=$(node -p 'require("@playwright/test/package.json").version')
export E2E_UID E2E_GID
E2E_UID=$(id -u)
E2E_GID=$(id -g)

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/issunexa-e2e.XXXXXXXX")
project="issunexa-e2e-$(date +%s)-$$"
compose=(docker compose --project-directory "$repo_dir" --env-file "$temp_dir/environment"
    -p "$project" --profile e2e -f "$repo_dir/compose.yaml" -f "$repo_dir/compose.e2e.yaml")
build_services=(postgres backend frontend playwright)
start_services=(postgres backend frontend)
if $artifact; then
    compose+=(-f "$repo_dir/compose.artifact.e2e.yaml")
    build_services=(postgres backend playwright)
    start_services=(postgres backend)
fi
compose_ready=false
cleanup() {
    result=$?
    trap - EXIT INT TERM
    if $compose_ready && (( result != 0 )); then
        printf '\nE2E failed (exit %s). Recent service logs:\n' "$result" >&2
        "${compose[@]}" logs --no-color --tail=100 postgres database-bootstrap backend frontend >&2 || true
    fi
    # Only this invocation's resources; never the normal database volume.
    if $compose_ready && ! "${compose[@]}" down -v --remove-orphans; then
        printf 'E2E cleanup failed for project %s.\n' "$project" >&2
        if (( result == 0 )); then result=1; fi
    fi
    for service in backend frontend playwright; do
        image="$project-$service:latest"
        if docker image inspect "$image" >/dev/null 2>&1; then
            if ! docker image rm "$image"; then
                printf 'Could not remove E2E image tag %s.\n' "$image" >&2
                if (( result == 0 )); then result=1; fi
            fi
        fi
    done
    rm -rf -- "$temp_dir"
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Explicit env-file and cleared inherited settings keep the user's .env unused.
unset ISSUNEXA_POSTGRES_DB ISSUNEXA_POSTGRES_USER ISSUNEXA_POSTGRES_PASSWORD
unset ISSUNEXA_DB_USERNAME ISSUNEXA_DB_PASSWORD ISSUNEXA_FLYWAY_USERNAME ISSUNEXA_FLYWAY_PASSWORD
unset ISSUNEXA_POSTGRES_PORT ISSUNEXA_BACKEND_PORT
unset ISSUNEXA_FRONTEND_BIND_ADDRESS ISSUNEXA_FRONTEND_PORT
unset ISSUNEXA_PUBLIC_ORIGIN ISSUNEXA_PUBLIC_INGRESS
unset ISSUNEXA_LOGIN_ACCOUNT_LIMIT ISSUNEXA_LOGIN_ACCOUNT_WINDOW
unset ISSUNEXA_LOGIN_SOURCE_LIMIT ISSUNEXA_LOGIN_SOURCE_WINDOW
unset ISSUNEXA_LOGIN_MAX_ACCOUNT_ENTRIES ISSUNEXA_LOGIN_MAX_SOURCE_ENTRIES ISSUNEXA_LOGIN_TRUSTED_PROXY
node --input-type=module - "$temp_dir/environment" <<'JS'
import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
writeFileSync(process.argv[2], [
    'ISSUNEXA_POSTGRES_DB=issunexa_e2e',
    'ISSUNEXA_POSTGRES_USER=issunexa_e2e',
    `ISSUNEXA_POSTGRES_PASSWORD=${randomBytes(32).toString('hex')}`,
    'ISSUNEXA_FLYWAY_USERNAME=issunexa_migrator',
    `ISSUNEXA_FLYWAY_PASSWORD=${randomBytes(32).toString('hex')}`,
    'ISSUNEXA_DB_USERNAME=issunexa_runtime',
    `ISSUNEXA_DB_PASSWORD=${randomBytes(32).toString('hex')}`,
    'ISSUNEXA_FRONTEND_BIND_ADDRESS=127.0.0.1',
].join('\n') + '\n', { mode: 0o600 });
JS

mkdir -p e2e-artifacts
printf 'Disposable E2E project: %s; Playwright %s\n' "$project" "$PLAYWRIGHT_VERSION"
"${compose[@]}" config -q
compose_ready=true
"${compose[@]}" build "${build_services[@]}"
"${compose[@]}" up -d "${start_services[@]}"
"${compose[@]}" run --rm --no-deps -T playwright node e2e/wait-ready.mjs
"${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U issunexa_e2e -d issunexa_e2e \
    < e2e/fixtures/accounts.sql
if $artifact; then
    "${compose[@]}" run --rm --no-deps -T playwright \
        xvfb-run -a npm exec -- playwright test \
        --project=chromium-desktop --project=chromium-mobile "$@"
    "${compose[@]}" run --rm --no-deps -T playwright \
        xvfb-run -a npm exec -- playwright test \
        --config=playwright.artifact.config.ts --project=chromium-artifact-contract
    # Inspect the actual tested service, not a separate unconstrained image.
    "${compose[@]}" exec -T backend sh -ec '
        test "$(id -u)" = 10001
        for tool in node npm javac mvn; do
            if command -v "$tool" >/dev/null 2>&1; then exit 1; fi
        done
        test "$(cat /sys/fs/cgroup/memory.max)" = 536870912
        printf "Artifact memory limit bytes: "
        cat /sys/fs/cgroup/memory.max
        printf "Artifact peak memory bytes: "
        cat /sys/fs/cgroup/memory.peak
    '
    container=$("${compose[@]}" ps -q backend)
    test "$(docker inspect --format '{{.State.OOMKilled}}' "$container")" = false
else
    "${compose[@]}" run --rm --no-deps -T playwright \
        xvfb-run -a npm exec -- playwright test "$@"
fi
