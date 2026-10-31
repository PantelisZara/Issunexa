# Node runtime and jsdom assessment (SEC-03)

Assessed on 2026-10-31 against public master `5c443a30f1753353a26522000913d5546c34c8b2`. Decision owner: Issunexa engineering orchestration, under the maintainer's authorized delivery scope. Reassess whenever the conditions below change; this is a scoped disposition, not a general security clearance.

## Separate dependency layers

The build and E2E toolchains use [Node 24.22.2](https://nodejs.org/en/blog/release/v24.22.2), incorporating the [October security release](https://nodejs.org/en/blog/vulnerability/october-2026-security-releases). Its [embedded Undici is 7.30.1](https://github.com/nodejs/node/blob/v24.22.2/deps/undici/src/package.json). The frontend lockfile separately resolves npm Undici **8.12.3**, a development dependency. Updating either layer does not replace jsdom's copied implementation. Production serves compiled assets from Nginx; Node, jsdom and npm dependencies are not copied into that runtime image.

## Copied decompression code

[GHSA-55wc-3pg8-jfw6](https://github.com/nodejs/undici/security/advisories/GHSA-55wc-3pg8-jfw6) describes an uncaught exception when a downstream handler throws while processing a compressed response. The [Undici fix](https://github.com/nodejs/undici/commit/f851f315db1f2acce0ea668f88d9d0fa61c8420c) catches asynchronous data/end callback throws and turns them into request failures.

jsdom **30.1.1** contains its own [decompress-interceptor.js](https://github.com/jsdom/jsdom/blob/v30.1.1/lib/jsdom/browser/resources/decompress-interceptor.js), derived from Undici. The copied data/end callbacks lack that handling. Registry archives for **30.1.1, 30.1.2 and 30.1.3** were checked against their published SHA-512 integrity values. The extracted interceptor has the same SHA-256 in all three:

```text
43cf221c8039a77c1aac1918aa033d0711b1961e998d62dd57cd0c0744841dae
```

Consequently, upgrading to 30.1.2/30.1.3, overriding npm Undici, or patching Node cannot be represented as repairing this copy. No jsdom-specific advisory or exploit against Issunexa was established. General exploitability, including every possible default-handler failure, remains **unknown**; no exploit or denial-of-service payload was executed.

## Current application reachability

- jsdom is a development-only dependency, used by [Vitest configuration](../frontend/vite.config.ts) for local first-party `src/**/*.test.{ts,tsx}` tests.
- There are no jsdom resource/environment overrides or custom throwing resource interceptors. Vitest creates an ordinary local document. The interceptor can be constructed without receiving network traffic.
- Network-facing tests replace `fetch` with mocks. Current source has no XHR, EventSource, WebSocket, `JSDOM.fromURL` or remote resource-loading path. Example URLs test parsing and navigation rejection rather than retrieving remote documents.
- The Vite production build does not run the jsdom environment. Real-stack Playwright tests use Chromium and local synthetic fixtures.
- The absence of a `resources` option is **not** a universal network block: XHR and native fetch require separate consideration if introduced.

Under this exact test configuration, an attacker-controlled compressed response reaching a throwing downstream handler has **not been demonstrated**. The copied control-flow pattern is present and potentially applicable through jsdom's configurable APIs; current Issunexa test reachability is not established. Neither universal jsdom safety nor a confirmed application vulnerability follows from this assessment. npm audit cannot establish the safety of copied code.

## Decision and reassessment

Accept the bounded current test-only exposure, retain jsdom 30.1.1 and existing network mocks, and apply the Node remediation independently. Do not introduce remote/untrusted documents or custom resource handlers without reviewing this decision first. This avoids unrelated DOM/transitive dependency drift without pretending the copied code is fixed.

Reassess on changes to jsdom, Vitest, its environment configuration, fetch mocks, XHR, remote resources, custom dispatchers/interceptors, or when a relevant upstream advisory/fix appears. If untrusted networking becomes necessary, verify a corrected upstream implementation or design and test isolation before enabling that path. Reverting this disposition means reopening the investigation; do not downgrade the patched Node runtime as a remedy for jsdom.

The exact lockfile and production dependency graph remain unchanged by SEC-03. Runtime execution, compatibility tests and hosted CI are recorded in the linked PR; prior SEC-02 results alone do not validate this change.
