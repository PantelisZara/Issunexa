# Development glob assessment (SEC-04)

Assessed 2026-10-31 against the unchanged dependency graph from public master 57edcb82f4c561d7a178c55c6aa73cc4e8ae724b. Decision owner: Issunexa engineering orchestration under the maintainer's authorized delivery scope.

## Finding

A fresh full npm audit reports **12 moderate affected package entries from one advisory**, [GHSA-g3hv-75wg-m249](https://github.com/isaacs/minimatch/security/advisories/GHSA-g3hv-75wg-m249) / CVE-2026-118764. This supersedes the earlier zero-finding snapshot; it is not a Node-runtime regression. The [reviewed advisory](https://github.com/advisories/GHSA-g3hv-75wg-m249) marks minimatch versions below 11.0.1 affected. The attack needs hostile glob input and optimizationLevel at least 2; amplification consumes CPU/memory. The remaining package entries are dependency propagation, not eleven additional independent flaws.

The lockfile resolves minimatch **10.2.6**, development-only, through:

| Consumer | Requested minimatch range |
| --- | --- |
| eslint 10.11.0 | ^10.2.5 |
| @eslint/config-array 0.23.5 | ^10.2.4 |
| @typescript-eslint/typescript-estree 8.71.0 | ^10.2.2 |

## Current reachability and decision

Inspected installed call sites in eslint/lib/eslint/eslint-helpers.js and @eslint/config-array/dist/esm/index.js use dot:true, with flipNegate where needed, and do not raise optimizationLevel. The optional typescript-estree project-service path likewise uses dot:true; this project does not enable projectService/allowDefaultProject. minimatch defaults optimizationLevel to **1**, so these inspected calls do not reach the advisory's vulnerable preprocessing branch.

The tracked [ESLint configuration](../frontend/eslint.config.js) supplies static patterns. No application minimatch import or public user-controlled glob input was found. The production npm audit reports zero findings, and the frontend runtime contains compiled assets rather than development Node packages.

Accept the bounded current development-tooling risk with **no dependency change**. This is a source-based reachability disposition, not a claim that minimatch 10.2.6 is patched or universally safe. No denial-of-service payload was executed. Full audit continues to exit nonzero and must be reported accurately.

## Remediation tradeoffs

The first fixed major is 11.0.1. A blanket v11 override crosses all three consumers' declared ranges and introduces [API/matching changes](https://github.com/isaacs/minimatch/blob/v11.0.1/changelog.md), including removed regex APIs. npm's suggested major downgrades are not accepted as verified fixes: typescript-estree 8.14.0 still [requests minimatch ^9.0.4](https://github.com/typescript-eslint/typescript-eslint/blob/v8.14.0/packages/typescript-estree/package.json), within the advisory's affected range.

Prefer a supported corrected parent release or verified compatible backport when available. Reassess on minimatch/ESLint/typescript-eslint upgrades, changes to lint glob sources/options, optimizationLevel >=2, or any new user-controlled pattern input. Review this disposition during dependency maintenance; it is not permission to ignore future findings. If those conditions change before a supported patch is available, stop using the affected path and reassess isolation/remediation.

## Evidence and limits

The fresh Node 24.22.2/npm 11.19.1 verification recorded npm ci, full/production audits, dependency graph checks, lint, both TypeScript checks, 385 frontend tests, production build and 9 full-stack E2E tests. Those checks establish compatibility of the unchanged graph; they do not prove security of every consumer. Hosted checks for PR #6 passed separately. This assessment changes documentation only and leaves the manifest, lockfile and production graph unchanged.
