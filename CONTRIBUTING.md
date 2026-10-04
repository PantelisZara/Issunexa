# Contributing to Issunexa

Issunexa is a single-maintainer portfolio project. Keep changes focused and use this workflow for feature, infrastructure, testing and hardening work:

**Issue → task branch → pull request → CI → review → merge**

## Plan the work

Open a GitHub Issue using the [implementation task template](.github/ISSUE_TEMPLATE/implementation_task.md). Define the objective, context, scope, non-goals, implementation requirements, verification and acceptance criteria before starting. Clarify material scope changes in the issue.

## Create a task branch

Start from an up-to-date, clean `master`. Use `issue-<number>-<short-topic>` with lowercase words separated by hyphens, for example `issue-1-contribution-workflow`:

```sh
git switch master
git pull --ff-only origin master
git switch -c issue-1-contribution-workflow
```

Develop and push on the task branch, then open a pull request against `master`. Future engineering work reaches `master` through reviewed pull requests.

## Keep commits focused

Include only the linked issue's scope; leave unrelated formatting and refactors for separate work. Use short, descriptive commit messages, preferably `<type>: <summary>`, with types such as `feat`, `fix`, `test`, `docs`, `ci` or `chore`. For example: `docs: establish contribution workflow`.

Review the complete diff before committing. Keep credentials, local `.env` files, generated reports, build output and installed dependencies out of commits.

## Verify the change

Follow the setup instructions in [README.md](README.md). Select local checks appropriate to the changed behavior and record the actual commands and results:

- Backend changes: with Java 21 and Docker available, run `./mvnw --batch-mode --no-transfer-progress verify` from `backend/`.
- Frontend changes: with Node 24.21.0, run `npm ci`, `npm run lint`, `npm run typecheck:e2e`, `npm test` and `npm run build` from `frontend/`.
- Full-stack journeys or E2E changes: with Node 24.21.0 and Docker/Compose/Buildx available, run `npm run test:e2e` from `frontend/`.
- Documentation/templates: check syntax, links, paths and consistency with current project commands.

Always run `git diff --check`. Report skipped checks and their reasons, failures and known limitations; do not describe unexecuted checks as passing. Share useful failure diagnostics without credentials.

## Open and review the pull request

Use the [pull request template](.github/pull_request_template.md) and a concise title describing the outcome. Include the objective and scope, implementation summary, verification results, risks or unresolved issues, and the linked issue. Use `Closes #<number>` when merging the PR should resolve that issue.

Before merge:

- Confirm the diff satisfies the issue's acceptance criteria and contains no unrelated changes.
- Wait for Backend CI, Frontend CI and E2E CI to pass. Investigate failures and address review feedback before merging.
- Have the maintainer review the final diff, verification and any unresolved issues. For maintainer-authored work, this includes a deliberate final review before merge.

The maintainer merges the reviewed PR into `master` when the relevant CI checks pass and acceptance criteria are met. This is a contribution convention; it does not configure branch protection or repository rulesets.
