# Web local-text verification — 2026-10-10

Status: implemented and unit-tested; browser and real-model integration remain unverified. Not a production-readiness or security certification.

## Executed on final source

- `npm test`: PASS, 39 tests across 4 files, exit 0. Final run reported 3.24 seconds.
- `npm run build`: PASS, strict TypeScript checking and Vite production bundling, exit 0.
- Environment: Linux executor, Node 24.19.0, npm 11.9.0. No Mac/Metal/model weights were used by these tests.
- Existing 18 tests retained. New 21 tests cover disabled/unavailable/loading display, exact runtime parsing and unsafe fields, local text-only payload, duplicate click exclusion, authoritative queue/running/result states, backpressure without fallback, unknown submission/cancellation/reload, server-confirmed cancellation, pagehide during status/send, loss/staleness of readiness, and Unicode input bounds.
- Asynchronous DOM assertions now allow 5 seconds and await stream cleanup. Earlier loaded-executor attempts exposed timing failures; final normal and single-worker test runs passed without removing assertions.
- Auth/CSRF, same-origin credentials, no-store requests, text-only hostile strings, frozen approval snapshots, and existing SSE validation remain covered by synthetic tests.

## Blocked / not run

- `npm run test:e2e`: launcher FAILED, exit 1, before either of two existing Playwright test bodies ran. Chromium emitted `socket() failed: Operation not permitted`. Browser assertions are NOT_RUN, not PASS. No security bypass/escalation was attempted for this retry.
- Browser CSP enforcement, real cookie transport/revocation, Back/Forward and background/resume behavior, screen-reader/keyboard/touch/device behavior, phone/iOS/Android/PWA access: NOT_RUN.
- Full Core/PostgreSQL/Python/MLX/browser integration, actual Mac model loading, Korean model response, worker-stop proof, runtime queue limits, offline egress proof, real quality/performance and full LOC/SEC suites: NOT_RUN by this web worker. Separate component evidence does not imply these passed.
- No dependency changes. The previous advisory audit is historical and was not rerun for this slice.

## Contract and truthful display

- The authenticated status route supplies exactly `mode`, `model_id`, `ready`, `status`. Local sending requires a consistent READY response less than 10 seconds old, and Core independently checks acceptance.
- LOADING/false has a unit-tested display branch, but the initial Core route currently emits only READY, UNAVAILABLE, or DISABLED. No real loading-progress observation is claimed.
- QUEUED/RUNNING/UNKNOWN/CANCELLED come from canonical run reads. A cancel request or page disposal never optimistically marks a run cancelled.
- No queue count, percentage, training progress, device-access verification, or externally completed action is invented.
- Local requests contain only client message identity and text. Mock actions remain explicitly separate. Local UNKNOWN cannot trigger mock reconciliation; that action requires canonical execution_mode MOCK.
- Generic protocol aliases are not private model identities. Client source and VITE variables must not contain secrets or private model configuration.

Control touchpoints: JVS-03/04/06/09/21/22; JVL-04/06/07/08/11. These are focused UI evidence touchpoints, not full control/LOC PASS declarations.

## Frozen SHA-256 digests

| File | SHA-256 |
|---|---|
| src/App.tsx | beb85d83f92fa0567dceda855b0409be4770e88983b23a298bee8b568b08da89 |
| src/runtime.ts | aa276dbd38e6ebd27dd17e201cff2093459e9737f53cb037f525084f5626c44c |
| src/domain.ts | 6a7ff55a34c9626725bfadc4c2267fc9b0d18bb5fa60fef28707526f86e0def1 |
| src/runtime.test.tsx | 3eff78096ebf9b01cf0ce72bbbda046e06d7fb75390658d97444747dc2b09c66 |
| src/App.test.tsx | 90255a8fc9a4453c81c3ed462500177200753cfaed84e4c4d3804f6ffa9c1354 |
| tests/operator.spec.ts | 032db2b3d9aabbbe8361a41be87657c4a9c38efd02d38f7b589ce654f69289b7 |
| package-lock.json | 0257679bc9efcc6aa57ca1ea7885cd99f0684f967fce0163b73da61dc001bffb |
| dist/index.html | 4853cae430351b37fa4c4f4a1ea961b7006cd5702ab6ead753f3871c38f57ef0 |
| dist/assets/index-4lWeHUkj.css | 3cf50db815bb1739f1a8a12a57e1bc69734e1ab132c34981c0d9c539b699ff31 |
| dist/assets/index-B2-iT33Y.js | 7d0ae14a735f2b371a9c8c78aafb3d6e28c1dce3d2b8b25a92cd24bd6a1df412 |

Build output is ignored and reproducible from locked source. Any source/contract change invalidates the corresponding evidence and requires retesting. No Git publication or deployment occurred.
