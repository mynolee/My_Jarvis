# Operator web: local text and explicit mock mode

React + TypeScript + Vite browser client for the same-origin Core `/api/v1` API. The local text button uses the authenticated Core local-inference route only after validated server readiness. A separate mock button retains synthetic demonstrations; it never proves a real provider write. Server status and response text are displayed literally. No real calendar, mail, device, or training feature is implied. Actual Mac/Metal inference and browser integration have not been verified by this web implementation.

## Run

Use Node 22.12+ (verified locally with Node 24.19.0), then:

- `npm ci --ignore-scripts`
- `npm test`
- `npm run build`
- `npm run test:e2e` (Chromium `/usr/bin/chromium`, or set `CHROMIUM_PATH`)

Serve `dist/` behind the same TLS origin as Core. `/api/v1` must route to Core without rewriting Origin, and the server must emit a `__Host-` Secure/HttpOnly/SameSite cookie. Vite's development proxy preserves Origin; Core must be configured to allow the precise development origin. Production uses external CSS/JS with strict CSP; no unsafe-inline development workaround is shipped.

For deployment, set these response headers for the web shell and static assets: `Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; font-src 'self'; base-uri 'none'; form-action 'self'; object-src 'none'; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy: camera=(), microphone=(), geolocation=()`, and `Cache-Control: no-store`. The Vite preview applies these headers for tests. HTML meta CSP cannot enforce frame-ancestors, so deployment headers are required. API/SSE responses must also be no-store.

## Security boundary

- Sessions remain in HttpOnly server cookies; CSRF is held only in memory and rotated after login/reauthentication.
- No service worker, private offline caching, Web Storage, IndexedDB, third-party assets, raw HTML, Markdown link expansion, or external previews.
- Full server approval payload, expiry, version, and digest are read-only. A decision posts only the exact snapshot version/hash and APPROVE/REJECT. Expired/invalid/consumed cards cannot be approved.
- A synchronous in-flight lock blocks duplicate clicks. Failed/uncertain writes are never retried automatically. UNKNOWN pauses business writes; refresh is read-only. Review canonical server state before signing in again to initiate any new operation.
- Reload discovers conversations and latest run from authenticated server reads. Polling reconciles canonical state, while SSE acts only as an invalidation hint. EventSource scopes Last-Event-ID to the same conversation. Unknown, replayed, oversized, foreign-conversation, or flooding events close the client stream. Core must independently enforce ownership/revocation/rate limits.
- No UI-side approval grants authority. Core remains responsible for authorization, CSRF/Origin, optimistic checks, single consumption, expiry, and execution/result verification.

## Test scope

Unit tests cover text rendering, request guardrails, approval decision freezing, and event validation. Playwright tests run the compiled production app with intercepted synthetic HTTP fixtures; they are browser UI tests, **not** an end-to-end Core/PostgreSQL security certification. Cross-owner rejection, session revocation, real SSE cursor binding, provider writes, Mac/phone/PWA behavior and full CSP deployment verification require integration/operational tests. Unit/browser fixtures do not perform real model/provider calls. The production client can submit local text through Core when the server enables and validates the local runtime.

## Dependency provenance

Direct versions are exact in `package.json`; transitive versions and registry integrity digests are frozen in `package-lock.json`. Resolved from the official npm registry on 2026-10-09. Official compatibility references checked: [Vite guide](https://vite.dev/guide/), [React versions](https://react.dev/versions). Registry stable tags may be newer than cached documentation. No React Server Components framework is used.

## Local text contract

- Authenticated GET `/api/v1/local-model/status`: exact public fields `mode: LOCAL_MLX`, `model_id: local-mlx-v1`, boolean `ready`, and `status: READY | UNAVAILABLE | DISABLED`. The parser also accepts a consistent `LOADING/false` state for forward-compatible display; the initial Core implementation does not currently emit LOADING. No queue length or loading percentage is invented.
- A ready snapshot expires for sending after 10 seconds. Missing, malformed, inconsistent, stale, or inaccessible status fails closed. Refresh and 3-second polling are read-only and never invoke generation. Core independently rechecks readiness and capacity on submission.
- Explicit POST `/api/v1/conversations/{id}/local-inference` sends only `client_message_id` and `text`, limited to 8000 Unicode code points. No model selection, file path, risk, grant, owner, or approval is chosen by the client.
- POST acceptance is not completion. Existing authenticated run reads supply QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/UNKNOWN and the result. Cancellation sends the canonical run version and waits for server state; a closed HTTP/SSE stream does not imply worker termination.
- `execution_mode` distinguishes `LOCAL_INFERENCE` from `MOCK` after reload. Mock reconciliation is available only for explicitly identified MOCK/UNKNOWN runs. Local UNKNOWN pauses writes and has no replay button.
- Runtime identifiers are generic protocol aliases, not model distribution identities or paths. Do not put private model configuration, credentials, or personal prompts in `VITE_*` variables, source, or the client bundle.
- Responsive CSS is present. Real mobile access, touch/keyboard behavior on devices, background/resume, and browser history behavior require real-browser/device testing and are not claimed verified.
