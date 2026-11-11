# 0001. One repository for all components

- **Status:** Accepted
- **Date:** 2026-11-10 (recorded 2026-11-11)

## Context

Pulseboard has three deployable components (`api-core` in Java, `analytics` in Python, `web` in TypeScript), two OpenAPI contracts, two event JSON Schemas and one Compose stack. The components change together: an endpoint added to `api-core` changes the generated client of `web`, a column added by a Flyway migration changes what `analytics` reads. The project is built and reviewed by one person.

## Decision

Keep everything in one Git repository:

```text
apps/api-core  apps/web  services/analytics  contracts/  infra/  scripts/  docs/
```

- `contracts/` is the single source of truth for the HTTP and event interfaces. Each component checks itself against it: `api-core` and `analytics` export their OpenAPI document in CI and fail on a diff (`.github/workflows/api-core.yml`, `.github/workflows/analytics.yml`); `web` regenerates its types and fails when they drift (`npm run api:check`, `.github/workflows/web.yml`). Both services also validate payloads against the JSON Schemas in `contracts/events/`.
- Each component has its own workflow. A workflow runs on changes to its own directory and to `contracts/**`, so a contract change builds all three.
- There is no shared source package between components. The only coupling is the contract, the database schema (owned by Flyway in `api-core`) and the signed internal call described in [../ARCHITECTURE.md](../ARCHITECTURE.md).
- `infra/` and the root `Makefile` run the whole stack (`make up`, `make test`, `make e2e`).

## Consequences

Positive:

- A change that spans components is one commit and one review; the contract drift checks fail in the same pull request.
- One place to clone, one Compose project, one end-to-end test (`scripts/e2e/run.py`).

Negative:

- CI minutes are spent on three toolchains whenever `contracts/**` changes.
- No per-component access control or release cadence; a repository-wide permission model is all there is.
- The end-to-end check is not part of push builds (nightly only, see [../KNOWN_LIMITATIONS.md](../KNOWN_LIMITATIONS.md)).

## Alternatives considered

- **One repository per component.** Needs a published contract package or git submodule and a version bump per interface change; too much ceremony for a single maintainer.
- **A shared library for DTOs.** The components use three languages, so a shared source library does not exist; generated types from the contract do the same job.
- **A monorepo build tool (Nx, Bazel).** Path-filtered workflows are enough for three components.

## References

`contracts/`, `.github/workflows/`, `Makefile`, `infra/docker-compose.yml`, `apps/web/package.json` (`api:check`), `scripts/e2e/run.py`.
