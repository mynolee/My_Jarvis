# Local mock development

M0 is a mock-only security prototype. It does not connect a model provider, Google, a personal computer, or a training worker. Production activation is intentionally unsupported.

## Prerequisites

- Java 21 JDK (a JRE alone cannot compile); official Temurin 21.0.12.1+1 was used for initial verification.
- Python 3.12 and uv 0.12.23.
- Node 24 and npm; exact application dependencies are in the committed lockfile.
- A local PostgreSQL 17 database for interactive Core use. Tests use the embedded PostgreSQL dependency declared by Core; consult its test results for the exact tested database version.

Run the reproducible checks from the repository root:

    scripts/check.sh

Checks download only locked build dependencies. Initial dependency setup requires network access. Subsequent offline checks require a complete local cache. The build does not download model weights or call external AI services.

The Gradle wrapper pins both its JAR checksum (verified by `scripts/verify_wrapper.py`) and distribution checksum. Python uses frozen hash-bearing uv locks. npm uses `npm ci` with the integrity-bearing package lock. No container configuration is supplied until a selected image digest has been verified.

## Interactive services

Consult each application's README for required environment variables and startup commands. Keep local credentials outside the repository. Bind development listeners to loopback, and use same-origin routing for the browser. Do not relax cookie/Origin/CSRF checks to make a remote dev endpoint work.

Use disposable synthetic data only. Real account connection, deployment, model activation, or personal file ingestion requires a separately reviewed stage.

## Evidence limits

`scripts/check.sh` is a development test aggregator, not a release acceptance gate. Publication inventory checks do not replace a dedicated secret scanner, SAST, dependency audit, cross-service end-to-end tests, or hardware tests. Unexecuted checks must remain NOT_RUN.
