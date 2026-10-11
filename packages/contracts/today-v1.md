# Today read-only contract v1

GET /api/v1/today accepts no query parameters or request body. Success uses the existing `{data, request_id}` envelope; `today-response.schema.json` defines data. No mutation, model inference, provider call, notification-open, schedule execution, or external data transmission occurs.

## Authority and observation

Authenticate the owner session and revalidate its revocation/security epoch in the read transaction. Derive owner and timezone from server records, never request fields. Read one coherent database snapshot and server checked_at. Session validity is revalidated as of that snapshot; a later concurrent revocation is not serialized against response delivery. Return Cache-Control: no-store and the existing security headers. Failed or invalid profile/task reads fail the request; they must not become a successful empty task list. Empty AVAILABLE means the authenticated snapshot truly has no matching rows. Calendar, briefing and device are explicitly UNAVAILABLE / NOT_IMPLEMENTED, not empty provider results.

Only task summary fields are returned. Titles are user content (maximum 200 Unicode code points, nonblank, no NUL), rendered as text. Descriptions, conversation bodies, authentication material and internal storage references are excluded. Task summaries remain private personal data despite their small size. The server serializes closed response objects with unique keys. Schema/reference checks reject duplicate keys, nonintegral values, unsafe integers and invalid Unicode scalars. The current browser transport uses JSON.parse before Today object validation, so duplicate raw response keys and original numeric spelling cannot be recovered or independently rejected by this panel; no raw-wire duplicate-key guarantee is claimed. The object validator checks closed fields, integral safe values and Unicode scalars. UUIDs are lowercase canonical strings; output instants are UTC with Z, at most microsecond precision.

## Visible technical defaults

These are deterministic product defaults, not inferred personal preferences. Open tasks mean TODO or IN_PROGRESS. Consider all authenticated-owner rows, never only an existing list page. This endpoint is an owner-wide overview; it neither assembles model context nor merges project documents.

Top tasks: at most three, ordered by (1) overdue before non-overdue, (2) HIGH, NORMAL, LOW, (3) due_at ascending with null last, (4) UUID ascending. Overdue means due_at strictly before checked_at. IN_PROGRESS does not silently outrank TODO.

Deadlines: open tasks with non-null due_at strictly before next_day_start_at, including every overdue task and remaining deadlines today. Sort by due_at ascending, priority HIGH/NORMAL/LOW, then UUID ascending. Return first 20, exact deadline_total, and deadlines_truncated exactly when total exceeds 20. Array length equals min(total,20). No lower date bound hides old overdue items. End-of-window equality is excluded. A task may appear once in each list; matching IDs must have identical summaries.

The owner timezone determines local_date. day_start_at is that date's start in the named zone; next_day_start_at is the following local date's start, using timezone rules. They are not computed by adding 24 hours. deadline_window_end_at equals next_day_start_at. checked_at must fall within the resulting half-open day. Unknown zones or an unrepresentable next day fail closed. Use the installed timezone database; do not assume identical tzdb revisions across runtimes.

## Client behavior and limits

Show checked-at time, owner timezone and the deadline window in the UI. Label each unavailable section honestly. Internal reminders must not stand in for calendar events. Clear private data on logout/page disposal/session change and reject late responses from a previous session. Failed refresh must not acquire a new checked_at or present old information as freshly verified. Preserve existing pending-write/navigation boundaries.

This is a bounded local Today slice, not complete provider-backed Today, briefing, device or release acceptance. Synthetic schema/reference checks prove neither HTTP authorization nor database isolation, query completeness, browser rendering or runtime integration; those require their own executed checks.
