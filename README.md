# RelayOps

**English** | [中文](#中文)

Reliable asynchronous task execution and result delivery for AI-assisted technical documents.

Current flow: sign in → tenant/workspace authorization → idempotent acceptance + transactional outbox → RabbitMQ → model execution → grounded result → optional local or allowlisted HTTPS delivery.

Current source candidate: **1.1.0**. It adds requirements review, document version diff, owner-configurable daily quota, allowlisted external HTTPS delivery, and signing-key rotation. **1.1.0 has not been tagged or published**; the published release is [1.0.0](https://github.com/Mitsuki-lwx/relayops-ai/releases/tag/v1.0.0). Release evidence is recorded in [release gates](docs/release-gates.md).

RelayOps provides a single executable JAR with the bundled UI, tenant/workspace and department/team authorization, reliable jobs and callbacks, history, reports, and security audit. The default fake model never calls an external provider. A compatible provider can be configured explicitly; the workbench requires an external-processing acknowledgement before submission. The deployment boundary is a single application instance and a trusted team.

Installation is documented in the [release guide](docs/release-guide.md); backup, restore, and upgrade are in [operations](docs/operations.md). A host MySQL instance can be used directly; Docker MySQL is optional for development/CI. This development host uses native MySQL 8.0 on port 3306; the previous container's 27 tables were migrated table-by-table with matching hashes, and the old volume was retained.

Optional [Langfuse task observability](docs/observability.md) exports metadata, asynchronous spans, and model usage only; document bodies are never exported. Published artifacts and later source increments are tracked separately.

## Status

| Version | Status | Notes |
| --- | --- | --- |
| 1.0.0 | Published 2026-11-02 | 120 backend tests, 80 browser tests, 15-file package, 27-table restore drill |
| 1.1.0 | Source candidate, 2026-11-04 | 151 backend tests, 98 browser tests, 17-file package; no tag or GitHub Release |

The 1.1.0 candidate package is 35,496,347 bytes with SHA256 `4cc8d57fa5814b9aa0efec06afbba6ec3dd22bc226e8fdad14b016246e617d61`. The 1.0.0 package remains 31,243,558 bytes with SHA256 `1c8feff361f3ea2669d1e2abd03efc540f8fb7b705451b1a59fe976e471d7ab4`.

## What it does

- Authenticates users with session cookies, CSRF protection, BCrypt password hashing, and an 8-hour absolute / 30-minute idle session limit.
- Separates tenants, workspaces, departments, and teams. Workspace roles are OWNER, OPERATOR, and VIEWER; organization ADMIN is a governance role and does not automatically grant document access.
- Accepts text/Markdown tasks with an idempotency key, a per-workspace daily quota, up to three delivery targets, and one of three task types: `EXTRACT`, `REQUIREMENTS_REVIEW`, or `VERSION_DIFF`.
- Commits the job, budget entry, idempotency record, outbox event, destination snapshot, and audit event in one transaction before returning `202 Accepted`.
- Publishes only an 8-field readiness envelope through RabbitMQ. Consumers re-check the database, claim the job with a lease, mark an attempt `SENT` before the external call, and never retry a call that may already have reached the provider.
- Enforces structured output and exact source evidence: every cited paragraph ID must exist and every quote must be an exact substring of that paragraph.
- Encrypts input, result, delivery payloads, and signing keys with AES-256-GCM and context-bound AAD. Online payloads expire after seven days; business metadata and audit facts are retained.
- Delivers results through signed local or allowlisted HTTPS callbacks with bounded retries, and supports a two-minute preview/confirm replay for a single `DEAD` delivery.
- Exposes workspace operations, OWNER-only security audit, full job history, Markdown report export, and optional Langfuse metadata tracing.
- Ships release/ops tooling: same-origin JAR, host-only account provisioning, logical backup/restore, package integrity checks, and CI.

## Start reading

- [Product directions](docs/product-directions.md): implemented boundaries and proposed extensions.
- [Scope](docs/spec.md): what is implemented and the completion criteria.
- [Tasks](docs/tasks.md): feature batches, dependencies, and gates.
- [Frontend design](docs/frontend-design.md): framed-sheet workbench, information hierarchy, and verification.
- [Acceptance checklist](docs/checklist.md): only items with recorded evidence are checked.
- [First slice](docs/adr/0001-first-slice.md): the initial vertical-slice trade-offs.
- [Outbox and RabbitMQ](docs/adr/0002-outbox-rabbitmq.md): confirms, redelivery, generations, and quarantine.
- [Job lifecycle](docs/adr/0003-job-lifecycle.md): original-day quota release, source retention, and command snapshots.
- [Compatible model](docs/adr/0004-compatible-model.md): frozen profiles, external-processing acknowledgement, total deadline, and heartbeat.
- [Organization isolation](docs/adr/0005-organization-isolation.md): tenant boundary, explicit grants, migration, and revocation lock order.
- [Controlled delivery](docs/adr/0006-controlled-delivery.md): frozen targets, signatures, independent retries, and receiver deduplication.
- [Delivery replay](docs/adr/0007-delivery-replay.md): two-minute preview, current authorization, version binding, and original-key recovery.
- [Online payload retention](docs/adr/0008-online-payload-retention.md): expiry settlement, terminal cleanup, bounded audit, and backup boundary.
- [Workspace operations](docs/adr/0009-workspace-operations.md): retained jobs, latest delivery runs, pending outbox, and OWNER audit.
- [Job history](docs/adr/0010-job-history.md): literal search, status filters, and time/ID cursor pagination.
- [Analysis export](docs/adr/0011-analysis-export.md): fresh authorization, plain-text Markdown report, and downloaded-copy boundary.
- [Organization audit](docs/adr/0012-organization-audit.md): current ADMIN, safe columns, and historical pagination.
- [Requirements review](docs/adr/0013-requirements-review.md): frozen purpose, grounded findings, acceptance drafts, and output cap.
- [Owner daily limit](docs/adr/0014-owner-daily-limit.md): owner-adjustable quota within a deployment hard cap.
- [Version diff](docs/adr/0015-version-diff.md): two documents, two-sided evidence, and impact analysis.
- [External delivery and key rotation](docs/adr/0016-external-delivery-and-key-rotation.md): HTTPS allowlist and signing-key rotation.
- [Requirements-review evaluation](eval/requirements-review/README.md): 20 synthetic cases, deterministic scoring, and comparison protocol.
- [Version-diff evaluation](eval/version-diff/README.md): 16 synthetic cases, three-run baselines, and manual review.
- [Same-provider model comparison](docs/model-comparison.md): fixed-sample results for deepseek and qwen3.8-flash.
- [Walkthrough](docs/walkthrough.md): 202, transactions, and the SENT boundary.
- [Backup/restore SLO and metadata inventory](docs/backup-restore-slo.md): local RTO observation, recovery steps, and 30-day read-only inventory.
- [Verification record](docs/verification.md): actual commands, results, and limitations.

## Local development

The frontend uses the taste framed-sheet workbench layout: separate views for the job queue, document creation, and organization management. Jobs can be searched/filtered within the loaded set; on mobile, the list and detail views switch with a back action. Fonts are locally hosted with their OFL license. Business APIs, authorization, and execution responsibility boundaries are unchanged.

Source development requires JDK 21, Maven 3.9+, Node 22.12+, MySQL, and RabbitMQ (RabbitMQ can run in Docker). The release package only needs Java and its runtime dependencies; see the release guide. The backend uses Spring Boot 4.1.2; the frontend uses Vue 3 and Vite 7. Exact versions are pinned in `package-lock.json`.

Run the following from the project root in PowerShell:

```powershell
./scripts/init-local.ps1
docker compose --project-name relayops -f ops/compose.yml --env-file ops/.env up -d --wait rabbitmq
./scripts/start-backend.ps1
```

Start the frontend in a new terminal:

```powershell
cd frontend
npm ci
npm run dev
```

Open <http://127.0.0.1:5173>. Demo accounts are `owner.a`, `operator.a`, `viewer.a`, and the corresponding `.b` accounts for workspace B. The password is `RELAYOPS_DEMO_PASSWORD` in the local `ops/.env`. The file is generated with an independent random value and is not committed. Accounts are seeded only when the workspace is first created; changing the configured demo password does not reset an existing database password.

The backend listens on `127.0.0.1:18080`. Native database connections are configured by `DB_URL` / `DB_USERNAME` in `ops/.env` (port 3306 on this host). To use Docker MySQL instead, start the compose `mysql` service; it maps to port 13307. The frontend uses the Vite same-origin proxy, so CSRF stays enabled and no wildcard CORS is required. Set `JAVA_HOME` to JDK 21; Maven uses the official HTTPS repository and the project-local cache.

RabbitMQ does not need a manual download: Compose pulls the digest-pinned official image and keeps a data volume. AMQP is on `127.0.0.1:5672`; the [management UI](http://127.0.0.1:15672) uses `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` from `ops/.env`, and the application uses the `relayops` vhost. The init script only fills missing configuration; it never replaces existing passwords or encryption keys. Changing `.env` does not reset the password of an existing Broker volume.

The Docker data directory on this development host is `F:/DockerData`; see [Docker storage](docs/docker-storage.md) for migration and verification records.

Stop dependencies with:

```powershell
docker compose --project-name relayops -f ops/compose.yml --env-file ops/.env stop
```

This keeps the data volumes. Stop the backend/frontend with Ctrl+C in their terminals. Do not delete the master key to reset the UI: existing ciphertext requires the original key.

## Analysis report export

In a successful job's detail view, choose **Download analysis report**. OWNER/OPERATOR can download a UTF-8 Markdown file containing the summary, key points, constraints, unknowns, and source evidence; VIEWER has no entry point. Every export re-reads the authorized result and writes a `RESULT_READ` audit record. A denied, expired, or context-switched request cannot reuse an old cached result and does not call the model or increase AI usage.

The report contains the job ID/time, frozen configuration identifier, and a citation-check statement. It does not contain the full input or secrets. Model text is stored in plain-text code fences, so HTML, images, and links cannot become active report content. A downloaded copy is controlled by the user and is not affected by later platform revocation or automatic cleanup; the page only says the download has started, so check the browser downloads list. Batch export, PDF, and Word are not supported.

## Document version diff and impact analysis

Choose **Document version diff and impact analysis** when creating a job, then paste the baseline and revised text. The model returns up to 8 `ADDED` / `REMOVED` / `MODIFIED` changes, up to 6 impacted constraints, and up to 6 impacted acceptance criteria.

- `ADDED` cites only the revised text.
- `REMOVED` cites only the baseline text.
- `MODIFIED` must cite both sides.
- Every citation still uses a paragraph ID and an exact substring.

The result and Markdown report are marked as generated suggestions requiring human confirmation. The platform does not auto-merge, modify requirements, or execute tests. Rerunning keeps the diff purpose; authorization, quota, TTL, delivery, and observability reuse the existing boundaries.

## External HTTPS delivery and signing-key rotation

Set `RELAYOPS_DELIVERY_HTTPS_HOSTS` in `ops/.env` to a comma-separated list of controlled DNS hostnames. The delivery-destination view then offers **Controlled HTTPS receiver**. Endpoints must be `https://<allowlisted-host>/relayops/events` on port 443; arbitrary URLs, IPs, private/loopback addresses, query strings, and fragments are rejected. URL shape and allowlist membership are checked at creation; the host is resolved again before sending, and loopback/private/link-local/multicast addresses are rejected. Redirects are not followed. If the allowlist is empty, the external catalog is not exposed.

OWNER can rotate a destination's signing key only when no `PENDING`, `IN_FLIGHT`, or `RETRY_WAIT` delivery exists. The old key stops working immediately, so update the receiver at the same time. Key rotation is separate from the master key used to encrypt document payloads; master-key rotation is not implemented.

## Daily quota

The operations view shows today's reserved, spent, and uncertain usage plus the policy version. A workspace OWNER can change the daily limit within `RELAYOPS_MAX_DAILY_LIMIT` (default 100). A limit cannot be lowered below today's `reserved + spent + uncertain`; historical days are not rewritten; every change writes a security audit event.

The quota is a platform-side admission guard, not a provider bill. It does not enforce provider concurrency, monetary spend, or retry cost. Company-level quotas and per-user quotas are not implemented.

## Requirements review and acceptance drafts

Switch the task purpose to **Requirements review and acceptance drafts**. In addition to a summary, the model returns up to 6 suspected gaps, ambiguities, or conflicts with clarification questions, and up to 6 Given/When/Then acceptance drafts. Every item has exact source evidence; a conflict requires at least two distinct citations; a citation for a gap only locates background and does not prove the whole document lacks a requirement. Drafts and added assumptions are explicitly marked for human confirmation. The model does not execute tests or choose a conflict value for the user.

Review jobs freeze the purpose, prompt, and result version; reruns keep the purpose. They share workspace authorization, external-processing acknowledgement, one-call execution, quota, encryption, TTL, delivery, and redacted observability with normal analysis. The fixed evaluation set and deterministic scorer live in [eval/requirements-review](eval/requirements-review/README.md); passing the set does not prove review completeness or overall accuracy. Viewing and exporting still re-authorize; VIEWER has no result or export entry. The Markdown report contains the summary, findings, clarification questions, acceptance drafts, and assumptions, but not the full input. The review output cap is independently configured as `RELAYOPS_MODEL_REVIEW_MAX_TOKENS` (default 8192) because reasoning models may count internal reasoning tokens toward output.

## Full job history

The **Search full job history** action at the bottom of the queue searches all retained jobs in the current workspace, beyond the most recent 50 loaded rows. It supports literal title/ID search, exact status / active / attention filters, 25 rows per page, and **Older jobs** pagination. Input changes require an explicit query; the page shows the applied filters. Selecting a job re-checks authorization and opens the normal detail view; history visibility does not mean the body is still within retention.

API: `GET /api/v1/workspaces/{id}/job-history` with optional `q` and `status` (`ACTIVE`, `ATTENTION`, or an exact status), and `limit` 1–100 (default 25). The next page passes the returned `nextCursor.createdAt` / `id` as `beforeCreatedAt` / `beforeId`; UUIDs are not treated as submission order. Pagination is for browsing, not message consumption or a cross-request consistent snapshot.

## Workspace operations

Open **Operations** from the sidebar to see all retained jobs, the latest run of each delivery, pending outbox rows, expired leases, and payload-cleanup status. The view refreshes every 15 seconds and pauses when the page is hidden. The pending count comes from the database, not the live RabbitMQ queue depth; the job page still counts at most 50 loaded rows.

An OWNER can also filter security audit by event type and full resource UUID, and page backwards. OPERATOR/VIEWER can view operations; only OWNER can query this audit. A company ADMIN role does not automatically grant it. Audit responses contain the event, resource, actor, and time only; they do not expose reasons, document bodies, or secrets. A company ADMIN can expand the company organization audit in organization management without gaining workspace document access. Trends, alerts, and full production monitoring are not implemented.

Read-only endpoints:

- `GET /api/v1/workspaces/{id}/operations`
- `GET /api/v1/workspaces/{id}/audit-events?limit=25`

Audit supports exact `operation`, `resourceId`, and a `beforeId` cursor; event IDs and cursors are strings, and `limit` is 1–100.

## Verification

Rule/unit tests (no database required):

```powershell
cd backend
mvn -s ../ops/maven-settings.xml -gs ../ops/maven-settings.xml test
```

Database and messaging tests require MySQL and RabbitMQ. Prepare the isolated databases and vhost once, then run the test script; configuration is loaded from the local `.env`:

```powershell
./scripts/prepare-test-env.ps1
./scripts/test-backend.ps1
```

Integration tests use the dedicated `relayops_test`, `relayops_messaging_test`, and `relayops_upgrade_test` databases and the `relayops-test` vhost. The tests reset only these explicitly named data stores; the code first verifies that the connection points at the test database/vhost and refuses to clean the demo database. Demo data and workbench quota are preserved.

With the backend running, execute the HTTP end-to-end check:

```powershell
node scripts/smoke.mjs
```

The real-Broker fault check briefly stops and then restores this project's RabbitMQ service:

```powershell
node scripts/broker-fault-smoke.mjs
```

After acceptance succeeds, the job must remain `QUEUED` with zero model calls while the Broker is stopped; after recovery it completes with exactly one `SENT`. The HTTP and fault scripts use the demo database and consume a small amount of local quota.

Frontend build and real-browser verification:

```powershell
cd frontend
npm run build
npx playwright test
```

Browser tests use the installed Microsoft Edge in headless mode by default, start Vite automatically, and require the backend. Screenshots and test output go to the Git-ignored `artifacts/`. Browser tests use the real backend and MySQL. Command tests briefly stop and restore this project's RabbitMQ; rerun tests only mark their own new job as a `UNKNOWN`/`UNCERTAIN` fixture, and the child job executes the fake model through the real consumer. Results are not fabricated.

Start the backend with `./scripts/start-backend.ps1 -FakeModel` before browser regression so the local model configuration is explicitly overridden and tests do not incur provider charges. Browser cases that create jobs check after login that every accessible workspace is Fake; otherwise they stop. The external-processing acknowledgement case replaces only configuration metadata to test the page confirmation and refresh recovery; real HTTP protocol, timeout, and heartbeat are covered by backend tests with a local provider stub. Organization cases create time-stamped departments, teams, and workspaces through the real management API, keep audit and jobs, and remove test members at the end.

While a real model is configured, run only:

```powershell
npx playwright test llm-results.spec.ts visual.spec.ts
```

`llm-results.spec.ts` uses local API response fixtures; `visual.spec.ts` logs in and reads real metadata only. Neither creates a model task.

The original submission cases default to `owner.a`; command cases default to `owner.b`. Set `$env:RELAYOPS_BROWSER_USER='owner.b'` to use one account for the whole run. Tests consume a small amount of demo quota. Do not send stress-test traffic to the demo quota configuration.

## Configure real document analysis

The local default is `RELAYOPS_MODEL_MODE=fake`. To enable a compatible endpoint, fill these variables in the ignored `ops/.env` and restart the backend:

```dotenv
RELAYOPS_MODEL_MODE=compatible
RELAYOPS_MODEL_ENDPOINT=https://your-provider.example/v1/chat/completions
RELAYOPS_MODEL_NAME=your-model-name
RELAYOPS_MODEL_API_KEY=fill_your_key_locally
RELAYOPS_MODEL_TIMEOUT_SECONDS=60
RELAYOPS_MODEL_MAX_TOKENS=4096
RELAYOPS_MODEL_REVIEW_MAX_TOKENS=8192
RELAYOPS_MODEL_TOKEN_PARAMETER=max_tokens
RELAYOPS_MODEL_JSON_MODE=true
```

The endpoint must be a complete HTTPS Chat Completions URL; the path varies by provider. The domain and model above are placeholders. Some models require `max_completion_tokens`; a compatible endpoint that does not support JSON mode can set `JSON_MODE=false` but must still return the agreed JSON. Output validation is not relaxed. The implementation is non-streaming, does not execute tool calls, and does not switch providers or retry automatically.

The workbench displays the server-side model name and requires the submitter to acknowledge external processing and potential cost; an API-created compatible job requires `acknowledgeExternalProcessing=true`. The key is never returned to the browser and is not written to jobs or audit. The configuration is shared by all workspaces and managed by the local administrator; there is no per-tenant provider-key UI.

Timeout is 1–300 seconds. Normal analysis and review/version output caps are 256–16384; review/version default to 8192 to leave room for reasoning tokens and avoid truncation at 4096. A configuration change produces a new frozen profile. The complete HTTP response body is capped at 128 KiB. The default lease is 120 seconds; during execution the worker renews every 40 seconds by token/generation, up to the call deadline plus 30 seconds. A timeout, disconnect, or unknown server failure becomes `UNKNOWN` and is never sent again automatically. Platform quota is not the provider bill.

The profile identifier binds the endpoint, model, prompt, timeout, and output parameters. After a change, an old compatible queued job fails before `SENT` with `MODEL_CONFIG_CHANGED` and releases its reservation. Old fake jobs still run; an explicit rerun uses the current configuration. Idempotent replay of an accepted request still returns the original job. Local provider stubs cover protocol and failure behavior. A real ClinePass model completed a short synthetic-document run on 2026-10-30; this does not validate other providers or actual billing.

The adapter accepts the standard Chat Completions envelope and a `data` object wrapped by `success: true`; failure/error envelopes are rejected, and internal format, truncation, and evidence checks remain strict. Empty tool metadata is allowed; actual tool requests are not executed. Output parsing logs only fixed safe reasons and structural booleans, never provider text.

The result view shows the summary, key points, constraints/requirements, and unknowns. Each evidence item expands to the source paragraph and highlights the exact quote. Content is displayed as plain text. Failure details explain the safe error code and the cost boundary of an explicit rerun; a rejected re-read clears stale results.

## Local result delivery

A workspace OWNER creates and enables a destination in **Delivery destinations**. The local catalog is fixed to `http://127.0.0.1:8091/relayops/events`; arbitrary URLs are not accepted. OWNER/OPERATOR can select up to three targets when creating a job, or none. VIEWER can read delivery metadata but cannot read signing keys.

The independent demo receiver requires Python 3 (standard library only). After creating a destination, run:

```powershell
node scripts/configure-demo-receiver.mjs
python scripts/demo-receiver.py
```

The registration script logs in as `owner.a` by default, explicitly reads the enabled destination keys for all accessible OWNER workspaces, and writes an audit record for each read. It does not create jobs or call models. Keys are written to the ignored `artifacts/receiver/keys.json` and are not printed. Re-run the script after creating a destination; the file is overwritten with the current account's target set, so set `RELAYOPS_BROWSER_USER` when switching accounts. The receiver loads keys from the file and stores results and deduplication facts in a separate SQLite database; these local artifacts are also not committed.

Acceptance freezes the target. When a result is saved successfully, the delivery and its independent outbox are created in the same database transaction. Callbacks use HMAC-SHA256 with a five-minute receive window; the body and delivery ID stay stable across retries, and the receiver atomically deduplicates by ID and body hash. See ADR 0006 for the protocol. Job detail shows delivery status, each attempt, and the earliest next retry time.

`2xx` means transport acknowledgement; `408`, `429`, `5xx`, and network-unknown outcomes retry with backoff, up to six attempts per run and one hour or the job's seven-day retention, whichever comes first. Redirects are not followed; other non-2xx outcomes stop. Disabling a destination prevents future claims, but an already-sent request may still arrive. A delivery retry never reruns the model or changes AI quota. An explicit job rerun creates a new job, reuses the source targets, and re-checks whether they are enabled.

Signature/deduplication tests: `python scripts/test-demo-receiver.py`. `delivery.spec.ts` is an independent receiver browser case and requires a Fake backend and a free receiver port. `scripts/test-browser.ps1` unifies `RELAYOPS_RECEIVER_PORT` and `--relayops.delivery.demo-port` (default 18091) to avoid Windows dynamic exclusion ports. The default browser command also includes cases that create jobs and must not run in real-model mode. This batch used isolated test databases and a dedicated vhost and did not touch demo quota.

The current source increment supports allowlisted external HTTPS destinations and OWNER signing-key rotation. Batch replay, arbitrary URLs, receiver-side business actions, and physical secure erasure are not implemented. The two systems do not share a transaction; end-to-end exactly-once is not promised.

## Single delivery replay

After a receiver is fixed, OWNER/OPERATOR can open **Result delivery** in a job, enter a reason for the latest `DEAD` delivery, and choose **Preview replay**. The preview is valid for two minutes and binds the actor, workspace, delivery version, target configuration, and payload hash; it sends no request. A new delivery run is created only after checking the target and reason and confirming the duplicate-delivery risk. Successful or in-progress deliveries cannot be replayed.

Confirmation re-checks current authorization, destination enablement, version, and the original payload's seven-day TTL. A target configuration change (including disable/re-enable), an expired plan, or a changed delivery version requires a new preview; an expired payload cannot be replayed. The new run reuses the original delivery ID and result bytes, preserves all old runs/attempts, and still allows at most six attempts and one hour. It does not rerun the model or change AI quota.

If the confirmation response is lost, the browser stores the plan and confirmation key by account/workspace/job/delivery. After refresh, reselect the job and choose **Retry with the original confirmation** to recover the same acceptance snapshot; nothing is sent automatically. Discarding the local record does not undo a server-accepted run; refresh and verify first. Other accounts cannot consume the plan, and an old key cannot bypass revocation or payload expiry.

API prefix: `/api/v1/workspaces/{workspaceId}`. `POST /deliveries/{deliveryId}/replay-plans` with `{expectedVersion,reason}`; `POST /replay-plans/{planId}/confirm` with `Idempotency-Key` and `{acknowledgeDuplicateDelivery:true}`, returning `202`. Delivery queries now include the root version, latest run number, and run history. All writes use the session and CSRF protections; they do not accept body or address replacement.

## Seven-day online payload retention

Jobs keep the `payloadExpiresAt` seven-day retention assigned at acceptance. After expiry, body reads, reruns, and replays are rejected immediately. Unsent expired jobs settle as failed and release their reservation; already-`SENT` unknown requests stay `UNKNOWN`/`UNCERTAIN` and are not called again. A late successful result cannot restore cleared payloads.

The cleanup scheduler is disabled by default. After the maintainer confirms that expired data may be deleted, set `RELAYOPS_RETENTION_ENABLED=true` in the local `ops/.env` and restart the backend. When enabled, it runs every minute by default and clears the input/result of at most 25 terminal jobs and the payload of at most 25 finished deliveries per run. Active responsibilities are settled by the recovery worker first. Cleanup, deletion time, and security audit commit together; repeated or concurrent runs do not duplicate cleanup. Setting it back to `false` and restarting stops future cleanup; cleared payloads cannot be restored through the platform.

Cleanup only nulls online ciphertext columns. It keeps IDs, hashes, foreign keys, states, model calls, quota, all delivery runs, and business audit. It does not delete signing keys or the local demo receiver's SQLite/key files, logs, or backups. Nulling a column is not secure erasure of database pages, redo/binlog, backups, or immediate disk-space reclamation; 30-day metadata cleanup and backup retention are still future work.

On 2026-10-31, with explicit user approval, this host set `RELAYOPS_RETENTION_ENABLED=true`; the first automatic run cleared 7 expired Fake inputs and 2 result bodies. All 73 job and 54 result metadata records were retained, with original states, quota, attempts, and keys unchanged; cleared job versions and deletion markers were updated as designed. The hashes of 66 unexpired inputs and 52 unexpired results matched before and after. New source installs still default to `false`.

## Cancel and explicit rerun

OWNER/OPERATOR can cancel a `QUEUED` job from the detail view; success releases the reservation for the original acceptance day. `RUNNING` jobs already claimed by a consumer and terminal jobs cannot be cancelled; the API returns `409`.

`FAILED`/`UNKNOWN` jobs offer an explicit rerun: after entering a reason and acknowledging the new-call risk, the platform creates a separate new job using the source text and current model configuration, while preserving the source state and usage. The new detail can navigate back to the source; an expired body returns `410`. Every command requires an `Idempotency-Key` and `expectedVersion`; rerun also requires `reason` and `acknowledgeDuplicateCost=true`.

If the command response is lost, the page keeps the original operation, version, and key; after refresh, choose **Retry with the original operation**. On `409`, check the current state first; to start a new intent, discard the local operation record. Discarding does not undo an already-committed server operation.

## Companies, departments, and teams

After login, an administrator opens **Organization management** from the sidebar or mobile menu: create a department or team (optionally with a parent department), add existing company members to a unit, create a new workspace in the same company, then explicitly grant the unit OPERATOR or VIEWER. The creator of a new workspace becomes OWNER; other company members do not automatically gain workspace access. Demo accounts `owner.a` / `owner.b` belong to two companies and each is ADMIN; operator/viewer accounts have no organization-management permission.

When an existing database is upgraded to V4, each old workspace is placed in its own company, preserving workspace/member/job IDs, roles, quota, ciphertext, and messages; each active OWNER becomes the company ADMIN. New installs also seed two isolated companies. The workspace selector shows company and workspace names.

Permissions combine current active company membership, direct workspace membership, and explicit organization grants; the highest role wins. A parent department is an organizational relationship only and does not inherit access. After a unit membership or grant is revoked, subsequent list, body, budget, command, and old-key replay requests re-check immediately; another direct membership or organization source may still be valid. Accepted background jobs continue; browser revocation does not discard responsibility.

Organization ADMIN does not automatically gain document access; granting a unit to a workspace also requires OWNER on that workspace. An administrator can still influence access through explicit member changes, which are audited, so this is not a non-escalatable governance/data separation. The current model is shared-database logical isolation, not separate databases or keys.

Management API under `/api/v1/organizations`: `GET` lists current companies; `GET /{tenantId}` returns units/members/space/grants visible to an admin; `POST /{tenantId}/units` and `POST /{tenantId}/workspaces` create resources; `PUT /{tenantId}/units/{unitId}/members/{userId}` sets `active`; `PUT /{tenantId}/workspaces/{workspaceId}/grants/{unitId}` sets `OPERATOR`/`VIEWER`/`NONE`. All writes use session CSRF; membership and grant PUTs are target-state updates. Creation is not retried automatically; refresh and verify if the response is lost.

Current scope excludes company self-registration, user invitations, company-wide quota, company key configuration, department permission inheritance, and private-only job visibility. Quota remains per workspace and an OWNER can adjust it within the deployment hard cap; model configuration remains server-managed.

## Layout

```text
backend/    Java API, authorization, jobs, migrations, and rule tests
frontend/   Vue workbench and browser tests
ops/        MySQL Compose, Maven settings, and local environment templates
scripts/    Bootstrap, start, integration tests, HTTP smoke, backup/restore
docs/       Scope, tasks, acceptance, decisions, and verification records
```

The schema currently has 26 tables. V1–V7 remain unchanged; V8 adds `workspace.daily_limit` and `workspace.budget_version`. V7 allows input/result/delivery ciphertext to be null and adds deletion markers and indexes; the migration itself does not clear payloads. Executed migrations must not be modified.

---

# 中文

[English](#relayops) | **中文**

面向 AI 技术文档的可靠异步任务执行与结果交付平台。

当前链路：登录 → 公司与空间权限 → 幂等受理与事务 Outbox → RabbitMQ → 模型执行 → 带原文依据的结果 → 可选本机或白名单 HTTPS 回调。

当前源码候选 **1.1.0**：新增需求评审、文档版本对比、OWNER 可调每日额度、受控外网 HTTPS 回调与签名密钥轮换。**1.1.0 尚未创建 tag 或发布附件**；已发布版本为 [1.0.0](https://github.com/Mitsuki-lwx/relayops-ai/releases/tag/v1.0.0)，发布验收见[门禁记录](docs/release-gates.md)。

RelayOps 提供同源可执行 JAR、公司/空间与部门小组授权、可靠任务与回调、历史、报告及安全审计。默认假模型不调用真实供应商；可显式配置兼容接口，工作台要求外发确认。部署边界为单应用实例与受控团队。

安装方式见[安装指南](docs/release-guide.md)，备份/恢复/升级见[运维说明](docs/operations.md)。可以直接使用本机 MySQL，无需 Docker MySQL；Docker MySQL 为可选开发/CI 依赖。本机使用原生 MySQL 8.0 / 3306，原容器 27 表逐表哈希一致后迁入，原卷保留。

可选 [Langfuse 任务观测](docs/observability.md)只导出元数据、异步阶段和模型用量，不包含正文。发布附件版本与后续源码增量分别记录。

## 版本状态

| 版本 | 状态 | 说明 |
| --- | --- | --- |
| 1.0.0 | 2026-11-02 已发布 | 120 后端测试、80 浏览器测试、15 文件包、27 表恢复演练 |
| 1.1.0 | 2026-11-04 源码候选 | 151 后端测试、98 浏览器测试、17 文件包；未创建 tag/Release |

1.1.0 候选包 35,496,347 字节，SHA256 `4cc8d57fa5814b9aa0efec06afbba6ec3dd22bc226e8fdad14b016246e617d61`。1.0.0 包保持 31,243,558 字节，SHA256 `1c8feff361f3ea2669d1e2abd03efc540f8fb7b705451b1a59fe976e471d7ab4`。

## 已实现能力

- 会话登录、CSRF、BCrypt 密码哈希、8 小时绝对会话 / 30 分钟空闲限制。
- 公司、空间、部门、小组分离；空间角色为 OWNER/OPERATOR/VIEWER；公司 ADMIN 只是治理角色，不自动获得正文权限。
- 三种任务用途：`EXTRACT`、`REQUIREMENTS_REVIEW`、`VERSION_DIFF`；幂等键、每日额度、最多三个交付目标。
- 任务、预算分录、幂等记录、Outbox、目标快照和审计在同一事务提交后才返回 `202`。
- 消息只含 8 个关联字段；消费者回数据库校验、用租约领取、调用前标 `SENT`；可能已发出的调用绝不自动重试。
- 结构化输出和精确引用：段落 ID 必须存在，引用必须是该段原文的精确子串。
- 输入、结果、交付载荷和签名密钥使用 AES-256-GCM + 上下文 AAD 加密；在线正文七天到期清理，业务元数据与审计保留。
- 本机或白名单 HTTPS 签名回调、受限退避重试、单条 DEAD 交付两分钟预览/确认补投。
- 空间运行概览、OWNER 安全审计、完整任务历史、Markdown 报告导出、可选 Langfuse 元数据观测。
- 同源 JAR、宿主机建号、逻辑备份/恢复、发布包完整性校验和 CI。

## 开始阅读

- [LLM 与组织隔离扩展建议](docs/product-directions.md)：已实现边界与后续扩展。
- [需求范围](docs/spec.md)：当前做什么，以及完成条件。
- [实施任务](docs/tasks.md)：功能批次、依赖和门禁。
- [前端设计](docs/frontend-design.md)：framed-sheet 工作台、信息层级和验证方式。
- [验收清单](docs/checklist.md)：只勾选已有证据的项。
- [首阶段决策](docs/adr/0001-first-slice.md)：先打通纵向链路的取舍。
- [消息分发决策](docs/adr/0002-outbox-rabbitmq.md)：确认、重发、代次和隔离边界。
- [取消与重跑决策](docs/adr/0003-job-lifecycle.md)：原日额度释放、来源保留与成功响应快照。
- [兼容模型决策](docs/adr/0004-compatible-model.md)：配置绑定、外发确认、总时限与心跳。
- [组织隔离决策](docs/adr/0005-organization-isolation.md)：公司边界、显式授权、迁移与撤权锁序。
- [受控回调决策](docs/adr/0006-controlled-delivery.md)：冻结目标、签名、独立重试与接收端去重。
- [单条补投决策](docs/adr/0007-delivery-replay.md)：两分钟预览、当前权限与版本绑定、原确认键恢复。
- [在线载荷清理决策](docs/adr/0008-online-payload-retention.md)：到期结算、终态清理、分批审计及备份边界。
- [空间运行概览决策](docs/adr/0009-workspace-operations.md)：全量状态、最新交付、待发布消息与 OWNER 安全审计。
- [完整任务历史决策](docs/adr/0010-job-history.md)：服务端字面搜索、状态筛选和时间/ID 分页。
- [分析报告导出决策](docs/adr/0011-analysis-export.md)：重新授权、纯文本 Markdown 报告与下载副本边界。
- [公司组织审计决策](docs/adr/0012-organization-audit.md)：当前 ADMIN、租户安全字段与历史分页。
- [需求评审决策](docs/adr/0013-requirements-review.md)：用途冻结、评审引用、验收草稿与输出上限。
- [每日额度决策](docs/adr/0014-owner-daily-limit.md)：空间 OWNER 在部署硬上限内调整额度。
- [版本对比决策](docs/adr/0015-version-diff.md)：双版本文本、双侧引用和影响分析。
- [外网回调决策](docs/adr/0016-external-delivery-and-key-rotation.md)：HTTPS 白名单与签名密钥轮换。
- [需求评审固定评测集](eval/requirements-review/README.md)：20 个合成样本、确定性评分与比较协议。
- [版本对比固定评测集](eval/version-diff/README.md)：16 个合成样本、三次基线与人工复核。
- [同供应商模型对照](docs/model-comparison.md)：deepseek 与 qwen3.8-flash 的固定样本结果。
- [原理与代码导读](docs/walkthrough.md)：202、事务和 SENT 边界。
- [备份恢复 SLO 与元数据盘点](docs/backup-restore-slo.md)：本机 RTO 观测、恢复步骤与 30 天只读盘点。
- [验证记录](docs/verification.md)：实际命令、结果及限制。

## 本地运行

前端采用 taste 的 framed-sheet 工作台布局：任务队列、文档新建、组织管理各有独立视图；任务可按已载入范围搜索或筛选，手机在列表与详情间返回。字体本地托管，包含 OFL 许可证。业务 API、权限和调用责任边界保持原有契约。

源码开发需要 JDK 21、Maven 3.9+、Node 22.12+、MySQL 及 RabbitMQ（可由 Docker 运行）。直接使用发布包只需 Java 和运行依赖，见安装指南。后端使用 Spring Boot 4.1.2，前端 Vue 3 / Vite 7，精确版本见 `package-lock.json`。

以下命令在项目根目录的 PowerShell 执行：

```powershell
./scripts/init-local.ps1
docker compose --project-name relayops -f ops/compose.yml --env-file ops/.env up -d --wait rabbitmq
./scripts/start-backend.ps1
```

新终端启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

访问 <http://127.0.0.1:5173>。演示账号为 `owner.a`、`operator.a`、`viewer.a` 和空间 B 中对应的 `.b` 账号；密码在本地 `ops/.env` 的 `RELAYOPS_DEMO_PASSWORD`。该文件由脚本生成独立随机值，不提交 Git。账号只在第一次创建空间时初始化；改变配置中的演示密码不会重置已有数据库密码。

后端监听 `127.0.0.1:18080`；原生数据库连接由 `ops/.env` 的 `DB_URL` / `DB_USERNAME` 指定，本机为 3306。若选择 Docker MySQL，额外启动 compose 的 `mysql` 服务，默认映射 13307。前端通过 Vite 同源代理访问，无需关闭 CSRF 或配置跨域通配符。`JAVA_HOME` 指向 JDK 21；Maven 使用官方 HTTPS 仓库和项目内缓存。

RabbitMQ 无需手动下载：Compose 自动拉取锁定摘要的官方镜像并保留数据卷。AMQP 为 `127.0.0.1:5672`，[管理界面](http://127.0.0.1:15672) 使用 `ops/.env` 中的 `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` 登录，应用使用 `relayops` vhost。初始化脚本只补充缺少的配置，不替换已有密码或加密密钥；已有 Broker 数据卷的密码不会随修改 `.env` 自动重置。

当前开发机 Docker 数据目录为 `F:/DockerData`，迁移与验证记录见 [Docker 存储说明](docs/docker-storage.md)。

停止依赖使用：

```powershell
docker compose --project-name relayops -f ops/compose.yml --env-file ops/.env stop
```

该命令保留数据卷。后端/前端在各自终端 Ctrl+C 停止。不要删除密钥来重置界面，旧数据需要原主密钥解密。

## 分析报告导出

在成功任务详情点击“下载分析报告”。OWNER/OPERATOR 可下载包含摘要、关键结论、约束、未确定信息与原文引用的 UTF-8 Markdown 文件；VIEWER 无入口。每次导出都会重新读取受授权的结果并留下 `RESULT_READ` 审计，拒绝/过期或切换上下文后不会使用旧缓存下载，不重新调用模型或增加 AI 用量。

文件包含任务/时间/冻结配置标识及引用校验说明，不包含输入全文或密钥。模型文字以纯文本代码块保存，HTML、图片和链接不会成为报告活动内容。下载副本由用户本地管理，不受平台后续撤权或自动清理控制；页面只提示已开始下载，实际保存请查看浏览器下载记录。目前不支持批量、PDF 或 Word 格式。

## 文档版本对比与影响分析

新建任务选择“文档版本对比与影响分析”后，分别粘贴基线与修订文本。模型输出最多 8 项新增/删除/修改、最多 6 项受影响约束和最多 6 项受影响验收草稿。

- 新增只引用修订文本。
- 删除只引用基线文本。
- 修改必须两侧都有精确引用。
- 每条引用仍使用段落 ID + 原文精确子串。

结果和 Markdown 报告标注为生成建议，待人工确认。平台不自动合并、不修改需求、不执行测试。重跑保持对比用途；权限、预算、TTL、回调和观测沿用现有边界。

## 外网 HTTPS 回调与密钥轮换

在 `ops/.env` 的 `RELAYOPS_DELIVERY_HTTPS_HOSTS` 填写受控 DNS 主机（逗号分隔），运行概览的回调目的地即可选择“受控 HTTPS 接收端”。端点必须为 `https://<白名单主机>/relayops/events`、443 端口；不接受任意 URL、IP、私网/环回、查询或片段。创建时校验 URL 形状与白名单；发送前再次解析 DNS，拒绝环回/私网/链路本地/组播地址；不跟随重定向。未配置白名单时不开放外网目录。

OWNER 可在无 `PENDING` / `IN_FLIGHT` / `RETRY_WAIT` 交付时轮换签名密钥；旧密钥立即失效，接收端需同步更新。回调签名密钥轮换与正文加密主密钥不是一回事；正文主密钥轮换仍未实现。

## 每日额度

运行概览中的“每日调用额度”显示当天预留、已使用、结果未知和策略版本。空间 OWNER 可在 `RELAYOPS_MAX_DAILY_LIMIT`（默认 100）内修改；降低额度不能低于当天已占用，历史日期不改写，变更写安全审计。

额度是平台受理次数护栏，不是供应商账单；不控制供应商金额、并发或重试成本。公司总额度、按用户额度仍未实现。

## 需求评审与验收草稿

新建任务时可将用途切换为“需求评审与验收草稿”。模型在摘要之外输出最多 6 项疑似缺漏、歧义或冲突及澄清问题，并生成最多 6 项 Given/When/Then 验收草稿。每项关联精确原文；冲突至少给出两条不同引用；缺失项的引用只用于定位背景，不证明全文没有规定。草稿和新增假设统一标注待人工确认，不自动执行测试、不替用户选择冲突值。

评审任务冻结用途、提示词和结果版本，重跑保持用途；与普通分析共用空间授权、外发确认、一次调用、预算、加密、TTL、回调与脱敏观测。固定评测集与确定性评分器见 [eval/requirements-review](eval/requirements-review/README.md)；通过评测集不代表评审完整或总体准确。查看和导出仍重新鉴权，VIEWER 没有结果或导出入口。评审 Markdown 包含摘要、问题、澄清问题、验收草稿和假设，不包含输入全文。评审输出上限独立配置为 `RELAYOPS_MODEL_REVIEW_MAX_TOKENS`（默认 8192），因为推理型模型可能把内部推理计入输出。

## 完整任务历史

任务队列底部的“查询完整任务历史”可检索当前空间全部保留任务，突破最近 50 条载入范围。按标题或任务 ID 字面搜索，按精确状态、处理中或需关注筛选，每页 25 条，“更早任务”继续向前查；修改输入后需提交查询，页面会显示已应用条件。点“查看任务”重新检查权限并进入原详情，历史可见不代表正文仍在保留期内。最近队列的本地搜索和计数范围保持不变。

历史接口为 `GET /api/v1/workspaces/{id}/job-history`，可选 `q` 及 `status`（`ACTIVE`/`ATTENTION` 或精确状态），`limit` 为 1–100、默认 25。下一页同时传返回的 `nextCursor.createdAt` / `id` 为 `beforeCreatedAt` / `beforeId`，不把 UUID 当作提交顺序。此分页用于浏览，不作为消息消费或跨请求一致快照。

## 空间运行概览

登录后选择侧栏的“运行概览”，查看当前空间全部保留任务、每个交付的最新轮次、待发布消息、失效租约及正文清理状态。概览每 15 秒刷新，页面隐藏时暂停。待发布数来自数据库，不代表 RabbitMQ 实时队列长度；任务页仍按最多 50 条已载入记录统计。

空间 OWNER 还可按事件类型和完整资源 UUID 筛选安全审计，向前翻查更早记录。OPERATOR/VIEWER 可以看概览；审计查询只允许 OWNER，公司 ADMIN 身份不自动授予此权限。审计只返回事件、资源、操作者和时间，不返回操作原因、正文或密钥。公司 ADMIN 可在组织管理中展开公司组织安全审计并筛选分页，不因此获得空间正文权限。趋势、告警和完整生产监控尚未接入。

只读接口：

- `GET /api/v1/workspaces/{id}/operations`
- `GET /api/v1/workspaces/{id}/audit-events?limit=25`

审计支持精确 `operation`、`resourceId` 及 `beforeId` 游标；事件 ID 与游标均为字符串，`limit` 范围 1–100。

## 验证

规则单测（不需要数据库）：

```powershell
cd backend
mvn -s ../ops/maven-settings.xml -gs ../ops/maven-settings.xml test
```

数据库与消息测试需先启动 MySQL 和 RabbitMQ。首次准备隔离数据库及 vhost，再运行测试脚本；配置自动从本地 `.env` 加载：

```powershell
./scripts/prepare-test-env.ps1
./scripts/test-backend.ps1
```

集成测试使用 `relayops_test`、`relayops_messaging_test` 和 `relayops_upgrade_test` 专用数据库，以及 `relayops-test` vhost。测试会重置这些明确命名的测试数据；代码先检查连接确实指向测试库和测试 vhost，拒绝清理演示库。演示数据库和工作台额度保留。

启动后端后执行接口端到端验收：

```powershell
node scripts/smoke.mjs
```

真实 Broker 故障验收会短暂停止并自动恢复本项目 RabbitMQ 服务：

```powershell
node scripts/broker-fault-smoke.mjs
```

受理成功后任务必须在 Broker 停止时保持 `QUEUED`、调用次数为零，恢复后完成且只有一次 `SENT`。HTTP 和故障脚本使用演示库，会消耗少量本地额度。

前端构建和真实浏览器验收：

```powershell
cd frontend
npm run build
npx playwright test
```

浏览器测试默认使用已安装的 Microsoft Edge（无头模式），并自动启动 Vite；后端需要已启动。截图与测试输出放在被 Git 忽略的 `artifacts/`。真实浏览器测试使用真实后端和 MySQL。命令用例短暂停止并恢复本项目 RabbitMQ；重跑用例仅将自己新建的任务设为 `UNKNOWN`/`UNCERTAIN` 测试夹具，子任务通过真实消费者执行假模型，不伪造结果。

浏览器回归应先用 `./scripts/start-backend.ps1 -FakeModel` 启动后端，显式覆盖本地模型配置，避免测试产生供应商费用。会创建任务的浏览器用例在登录后检查所有可访问空间均为 Fake，否则停止。外发确认用例只替换配置元数据以验证页面确认和刷新恢复；真实 HTTP 协议、超时与心跳在后端测试中由本机模拟供应商验证。组织用例通过真实管理接口创建带时间标记的部门、小组和空间，保留审计与任务，测试结束会移出测试组员。

真实模型运行期间，可单独执行：

```powershell
npx playwright test llm-results.spec.ts visual.spec.ts
```

前者完全使用本地 API 响应夹具，后者仅登录并读取真实元数据，不创建模型任务。

原有提交用例默认账号为 `owner.a`，命令用例默认为 `owner.b`；可在当前终端设置 `$env:RELAYOPS_BROWSER_USER='owner.b'` 统一账号。测试消耗少量演示额度。不要把压力测试流量发向演示额度配置。

## 配置真实文档分析

本地默认 `RELAYOPS_MODEL_MODE=fake`。要启用兼容接口，在忽略的 `ops/.env` 中填写下列变量，再重启后端：

```dotenv
RELAYOPS_MODEL_MODE=compatible
RELAYOPS_MODEL_ENDPOINT=https://your-provider.example/v1/chat/completions
RELAYOPS_MODEL_NAME=your-model-name
RELAYOPS_MODEL_API_KEY=fill_your_key_locally
RELAYOPS_MODEL_TIMEOUT_SECONDS=60
RELAYOPS_MODEL_MAX_TOKENS=4096
RELAYOPS_MODEL_REVIEW_MAX_TOKENS=8192
RELAYOPS_MODEL_TOKEN_PARAMETER=max_tokens
RELAYOPS_MODEL_JSON_MODE=true
```

地址必须是完整 HTTPS Chat Completions 端点，路径可能因供应商而不同。此处域名和模型只是占位符。某些模型要求 `max_completion_tokens`；不支持 JSON 模式的兼容端点可将 `JSON_MODE` 设为 `false`，但仍须返回约定 JSON，结果验证不放宽。当前实现非流式响应，不做工具调用或自动切换/重试。

工作台展示服务端模型名，并要求提交者确认文档外发和潜在费用；API 新建兼容任务要求 `acknowledgeExternalProcessing=true`。密钥不返回前端、不写任务或审计。配置为所有空间共享的本地管理员配置，尚无按租户管理供应商密钥的界面。

超时允许 1–300 秒，普通分析与评审/版本的输出 token 上限分别允许 256–16384；评审/版本默认 8192，为可能计入输出的推理留出余量，避免小样本被 4096 截断。配置变化会生成新的冻结 profile。完整 HTTP 响应体上限 128 KiB。默认租约 120 秒，执行中每 40 秒按 token/代次续租，续租最多持续调用时限加 30 秒；响应超时、断连或不明服务端故障最终进入 `UNKNOWN`，不再次调用供应商。平台调用额度不是实际供应商账单。

配置标识绑定地址、模型、提示、超时和输出参数；更改后旧兼容排队任务以 `MODEL_CONFIG_CHANGED` 在发送前失败并释放预留。旧 Fake 任务仍可处理，明确重跑使用当前配置。已受理请求的幂等重放仍返回原任务。本机模拟供应商覆盖协议与故障；2026-10-30 另以用户配置的 ClinePass 型号完成一篇自拟短文的真实链路验收，不能代表其他供应商或实际账单已验证。

兼容标准 Chat Completions 信封及 `success: true` 包裹的 `data` 对象；失败/错误信封仍拒绝，内部格式、截断和引用检查保持严格。空工具元数据允许，实际工具请求不执行。输出解析仅记录固定安全原因和结构布尔值，不记录供应商正文。

结果页完整展示摘要、关键结论、约束与要求、未确定信息；每条依据可展开对应原文并高亮精确引用。内容按纯文本呈现。失败详情解释安全错误码与明确重跑的费用边界；重新读取被拒绝时清除旧结果。

## 本机结果回调

空间 OWNER 在“回调目的地”创建并启用目标；当前受控目录固定为 `http://127.0.0.1:8091/relayops/events`，不接受任意 URL。OWNER/OPERATOR 新建任务时最多选择三个目标，也可以不选择。VIEWER 只读交付元数据，不能读取签名密钥。

准备独立演示接收端，需要 Python 3（仅标准库）。创建目的地后，在项目根目录执行：

```powershell
node scripts/configure-demo-receiver.mjs
python scripts/demo-receiver.py
```

注册脚本默认以 `owner.a` 登录，显式读取其所有可访问 OWNER 空间的启用目的地密钥，每次读取记录审计；不会创建任务或调用模型。密钥写入忽略的 `artifacts/receiver/keys.json`，不会打印。新建目的地后重新运行注册脚本；该文件会被当前账号的目标集合覆盖，切换账号需设置 `RELAYOPS_BROWSER_USER`。接收端从文件加载密钥，以独立 SQLite 保存结果与去重事实；这些本地产物也不提交 Git。

任务受理冻结目标，保存成功结果时在同一数据库事务中建立交付及独立 Outbox。回调使用 HMAC-SHA256 签名与五分钟接收窗口，正文和交付 ID 在重试间保持稳定，接收端按 ID 和正文 hash 原子去重。详细协议见 ADR 0006。任务详情提供交付状态、每次尝试和下一次最早重试时间。

`2xx` 表示传输确认；`408`、`429`、`5xx`、网络未知自动退避重试，每轮最多六次且不超过一小时或原任务七天保留期。重定向不跟随，其他非 `2xx` 停止。停用目的地阻止后续领取，已经发出的请求仍可能到达。回调重试不重跑模型、不增减 AI 额度。任务明确重跑则创建新任务，并沿用来源目标、重新检查是否启用。

验签与去重测试：`python scripts/test-demo-receiver.py`。`delivery.spec.ts` 为独立接收端浏览器用例，需 Fake 后端和未占用的接收端端口；`scripts/test-browser.ps1` 通过 `RELAYOPS_RECEIVER_PORT` 与 `--relayops.delivery.demo-port` 统一配置（默认 18091），避免 Windows 动态排除端口。默认浏览器命令还包含其他会写任务的用例，不应在真实模型模式运行。本批验收使用隔离测试数据库及专用 vhost，未动演示空间额度。

当前源码增量支持部署白名单内的外网 HTTPS 目的地和 OWNER 签名密钥轮换；批量补投、任意 URL、接收端外部业务动作、底层存储安全擦除仍未实现。两个系统无共享事务，不承诺端到端恰好一次。

## 单条交付补投

接收端修复后，OWNER/OPERATOR 在任务详情的“结果交付”中，对最新“已停止”的交付填写原因并点击“预览补投”。预览有效两分钟，绑定操作者、空间、交付版本、目标配置和载荷 hash，不发送请求。核对目标与原因并确认重复投递风险后，才会新建交付轮次。已成功或仍在处理的交付不支持人工补投。

确认时重新检查当前权限、目标启用状态、版本及原载荷七天 TTL。目标配置变化（包括停用后重新启用）、计划过期或交付版本改变时，需要重新预览；载荷过期则不能补投。新轮次沿用原 deliveryId 和结果字节，保留全部旧轮次/尝试，每轮仍最多六次及一小时；不重跑模型、不修改 AI 额度。

确认响应丢失时，浏览器按账号/空间/任务/交付保存原计划与确认键。刷新后重新选择任务，点击“沿用原确认重试”恢复同一接受快照；不会自动发送。放弃本地记录不撤销服务端已接受的轮次，应先刷新核对。其他账号不能消费此计划，旧确认键也不能绕过撤权或载荷过期。

API 以 `/api/v1/workspaces/{workspaceId}` 为前缀：`POST /deliveries/{deliveryId}/replay-plans` 携带 `{expectedVersion,reason}`；`POST /replay-plans/{planId}/confirm` 携带 `Idempotency-Key` 和 `{acknowledgeDuplicateDelivery:true}`，成功 `202`。现有任务交付查询增加根 version、最新 runNo 及 runs 历史。所有写入沿用会话与 CSRF，不接受正文或地址替换。

## 七天到期在线载荷清理

任务仍按受理时的 `payloadExpiresAt` 执行七天保留期。到期后立即拒绝正文读取、重跑和补投；未发送的过期任务结算失败并释放预留，已 `SENT` 的未知请求保留 `UNKNOWN`/`UNCERTAIN`，不重新调用模型。迟到成功结果不能重建已清正文。

清理器默认关闭。维护者确认到期数据可删除后，在本地 `ops/.env` 设置 `RELAYOPS_RETENTION_ENABLED=true` 并重启后端。启用后默认每分钟检查，每次最多清理 25 个终态 Job 的输入/结果和 25 个已结束交付的载荷；活跃责任先由恢复器结算。每项清理、删除时间和安全审计同事务，重复或并发执行不重复清理。设为 `false` 并重启可停用后续清理，已清正文无法通过平台恢复。

清理仅置空在线数据库的密文字段，保留 ID/hash、外键、状态、模型调用、额度、全部交付轮次和业务审计。不会删除签名密钥，也不会删除本地演示接收端 SQLite/密钥文件、日志或备份。SQL 置空不等于数据库页/redo/binlog/备份的安全擦除或磁盘空间立即归还；三十天元数据清理和备份保留仍需后续实现。

本机已于 2026-10-31 经用户明确批准设置 `RELAYOPS_RETENTION_ENABLED=true`，首次自动调度清除 7 条已到期 Fake 输入及其中 2 条结果正文。73 个任务及 54 个结果的元数据记录均保留，原状态、额度、尝试记录和密钥不变；已清任务版本和删除标记按设计更新。66 条未到期输入与 52 条未到期结果密文 hash 核对一致。新安装的源码默认值仍为 `false`。

## 取消与明确重跑

OWNER/OPERATOR 可在详情取消 `QUEUED` 任务，成功后释放原受理日的预留额度。消费者已领取的 `RUNNING` 任务及终态不能取消，返回 `409`。

`FAILED`/`UNKNOWN` 详情提供明确重跑：填写原因并确认新调用风险后，创建独立的新任务，使用来源文本和当前模型配置，来源状态与用量保留。新详情可跳回来源；正文过期则返回 `410`。每次命令要求 `Idempotency-Key` 和 `expectedVersion`，重跑还要求 `reason` 和 `acknowledgeDuplicateCost=true`。

命令响应丢失时，页面保留原操作、版本和键；刷新后点击“沿用原操作重试”。`409` 时先核对当前状态，若要发起新意图则先放弃本地操作记录。放弃记录不撤销已经提交的服务端操作。

## 公司、部门与小组

登录后，管理员从侧栏或手机菜单进入“组织管理”：创建部门或小组（可选择父部门）、将已有公司成员加入组织、创建同公司的新空间，再显式授予组织 OPERATOR 或 VIEWER。新空间的创建者成为 OWNER；其他公司成员不会自动获得新空间权限。默认演示账号 `owner.a` / `owner.b` 分属两家公司并各自具有 ADMIN，operator/viewer 没有组织管理权限。

已有数据库升级到 V4 时，每个旧空间归入独立公司，保留原空间/成员/任务 ID、角色、额度、密文及消息；原有效 OWNER 成为对应公司 ADMIN。新安装同样预置两个隔离公司。空间选择器显示公司及空间名称。

权限按当前有效公司成员、直接空间成员和显式组织授权计算，多来源取最高角色。父部门仅表示组织关系，不自动继承权限。撤销组织成员或授权后，后续列表、正文、预算、命令及原键重放立即重查；已有直接成员或其他组织来源仍有效。已受理后台任务继续处理，不因浏览器撤权丢弃责任。

组织治理 ADMIN 不自动取得任务正文；设置组织到空间授权还需目标空间 OWNER。管理员可以通过显式成员变更影响授权，操作留审计，因此不是不可提权的治理/数据权限分权。当前是共享库的逻辑隔离，不是独立数据库或密钥隔离。

管理 API 以 `/api/v1/organizations` 为入口：`GET` 列出当前公司；`GET /{tenantId}` 返回管理员可见的组织/成员/空间/授权；`POST /{tenantId}/units` 和 `POST /{tenantId}/workspaces` 创建资源；`PUT /{tenantId}/units/{unitId}/members/{userId}` 设置 `active`；`PUT /{tenantId}/workspaces/{workspaceId}/grants/{unitId}` 设置 `OPERATOR`/`VIEWER`/`NONE`。所有写入沿用会话 CSRF；成员及授权 PUT 是目标状态更新。创建操作不自动重试，响应丢失时先刷新核对。

当前不包含公司注册、新用户邀请、公司总额度、公司密钥配置、部门权限继承或仅本人任务可见范围；额度仍按空间，空间 OWNER 可在部署硬上限内调整，模型配置仍由服务端统一管理。

## 目录

```text
backend/    Java API、权限、任务、迁移和规则测试
frontend/   Vue 工作台与浏览器测试
ops/        MySQL Compose、Maven 配置及本地环境样例
scripts/    初始化、启动、集成测试、HTTP 冒烟、备份恢复
docs/       需求、任务、验收、决策和验证记录
```

当前业务结构共 26 张表。V1–V7 保持不变；V8 追加 `workspace.daily_limit` 和 `workspace.budget_version`。V7 允许输入/结果/回调密文为空并追加删除标记和索引；迁移本身不清正文，已执行的迁移不可修改。
