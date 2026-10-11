# Restore preparation Q0

Q0 is offline preparation metadata and a supported-launch refusal. It does not
restore a backup, inspect a database, replay deletion or revocation records,
provision checkpoint trust, rotate keys, reconcile provider outcomes, or activate
anything. A successful command still reports `STILL_PAUSED`, verification
`BLOCKED`, target database `NOT_INSPECTED`, and durable isolation `UNVERIFIED`.
Exit zero means only that metadata preparation or inspection completed.

## Supported launch boundary

An operator-authorized restore target must preserve `JARVIS_RESTORE_CONTROL_DIR`
and use its staging directory as `JARVIS_RUNTIME_DIR`. The supplied stack passes
both settings to Core and intelligence. The union of ambient and env-file
settings is checked; an env-file cannot erase ambient restoration intent. Any
present control setting denies startup, even if empty, invalid, `false`, or
`normal`. A missing/corrupt control file cannot turn that setting into admission.

The fixed `restore-enrollment.json` child of a supplied runtime directory also
denies startup, regardless of its contents or type. Direct intelligence startup
additionally checks the parent of its supplied `--ledger` path. Symlinked ledger
files or path components, special ledger files and hard-linked ledger files are
refused before runtime imports, including for ordinary launches. The accepted
ledger reference is not subsequently resolved through a different file alias. The guard never
follows an anchor's stored control path to grant launch permission.

Refusal precedes:

- Core main's Spring initialization, database migration, keys and scheduled work
- BootstrapAdmin's TTY, JDBC connection and token creation
- Intelligence main's runtime/crypto imports, keys, ledger, model and HTTP server
- Stack start/doctor and internal `_doctor` configuration/model work

Stack stop/status remain available. Existing unenrolled startup remains unchanged
when neither a restore control setting nor a supported staging anchor is present.
Unsupported paths, insecure/symlinked runtime references, and inspection errors
are refused where a supplied staging reference cannot be safely checked.

**Removing/omitting enrollment settings and supported staging references is an
unsupported restore bypass.** Q0 cannot discover a database rollback by looking
inside a database. It does not protect old binaries, alternate direct application
factories, manual database clients, deleted/replaced control state, or already
running processes. Preparation never stops a process. This is not universal
rollback detection, proof of production isolation, or a completed restore drill.

## Offline interface

The standard-library CLI is `python3 scripts/restore_quarantine.py` with exactly
one operation:

- `prepare`: `--control-dir`, `--staging-dir`, `--deployment-id`, `--target-id`,
  and `--preparation-id` are required.
- `inspect`: `--control-dir`, `--deployment-id`, `--target-id`, and
  `--preparation-id` are required. Staging is recovered only from validated,
  identity-matching enrollment metadata.

All three IDs are caller-supplied canonical lowercase UUIDs. Paths are canonical
absolute paths. Directories must already exist, belong to the invoking user,
use mode 0700, be outside the checkout, and neither equal nor contain each other.
Staging must start empty. Control must be a separate private restore-control
directory outside every prospective backup/restore root. The code checks path
separation from staging and checkout; actual storage placement and exclusion from
backup restoration remain deployment assumptions, not facts certified by Q0.
No operational enrollment is implied by running the synthetic tests.

No backup payload, database URL, credential, key path, SQL, checkpoint, approval,
release flag, model location, or content digest is accepted. The CLI opens only
its small metadata files and prints one bounded JSON object with closed status
and error codes. It never prints local paths or exception text.

The closed receipt contains revision, the three IDs, the fixed policy fields,
and these fixed blockers:

- AUTHORITY_INVALIDATION_NOT_PERFORMED
- BACKUP_IMPORT_NOT_IMPLEMENTED
- CHECKPOINT_TRUST_AND_FRESHNESS_UNAVAILABLE
- DELETION_REPLAY_NOT_PERFORMED
- EXTERNAL_OUTCOMES_NOT_RECONCILED
- STORAGE_VERIFICATION_NOT_PERFORMED

No command can promote a receipt, release quarantine, clear enrollment or resume
execution. The ordinary HTTP resume route cannot be reached through an enrolled
supported launch because HTTP is never initialized.

## Filesystem protocol and retry

This interface requires POSIX descriptor-relative no-follow traversal, advisory
file locking and successful file/directory synchronization. There is no weaker
fallback when those operations are unsupported. Owner compromise, root access,
and rollback of the external control directory are outside this boundary.

A fixed `.restore-quarantine.lock` in control is never removed or replaced. Under
that lock preparation exclusively creates immutable mode-0600 records in order:

1. Control `restore-enrollment.json`, including IDs and canonical path bindings
2. Control `quarantine-fence.json`, with the same IDs and launch permission false
3. Staging `restore-enrollment.json`, binding the staging target to control

Each phase requires file fsync, directory fsync, strict bounded readback and
identity checks. Unknown fields/versions, duplicate keys, unsafe permissions,
wrong owners, links, special files, changed inodes, unexpected files, overlapping
paths and conflicting IDs remain blocked. Exclusive creation of the staging
anchor resolves competing control directories without overwriting a winner;
a losing attempt retains any external denying records it already wrote.

Retry uses the same preparation ID, deployment ID, target ID and path bindings.
A complete exact retry returns the same policy receipt. Missing later phases can
be completed after matching earlier records. Torn or malformed files are never
repaired on an identity guess, overwritten or deleted; they remain blocked for
separately designed operator recovery. No durable-success claim follows a failed
or uncertain fsync. A successful retry synchronizes and rereads existing records.
The staging directory remains data-empty except for its enrollment anchor.
This checks filesystem staging emptiness, never PostgreSQL emptiness.

## Verification scope

Synthetic filesystem tests exercise receipts, parsing, strict metadata,
permissions/links, exact retry, interruption/failure boundaries, locking and
competing preparations. Guard tests check early refusal using inert dependency
sentinels; they do not start an operational service. Java guard tests are part of
the normal Core CI suite. Full project checks, actual PostgreSQL/native-model
execution, backup recovery, storage durability and deployment isolation are
separate evidence categories. Passing a focused test suite does not prove them.

A future restore implementation still needs independently current and complete
checkpoint authority, old-authority invalidation, generation-aware memory
replay, conversation deletion provenance, and reconciliation of Core and Python
replay/cache/nonce domains. Snapshot-queued work is not proof that no external
effect occurred after backup creation. Logical redaction cannot make retained
backup, WAL, export or provider copies cryptographically unrecoverable while old
keys or copies remain. None of those deferred requirements is implemented here.
