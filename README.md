# My Jarvis

A development-stage personal-assistant service with a Java 21 / Spring Core,
Python inference boundary, React interface and strict shared contracts.
Core owns permissions and execution; model output is proposal data only.

## Verification

Use Java 21, the checked-in Gradle Wrapper, Python 3.12, uv and Node. From the
repository root, run `scripts/check.sh`. It runs synthetic component checks,
not a release gate or proof of native inference. Independent CI jobs check Python
lock freshness and audit Python/npm dependency identifiers. Audit failures block
those jobs; they never count as a clean scan. Separate secret scanning, SAST and
Java dependency review remain required. Gradle dependencies must be
available from the configured repositories; a dependency-resolution failure is
not a passing Core test. Never regenerate dependency locks as a normal test step.

No model download, installation, training or external AI fallback runs in CI.
No personal settings, prompts, private model manifests or dependency snapshots
belong in this repository. Actual configuration belongs in an ignored `local.env`
file (owner-only permissions) and private files outside the source tree. Do not
commit `.env` variants, even example files containing real values.

## Local backend lifecycle

`scripts/local_stack.py setup` lists the required environment variable names and
performs no installation. Populate a private `local.env` separately. This file is
plain `NAME=value` data, not a shell script: no `export`, quoting, interpolation,
command substitution or unknown keys. Secret values are never printed by the
launcher. Certificate/key/configuration files must be private, owner-only regular
files outside this repository. No certificate or signing key is generated here.

Prerequisites are an already provisioned local PostgreSQL database, private keys
and certificates, a reviewed/pinned model artifact, its exact installed Python
environment and a built Core jar. Build the frontend with its locked package
manager, then build `:apps:core:packageWeb :apps:core:bootJar`. If static web
packaging or the required security checks are unavailable, do not claim a usable
full application.

The lifecycle commands accept `--env-file /absolute/path/to/local.env`:

- `doctor`: checks static artifact/runtime/build inputs. It does not generate text.
- `start`: remains in the foreground, starts one TLS inference service, waits for
  matching authenticated readiness, then starts Core. It does not auto-restart.
- `status`: reads the private supervisor socket. RUNNING means managed processes
  are alive; it does not establish real inference, integration or release approval.
- `stop`: requests shutdown. STOPPING is only acknowledgement. The foreground
  supervisor reports STOPPED after verifying its process groups have exited.

The backend binds only loopback. The inference URL is `https://localhost:9443`;
Core uses `https://localhost:8443`. Certificates must cover those names. Remote
phone exposure, reverse proxies and certificate installation are not configured
by this launcher. Runtime state uses a separate owner-only directory outside the
repository. A missing socket is uncertain, not proof of termination. Unconfirmed
termination leaves a blocking marker requiring operator investigation before
another start. Never delete it merely to bypass a failed shutdown.

The dedicated model worker is inference-only. The service defaults to a total
4096-token context, 512-token output ceiling, one active generation, two waiting
requests and a 60-second generation deadline. Actual template/tokenizer accounting,
artifact checks and worker termination are enforced in the Python boundary.

Static checks, synthetic tests, native model output, fresh-process reload, resource
measurements and full application integration are separate evidence categories.
Mac/Metal inference is NOT_RUN until tested on the actual supported hardware.
