# XA Mass JVM Runtime API Server

Status: current Runtime API, application admission and Server resource assembly.

## Responsibilities and API Reference

Within the [global behavior loop](../doc/kernel/scheduling-overview.md#system-behavior-model),
Server owns `/api/v1` admission and error mapping, access-key authentication, user/credit/Project
directories, external Worker Identity, Endpoint defaults, Worker Prepare, Task/Direct Call waiting,
finite retention/history, and Spring provider, Pacer and Adapter assembly. It never selects candidates,
interprets Matching queries, acquires Worker leases, claims Items, retries execution, recovers Workers,
decides Task finality, routes physical connections or executes Worker Handlers.

`server_jvm` is an importable Java library (`XaMassServerConfiguration`) with no production `main`,
Boot auto-configuration or Scenario dependency. [Server Boot](../server_boot_jvm/README.md) owns the
sole production `main`, Boot JAR and host profiles. Scenarios consume only approved Server application
services and DTOs; business rules and Scenario-owned storage stay in their Scenario Owners.

Read by use case:
[Storage Split](#storage-split-hot-vs-cold),
[Projects and Tasks](#projects-tasks-and-results),
[Workers and Properties](#worker-preparation-and-properties),
[Delivery and Direct Calls](#delivery-and-direct-calls),
[Assembly](#assembly-boundaries),
[Configuration](#configuration), and
[Verification](#verification).

The generic frontend controller forwards `/runtime/workers`, `/runtime/tasks` and `/storage/tasks`
(including trailing slashes) to the Console entry. Project selection is frontend context; Server
independently checks caller permissions and stored ownership. Unknown API and asset paths are not forwarded.

### Runtime Shape

```text
HTTP -> AccessKeyFilter -> Controller -> Server use case -> Kernel / Matching Owner
Pacer -> bounded Server observation handoff -> application projection
Boot -> Server providers/lifecycles -> configured Groups -> dependency validation -> Adapters -> scenarios
```

Kernel owns Task/Item data, Scores and delivery resources; Matching owns Properties, query admission
and candidate pools; Server owns external Identity HASHes, immutable Endpoint/Project/User directories
and instance-local Direct Call FIFOs/waiters. Redis operations stay in their Owner providers; missing
operations fail explicitly without HTTP fallbacks or a Server scheduler.

### Storage Split (Hot vs Cold)

Every piece of platform state belongs to exactly one storage tier. Use this table as the single
decision source whenever classifying existing or newly requested state:

| Storage tier | Owns | Decision rule for new state | Read / write pattern |
| --- | --- | --- | --- |
| **Redis** (`xa_mass:<scope>:*`) | Kernel Scores, Worker/Group descriptors & bindings, Matching facts/pools/indexes, active Task/Item/Result, delivery queues, Worker identity/cooldown, user credit ledger | Put here if scheduling, Matching, Worker lifecycle, active Task execution, per-request rate/cooldown or ledger atomicity reads or writes it | Owner-local Redis/Lua operations; authoritative runtime truth |
| **`control-plane.sqlite`** (optional WAL, `user_version=2`) | Low-frequency Server control-plane directories: `UserDirectory` (`cp_roles`, `cp_users`, `cp_user_projects`) and host-declared `ProjectDirectory` (`cp_projects`, `cp_project_worker_groups`) | Put here **only** if it is low-frequency Server control-plane metadata that survives restarts independently of active execution and fits in an immutable in-memory snapshot | Loaded/bootstrapped at startup; request hot paths read **only** the immutable in-memory snapshot (zero SQLite I/O); rare admin writes commit SQLite first, then swap snapshot |
| **`task-history.sqlite`** (WAL, `user_version=1`) | Immutable archived finite Task snapshots, Items and Results (`TaskHistoryStore`) | Put here **only** for post-terminal finite Task retention/archive reads | Written by retention archival; read only by explicit `/storage/tasks` history APIs |
| **YAML / Env / Scenario files** | Process coordinates (ports, Redis URL/scope, Adapters, Endpoints, Pacer presets), Redis-scope-bound Matching/window/refill structure, startup seeds and Scenario-owned configuration | Keep here if changing it alters process wiring, Redis-scope physical structure or a business Scenario's own domain config | Parsed at startup; Scenarios own their own storage boundaries |

Hard boundaries enforced by `ServerArchitectureBoundaryTest` and `ScenarioArchitectureTest`:
- **Never touch SQLite on hot paths**: Kernel, Pacer, Matching, Delivery, Worker scheduling, Runtime View and per-request HTTP admission/authentication must never perform SQLite I/O.
- **Never dual-write or mirror Redis into SQLite**: Worker, WorkerGroup, active Task, cooldown and credit truth stay exclusively in Redis; do not cache or mirror them into `control-plane.sqlite`.
- **Keep Scenarios out of `control-plane.sqlite`**: business Scenarios consume only approved Server application services and own their own configuration/storage.
- **Explicit allowlist for new control-plane stores**: only `assembly/controlplane`, `user` and `project` may reference `ControlPlaneDatabase`, and only their dedicated `Sqlite*Store` adapters plus `task/history` may import `java.sql.*`. Adding a new control-plane table requires updating this table, `ControlPlaneDatabase` schema migration and both architecture boundary tests in the same change.

### API Reference and Response Contract

Routes, DTO fields and schema bounds live in the [committed OpenAPI snapshot](../frontend/public/reference/openapi.json).
After changing a Controller, DTO, Tag or OpenAPI description, regenerate it via
`./gradlew :server_jvm:exportOpenApiSnapshot` (contract tests detect drift).

| Reference | Route |
| --- | --- |
| Live Scalar / OpenAPI (`/api/v1/**` only; telemetry & external fonts off) | `/scalar`, `/v3/api-docs` |
| Static committed snapshot UI / JSON | `/api-reference`, `/reference/openapi.json` |
| Documentation site | `/docs/` |
| Diagnostic dictionary UI / JSON (generated by [Distribution](../distribution/server/README.md)) | `/reference/error-codes`, `/reference/platform-diagnostic-codes.json` |

Every non-exempt `/api/**` request first passes the [access-key front door](#access-key-front-door).

| HTTP status | Meaning |
| --- | --- |
| `200` | Completed, including idempotent no-op and bounded partial observation (Delivery exceptions: point poll `200`/`204`, report append `202`) |
| `400 + ApiErrorResponse` | Input, business absence, precondition or state rejection (no `422`) |
| `401 + ApiErrorResponse` | Missing, malformed or unknown access key (`WWW-Authenticate: Bearer`), returned before routing |
| `403 + ApiErrorResponse` | Authenticated caller lacks permission, Project grant or Task ownership |
| `404 + ApiErrorResponse` | Credit target absent (framework `404/405/415/500` stay coarse without XA codes) |
| `409 + ApiErrorResponse` | Credit balance/limit/idempotency conflict, or read-only/externally edited user directory |
| `429 + ApiErrorResponse` | Admission capacity exhausted (Direct Call only) |
| `503 + ApiErrorResponse` | Owner or required dependency temporarily unavailable |

- Success bodies stay direct (scalars, collections, Maps, or named DTOs); never add generic response envelopes.
- `ActionOutcome` expresses shared mutations (`applied | unchanged`); per-Item rejections remain map entries.
- `ApiErrorResponse` exposes only `{code, message, requestId}`—never Owner reasons, Redis data or internal exception text. Public error codes belong to Server; Adapter/Worker codes remain producer-local.

## Projects, Tasks and Results

For Task-ID routes, a missing Task returns `400/12002` and the wrong Task type returns `400/12008`.

| Operation | Admission | State effect | Failure boundary | Reference |
| --- | --- | --- | --- | --- |
| Project directory / lookup | Authenticated caller; startup declarations only | Return immutable Project views without Redis/SQLite I/O | No initialization or implicit `default` | [Projects](#projects-and-explicit-rpc-tasks) |
| Project Group capabilities | Declared bindings, or all registered Groups for explicit `default` | Point-read bound descriptors (`default` scans Group HASH pages) + immutable Matching enablement | Missing/corrupt descriptors stay null; not an atomic snapshot | [Projects](#projects-and-explicit-rpc-tasks) |
| Project Task list / detail | Configured Project; `limit=1..1000` | Read bounded Project/user Task batch or known Task attribution + Score | Ownership checks precede reads; missing projections stay null; corruption fails | [Task directory](#task-attribution-and-directory) |
| Task create | Business `requestId`, caller, declared Project & registered Group, valid supply | Create finite `CLOSE_WHEN_IDLE` descriptor + creation correlation | Validate before writes; unknown commit retains generated ID | [TaskCreationService](src/main/java/com/xa/mass/server/task/TaskCreationService.java) |
| RPC prepare | Caller Task ID and declared Project/Group | Create or reuse and approve a `PARK_WHEN_IDLE` Task | `400` conflict/invalid; `503` retains supplied ID | [Projects](#projects-and-explicit-rpc-tasks) |
| Approve / close | Admitted caller; finite Task; approve requires `{expectedItemCount}` > 0 | Apply shared lifecycle transition (`ActionOutcome`) | Missing/wrong-type `400`; Owner failure `503` | [TaskLifecycleService](src/main/java/com/xa/mass/server/task/TaskLifecycleService.java) |
| Ordinary Item append | Finite Task with `server.inputOwner=raw` | Persist admitted Items without releasing idle park | Query rejection is per Item; whole-request error is non-2xx | [TaskDataService](src/main/java/com/xa/mass/server/task/TaskDataService.java) |
| Item Call | Managed Task; all original queries admitted | Group-commit submit once, then consume Results within wait bound | Commit may precede response loss; timeout/capacity yields `not_observed` | [Managed Task Call](#managed-task-call) |
| Result load / take | `load`: finite or managed; `take`: raw RPC, 1..1000 IDs | `load`: non-consuming read; `take`: atomic consume | `take` response loss can lose consumed content; no resubmission | [Result observation](#result-and-item-state-observation) |
| Item states | Finite or managed Task | Read decoded Score independently of Results | Missing Items return null; Owner failure `503` | [Item Score Owner](../kernel_jvm/doc/score/task-item-score-band-scheduling.md) |
| Result export | Finite Task; observed Task Score `TERMINAL` | Stream successful content as NDJSON via bounded Owner pages | Nonterminal `400/12010`; concurrent generation `400/12009` | [Result Owner](../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md#taskitem-result-projection) |

### Access-key front door

- `AccessKeyFilter` authenticates every `/api/**` request via exactly one `Authorization: Bearer <access key>` header; missing, malformed or unknown keys return `401/18008` with `WWW-Authenticate: Bearer` before routing (so unknown `/api/**` paths also return `401`).
- Exact method/route exemptions (gateway Worker realm / loopback): `workers:prepare`, `workers:prepare-batch`, `commands:poll`, `workers/{workerId}/results`, `commands:consume`, `results:append`. Static pages, `/docs`, `/reference` and actuator sit outside `/api`.
- Each key resolves one immutable per-request `TaskCaller` snapshot (role policy + Project grants) handed to controllers via MVC argument resolver. No request can declare a user, role, grant or service identity. There is no session, password, ThreadLocal or token refresh.
- Directory updates apply to subsequent requests; admitted work retains its snapshot (including already-issued writes), and user/role/Project/key edits never rewrite Task attribution or creation correlation.

### User directory and access keys

- `UserDirectory` owns roles (`admin`, `operator`), users, Project grants and lowercase SHA-256 `accessKeySha256` hashes (keys are `xak_` + 32 random base64url bytes; plaintext is never stored or logged; a user without a hash cannot authenticate).
- **Seed & profile guard**: without `xa.mass.users.seed`, `default`/`preview` load built-in `classpath:users/development-directory.json` (`1000`/`1001`/`1002`, public keys `xak_dev_<userId>`) with a warning. `consumer` sets `xa.mass.users.require-configured-seed=true` and refuses to start with a blank seed unless `control-plane.sqlite` is already populated. Seed grants may reference a currently disabled business Project; loading rejects unknown fields, malformed/duplicate hashes, duplicate users and an admin-less directory.
- **Control-plane SQLite mode** (`xa.mass.control-plane.database-path` / `XA_MASS_CONTROL_PLANE_DATABASE`): `ControlPlaneDatabase` (`WAL`, `PRAGMA user_version=2`, atomic `v1 -> v2` migration) stores users in `cp_roles`, `cp_users`, `cp_user_projects`. An empty database imports `xa.mass.users.seed` once atomically without locking or editing the seed file; subsequent restarts load directly from SQLite even if the seed file is removed. Reads and authentication hit only the immutable in-memory snapshot (zero SQLite I/O); writes commit SQLite first, mapping storage errors to `503/18010` without mutating the snapshot.
- **File / classpath mode** (when control-plane DB path is blank): a `file:` seed holds an exclusive `<file>.lock` sidecar, verifies SHA-256 against the loaded snapshot before atomic temp-file/fsync/replace publication, and returns `409/18009` on external edits (or any write against a read-only `classpath:` seed) and `503/18010` on publication failure.

| Operation | Admission | State effect |
| --- | --- | --- |
| `GET /me`, `/users`, `/roles` | Authenticated caller | Return caller/directory views (`hasAccessKey` only, never hashes) |
| `POST /users` | `users.manage`; new `userId`; declared Projects | Persist user without an access key |
| `PUT /users/{userId}` | `users.manage`; another existing user | Persist name, role and Projects; preserve key |
| `POST /users/{userId}/access-key` | `users.manage`; another existing user | Replace key; return `{userId, accessKey}` once (`Cache-Control: no-store`) |
| `DELETE /users/{userId}/access-key` | `users.manage`; another existing user | Clear key; subsequent requests return `401` |

Rejections use `400/18001` (`403/12013` for denied permission). Self-edit is forbidden to prevent self-lockout/escalation; there is no user deletion, registration or key expiry, and `userId` must not be reassigned to another person.

### User credit accounts

- `CreditService` and `CreditAccountStore` own integer account bookkeeping keyed by stable `userId` (`credits.view` = self read; `credits.manage` = cross-user read/adjust, including self). Admission checks the current `UserDirectory` snapshot before Redis I/O; orphan accounts remain admin-readable but cannot be adjusted.
- **Endpoints** under `/api/v1/users/{userId}/credits`:
  - `GET` → `{balance, latestSequence, updatedAtMillis, userPresent}` (missing key for a directory user reads as zero without creating a key).
  - `GET /transactions` → immutable entries in descending sequence (default `limit=50`, max `100`, account-bound `nextCursor`; account and page reads are independent observations).
  - `GET /adjustments/{requestId}` → `applied | rejected | not_observed` (`not_observed` is not proof an in-flight write failed).
  - `POST /adjustments` → `{requestId (UUID, distinct from X-Request-Id), delta (nonzero safe integer; numeric strings/decimals rejected), reason (1..500 chars, stripped before fingerprinting)}`; balances stay within non-negative JavaScript safe integers.
- **Storage, receipts & atomicity**:
  - One Redis HASH `xa_mass:<scope>:server:credits:account:<userId>` holds `balance`, `sequence`, `updatedAtMillis`, `transaction:<seq>` JSON, `request:<UUID>` receipts, and `business:<source>:<taskId>` links to successful debit receipts.
  - Writes (`adjust`/`debit`) execute in one single-key Lua script that preflights metadata, head transaction, receipt/business-link invariants and arithmetic before one final `HSET`. Reads use bounded `HMGET` with Java validation (corrupt touched records fail `503`, never zero-fill or repair).
  - Receipt fingerprints cover `actorId + delta + normalizedReason` (plus business attribution for debits): same content replays the original outcome; different content or actor returns `409`. Insufficient-balance and integer-limit rejections also persist receipts (without incrementing sequence), so later credits cannot make an already-rejected `requestId` succeed; parameter/auth failures create no receipt.
  - Business debits claim `business:<source>:<taskId>` only on success so one Task identity cannot be charged twice under different requests; failed attempts do not claim it. Receipts and history have no TTL, edit or delete API.
- **Errors** (`18002..18007`): `400` invalid input, `403` denied, `404` missing target, `409` insufficient balance / safe-int limit / idempotency conflict, `503` unavailable/corrupt (reconcile by original `requestId` or retry the same content).
- **Task billing hook (`TaskCreditService` & `FiniteTaskApprovalAdmission`)**:
  - Persists immutable active and prior start snapshots in `xa_mass:<scope>:server:task-credit-start:<Task-ID digest>` before attempting the debit; the account receipt remains authoritative for financial outcome.
  - Modules declaring an `exclusiveInputOwner` (e.g. [App Checks](../scenarios/app-checks-jvm/README.md)) supply `FiniteTaskApprovalAdmission` at startup to validate and confirm payment under `OperationGuard.taskMutation` before Kernel approval. Reads never replay unknown debit/approval outcomes—explicit continuation with the original `requestId` reconciles the debit then attempts approval. Paid/unconfirmed starts block further imports; closure requires a known debit outcome and never refunds; final Task purge removes the start HASH while preserving account history.
- **Task access & stats**:
  - Role `projectScope` and `taskScope` govern Task access (`admin` sees all; `operator` sees assigned Projects and own `userId`; unattributed pure-Kernel observations are admin-only); `runtime.view` gates global Runtime Task preview. Catalogs stay discovery for any authenticated caller. Internal assembly paths may use a Project-scoped service caller (e.g. [SMS](../scenarios/sms-reception-jvm/README.md)).
  - Server stamps immutable creator and `server.inputOwner` (`raw`, `messages`, `app-checks`, `sms`); public `metadata.userId` and `server.*` fields are rejected. Raw append and Item Call write only `raw`-owned Tasks even for admin.
  - `POST /api/v1/projects/{projectId}/tasks:stats` validates Project membership and access for 1..100 Task IDs before reading bounded Item Score `{total, countsByTag}` maps (independent read-time observations).

### Projects and explicit RPC Tasks

- `ProjectDefinition(projectId, workerGroupIds, exclusiveInputOwner?)` is shared by `xa.mass.project-assembly.projects` and module-contributed Beans. `ProjectDirectory` merges host declarations with name-sorted module Beans into one immutable in-memory directory.
- **Control-plane SQLite persistence**: when `xa.mass.control-plane.database-path` is set, host-declared Projects persist in `cp_projects` and `cp_project_worker_groups`. An empty table bootstraps once from `xa.mass.project-assembly.projects` (after validating zero conflict with module Beans); subsequent restarts load host Projects from SQLite while merging module Beans live at startup. All runtime queries read only the in-memory map (zero SQLite I/O).
- **Topology invariants**: IDs must be globally unique across host and module sources (even with identical bindings); Group lists are unique and nonempty except explicit `default`, which must declare `[]` (`workerGroupSource="registered"` vs `"declared"`). Only `default`'s `/worker-groups` read scans the Kernel Group HASH (deduplicating and sorting IDs, rejecting repeated cursors and returning `503` on read failure); every write still point-checks Group registration. No implicit `default` Project or Project mutation API exists.
- **`TaskRpcPreparationService.prepare(caller, inputOwner, taskId, request)`**:
  - Creates or reuses and approves a `PARK_WHEN_IDLE` Task (caller owns `taskId`; priority `0`, `maxRetryTimes=3`). Explicit `refill` (max 100, `[]` = empty, `null` = invalid) is normalized by Matching and replaces host Group defaults; omission resolves `xa.mass.task-rpc.refill-by-worker-group`. Saved supply participates in exact descriptor comparison and cannot be updated via re-prepare.
  - Serializes per Task via `OperationGuard.taskMutation`. Matching descriptors reuse/complete unfinished approval (an admin reusing another caller's Task preserves original creator/timestamp); conflicts and terminal Tasks fail without overwrite/reopening.
  - Separate Owner commits: Kernel creation → Server directory confirmation → Kernel approval (subject to the 100 `RUNNING`+parked soft limit, which can leave a re-preparable `PRE_REVIEW` Task). Only confirmed approval returns `200 {taskId}`; `503` retains the caller's Task ID for explicit retry.

### Task attribution and directory

- `TaskUserId` strips whitespace and requires `^[1-9a-z][0-9a-z]{3,8}$` (uppercase, leading zero and other lengths are rejected, not aliased).
- Server stamps immutable `userId`, `server.projectId`, `server.inputOwner` and `server.createdAtMillis` on first external creation; stored metadata is authoritative (known-ID Project detail reads validate descriptor attribution without requiring or writing the directory).
- `TaskDirectory` opens/closes its own connection from the shared `RedisClient` and owns one ZSET per Project: `xa_mass:<scope>:server:task-directory:<Base64URL(projectId)>`, member = `taskId`, score = `Base36(userId) * 64` (max `6,499,837,226,778,560`, exact in IEEE 754 double; `0` when unattributed; low 6 bits reserved zero). No status/count/result copies, per-user keys or TTLs exist; terminal Tasks retain membership until retention purge.
- Creation confirms stored descriptor + Score before calling a single-key Lua script that registers a missing member and rejects score mismatch as corruption (`503` with known `taskId`).
- `POST /api/v1/tasks/{taskId}/directory:ensure` idempotently confirms missing index membership under `OperationGuard.taskMutation` (`applied | unchanged`) using saved truth only.
- `GET /api/v1/projects/{projectId}/tasks?limit=...&userId=...` selects `1..1000` (+1 for `truncated`) in ascending score/member order (or `[u*64, u*64+63]`), loads projections in batches of 100, and sorts by `createdAtMillis DESC, taskId ASC`. Missing projections stay null; personal queries omit unverified ownership; descriptor/index disagreement fails rather than repairs. `GET` never repairs.

### Task Admission

- Finite creation generates `task-{UUID}` (default priority `50`, retry budget `3`). Optional `refill` defaults to `[]` (`null` invalid); Matching normalizes and MAX-merges equal Group/Pool targets before immutable Kernel write.
- Supply watermarks are shared hints, not private quotas; Item queries name Matching functions independently (see [Matching Owner](../worker_matching_jvm/README.md#item-queries-and-pool-maintenance)). RPC Tasks expose no separate public approve/close/append endpoints.
- Server stamps Item creation/expiry and normalizes each Item's `WorkerQuery` against the Task's Group without consuming candidates or triggering refill; missing/invalid selectors reject that finite Item (see [TaskDataService](src/main/java/com/xa/mass/server/task/TaskDataService.java) and [TaskCallSubmissionService](src/main/java/com/xa/mass/server/task/call/TaskCallSubmissionService.java)).

### Request-correlated creation and finite import

- `TaskCreationService.createForRequest` (shared by HTTP and Scenarios) requires business `requestId` (1..128 chars, distinct from `X-Request-Id`) and atomically reserves a generated `taskId` under `xa_mass:<scope>:server:task-creation:<SHA256(projectId, caller.identityKey, requestId)>` storing `{taskId, fingerprint, inputOwner}` (`server.creationKey` links back). External identity keys are `user:<normalized-id>` (role excluded); internal service keys are `service:<project>`, so different callers never collide on `requestId`.
- Matching inputs return the same Task across restarts and can resume incomplete `PRE_REVIEW` creation; mismatched inputs conflict. Final purge conditionally removes the key by old `taskId`.
- Finite approval compares actual Item count against `{expectedItemCount}` under `OperationGuard.taskMutation` (`400/12009` on mismatch; already-approved returns `unchanged`; terminal never reopens).
- `OperationGuard.taskMutation` serializes instance-local file import, append, approve, close, export generation and retention per Task (reentrant on owning thread; concurrent thread conflicts; no distributed lock).
- `TaskDataService.importFiniteTaskItems` admits up to 100 Items to a `PRE_REVIEW` Task, skips identical complete identities without rewriting them, rejects content conflicts, and on re-import uses the stored Item to initialize a missing Score (a Score without Item data is unavailable, never reconstructed).
- `GET /api/v1/tasks/{taskId}/results:preview` returns up to 100 bounded entries (`messageId`, nullable `item`, `result`) plus `truncated`. `TaskResultsExportService` streams sequential Runtime Results/Inputs for common NDJSON and Scenario CSV export; Scenarios never access Redis or SQLite directly.

### Finite Task retention

- Server owns finite Task archival to `task-history.sqlite` and retention policy; Kernel owns the `OPEN -> FROZEN -> RELEASED -> PURGING` fences. RPC and non-finite Tasks are excluded by disposition.
- `xa.mass.task-retention` defaults: `grace=1h`, `retention=7d`, `history-retention=30d`, `interval=1h`, `database-path=./data/task-history.sqlite` (`0 <= grace < retention <= history-retention`, positive interval). All deadlines count from first termination (never renewed by reads/retries); one maintenance instance runs per scope.
- **Archival (after `grace`)**: freeze in Kernel → traverse Item and Result HASHes (`HSCAN COUNT` is a hint; SQL uniqueness catches duplicates) and batch-read Item states (≤100) → write pending SQLite rows (invisible until published; Task counts come from Owner, not row counts) → short SQLite publish transaction after confirming unchanged `FROZEN` phase → release Redis payloads (`RELEASED`). Retries reuse the scope/Task/freeze-derived `historyId` and compare content; SQL transactions never span Redis reads.
- **Runtime purge (at `retention`)**: if still within `history-retention`, complete publication is required first (failure defers that Task without blocking others); then purge Redis payloads/Scores, `TaskDirectory` membership, `TaskCreditStartStore`, conditional creation correlation, descriptor and terminal Score. At `history-retention`, missing history is no longer backfilled and expired SQLite rows (published or pending, across all scopes in the DB) are hidden immediately and deleted in bounded batches with no Redis scan.
- `POST /api/v1/tasks/{taskId}/retention:run` enforces admin permission and age checks (cannot force early deletion). After `RELEASED`, Runtime payload/preview/export reads return `400/12014`; during `PURGING`, `400/12015`. Runtime queries never fall back to SQLite.

### RPC terminal Item retention

- Opt-in branch on the retention executor (`xa.mass.task-retention`: `rpc-item-retention-enabled=false`, `rpc-input-retention=1h`, `rpc-terminal-retention=24h`; requires parent retention enabled and `0 < input < terminal`). Public `retention:run` stays finite-only.
- Applies only to parked `OPEN`/`PARK` raw-input RPC Tasks with valid Server attribution (business owners skipped). Based on the 100ms Score time coordinate in terminal tags `2..9` (a higher tag may carry an earlier time, so this is not a guarantee from first completion or last Redis write), the `24h` stage runs **before** `1h` input-only release and can reclaim Score-only identities without input; `ACTIVE` tag 1 is never reclaimed.
- Bounded pass budget: `120s`, `2,000,000` visited Item rows, `1,000` Task candidates with process-local cursors (no global Redis scan or whole-Task lock; continuously busy Tasks without parked windows have no reclamation SLO). Reclaimed Score ends same-ID dedup; late Result writes cannot recreate a missing Score and still release their Worker lease. Disabling the feature stops sweeps while preserving those writer guards.

### Finite Task history API

- `/api/v1/storage/tasks` is a read-only current-scope SQLite history API (`limit` default 20, max 100; filters: `projectId`, `creatorId`, `taskId`; ordered by `terminated_at DESC, history_id DESC`). `projectId` and ownership predicates apply before `LIMIT`; `TaskCaller` checks apply to saved descriptors (unfiltered listing and unattributed Tasks are admin-only).
- Sub-routes: `/{historyId}` (snapshot + counts), `/items` (cursor uses DB-owned per-history ordinal to avoid URL-length limits on opaque `messageId`s), `/results` (preview ≤100), `POST /results:export` (all successful raw Results as NDJSON). Errors: `400` missing/invalid, `403` denied, `503` storage failure. Scenario Storage endpoints ([App Checks](../scenarios/app-checks-jvm/README.md), [Messages](../scenarios/message-campaigns-jvm/README.md)) reuse pure interpretation over saved raw rows.
- `TaskHistoryStore` enforces WAL, foreign keys, `synchronous=FULL`, `busy_timeout=5000` and `PRAGMA user_version=1`. Reads authorize inside one SQLite snapshot so concurrent expiry cannot produce partial views; downloads prepare temp files outside HTTP transactions; no per-Task `VACUUM` runs (freed pages are reused). Operator backups must stop Runtime and copy the `.sqlite` file together with any `-wal`/`-shm` files (never delete live WAL).

### Managed Task Call

- `TaskCallSubmissionService` caches immutable Task descriptors (bounded, reset at capacity; missing Tasks are not cached) and uses a per-Task group commit: an idle call submits immediately; concurrent arrivals queue and batch up to 100 Items in arrival order without splitting a call, starting a **new batch before any call that repeats an earlier call's `messageId`** so repeated IDs preserve sequential semantics. One batch performs one Kernel submit + immediate Result take **only** for callers requesting observation (submit-only IDs are never consumed by another call in the batch); whole-batch failure fails every call in the batch as if submitted alone.
- Consuming probe threads wake immediately on Pacer's best-effort `ResultObservation` (after TASK success or Dispatch-terminal failure; fallback probe intervals `50/100/250ms`). Before each Redis take, the Registry captures active waiters and acquires their fair completion gates in registration order outside its monitor: timeout first leaves the Result in Redis for a later consumer; take first delivers obtained entries before timeout marks the remainder `not_observed`. An in-flight take may delay that waiter's timeout callback until Redis returns/fails, while unrelated admissions/wakeups/timeouts never block on it; no new take starts for an already-expired waiter.
- Accepted submission returns `200` with per-ID `succeeded | failed | not_observed` (timeout, observation saturation or shutdown mark only unresolved IDs `not_observed`, never `429`). A Dispatch-terminal failure completes its waiter without a payload; duplicate `messageId`s within one request use the latest Item and return one entry; empty takes never retract prior content. Call remains at-least-once: if submission returns `503`, retain original `messageId`s and check `results:take`.

### RPC Result consumption and idle reclamation

- `POST /api/v1/tasks/{taskId}/results:take` atomically consumes 1..1000 `messageId`s from a raw RPC Task (SMS calls the same service with its scoped service caller); `results:load` stays non-consuming.
- `xa.mass.task-rpc.result-idle-ttl` (default `7d`) is refreshed only by actual RPC Result writes and expires the whole Result HASH when idle (not a per-field TTL or Item/Task cleanup).
- A committed `take` may precede response loss; `not_observed` does not distinguish pending, consumed or expired. No ACK, replay or automatic resubmission exists.

### Result and Item State Observation

- `items:call`, `results:take` and `results:load` deduplicate IDs; failed entries carry no Worker payload/reason; late success may supersede an earlier failure. Result reads and `items:states` (decoded Score: `active`=tag 1, `terminal`=tags 2..9) are independent read-time views—Server never reconciles them.
- During `OPEN`, retained Items accept valid later observations after Task completion/closure until the finite retention freeze fence.
- Finite NDJSON export streams deduplicated successful `{messageId, opaqueResultPayload}` lines through a per-Task overlap guard (`400/12009` on concurrent generation; released after temp-file generation so response transfers may overlap) using temporary files deleted on stream close.

### Later Task Outcomes

- A Handler may retain `WorkerOutcomeReporter` after returning its execution Result (execution still finishes and releases its Worker lease immediately).
- `platform.worker.task-outcome.observed` (`WORKER -> TASK`) carries original opaque `forward` and `{tag (6..9), observedAtMillis, opaqueResultPayload?}` (extra fields rejected; Kernel verifies correlated Worker identity).
- The [Result Owner](../kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md#execution-and-observation-calls) advances Score before optional content, orders content by `(tag, observedAtMillis)`, retains content at equal timestamps, and never erases content on state-only updates. `items:call` waits only for the first observed Result and never blocks on later outcome tags.

### Task-backed Messages reads

- Scenarios call Server application services directly (`TaskCreationService`, `TaskLifecycleService`, `ProjectTaskQueryService`, `TaskDataService`, `TaskRpcCallService`) using standard DTOs—never Controllers, Redis providers, Pacer policy or mirrored contracts.
- Scenarios own no HTTP waiters, Direct Call registries or Redis clients; business tag interpretation, pricing, local caches and CSV projections stay in their respective Owners ([Messages](../scenarios/message-campaigns-jvm/README.md), [App Checks](../scenarios/app-checks-jvm/README.md), [SMS](../scenarios/sms-reception-jvm/README.md)).

## Worker Preparation and Properties

### WorkerGroup And Worker Preparation

| Operation | Admission | State effect | Failure boundary | Reference |
| --- | --- | --- | --- | --- |
| WorkerGroup directory | Admin caller; numeric cursor, `limit=1..100` | Scan Group HASH pages + startup config references, declared Projects and O(log N) Kernel state counts | Corrupt rows counted in `unreadableCount`; Owner failure `503` | [WorkerGroupManagementService](src/main/java/com/xa/mass/server/worker/group/WorkerGroupManagementService.java) |
| WorkerGroup register | Admin caller; create-only `attributes` and `eventCodes` | Create declaration (`already_registered` if identical) | Different declaration `400/15006` | [WorkerGroupRegistrationService](src/main/java/com/xa/mass/server/worker/group/WorkerGroupRegistrationService.java) |
| WorkerGroup metadata update | Admin caller; existing Group in Runtime | Replace `attributes` and `eventCodes` in Catalog (never alters Worker Handlers); startup seed skips existing Runtime descriptors on restart | Missing Group `400/15001` | [WorkerGroupManagementService](src/main/java/com/xa/mass/server/worker/group/WorkerGroupManagementService.java) |
| WorkerGroup safe delete | Admin caller; zero non-terminal/parked Tasks and zero active Workers (`activeHot + pausedActive + recovery == 0`) | `HDEL` Catalog fence → drain offline Bindings before Scores → unlink Identity & cooldowns → purge Matching (SQLite history untouched; config refs are informational warnings) | Active Task/Worker `400/15009`; partial cleanup retries idempotently | [WorkerGroupManagementService](src/main/java/com/xa/mass/server/worker/group/WorkerGroupManagementService.java) |
| Offline Worker delete | Admin caller; Worker in Group and inactive (`COLD` or offline `PAUSED`) | Remove Score atomically first → remove Binding, cooldown and Matching state (retains Identity mapping for deterministic reconnect UUID) | Active Worker `400/15009`; missing `400/15008` | [WorkerGroupManagementService](src/main/java/com/xa/mass/server/worker/group/WorkerGroupManagementService.java) |
| Prepare / Prepare batch | Existing Group; valid identity coordinates; one kind & transport per batch | Resolve Identity HASH → Kernel Binding → absent `COLD` Score | Only complete ordered response is `200`; completed stages survive failure | [WorkerPreparationService](src/main/java/com/xa/mass/server/worker/preparation/WorkerPreparationService.java) |

- Group `attributes`/`eventCodes` are directory metadata, not Matching facts or per-Worker Handler proof.
- `workerKind` defaults to `CLIENT_KEY` (requires nonblank `clientWorkerKey`); `SCENARIO_LAB` derives its key from string `labInventoryKey + labInventoryLine` (`1..100`; rejects numeric line and `clientWorkerKey`). Both kinds share one Group identity HASH via typed non-aliasing fields. Batch shape and all coordinates are validated before any side effect, followed by one Group read and default Endpoint resolution (command budget: WebSocket Prepare = 4 identity/Binding + 1 Group-local cooldown read; Polling/Socket = 4; Catalog register = 2).
- Existing same-Group Binding wins over a changed default Endpoint; Prepare only initializes absent `COLD` Scores and never overwrites active leases, PAUSE, Platform Properties or Matching facts. Non-identity fields in `workerProperties` are never persisted as Matching facts; transparent reconnect reuses in-memory identity/Endpoint without re-preparing.
- Responses tag each entry `status=prepared` (`workerId`, `transport`, `endpoint`) or `status=cooldown` (`workerId`, `cooldownUntilMillis`, no endpoint); admission Redis errors return `503`. Identity/Binding resolution is independent of cooldown admission, so denial/failure does not promise zero registration writes. See [Worker Owner](../kernel_jvm/doc/runtime-redis/worker-runtime-redis-shape.md) and [Matching](../worker_matching_jvm/README.md#item-queries-and-pool-maintenance).

### Worker connection cooldown

- Admin-only `GET/PUT /api/v1/worker-groups/{groupId}/workers/{workerId}/connection-control` manages WebSocket admission cooldown independently of scheduling `PAUSE` and Matching Pools. Only known WebSocket Workers bound to a running Adapter managed by this Runtime can be changed (Polling/Socket targets are rejected before mutation).
- Stored in `xa_mass:<scope>:server:worker-connection-control:<groupId>` (one current CAS-protected record per Worker with request ID, expected/committed revisions, absolute deadline or release, reason, actor, time). Same-request retries preserve the original deadline (even after expiry); release retains a revision to fence late old writes. Expiry does not itself reconnect/resume work, and releasing cooldown does not clear `PAUSE`.
- `PUT` serializes per Worker locally, validates bound running Adapter, persists the decision, then revokes route, pending verification and cached admission, and requests physical close outside the route lock (`409` conflict; `503` requires retaining the original request to reconcile/retry; only the latest receipt can reconcile a lost response).
- Every physical WebSocket admission rechecks Server (pending-channel identity fences late verification so old close callbacks cannot remove a resumed connection; Redis outage fails transiently as unavailable, never synthetic cooldown). Busy Workers disconnect immediately; running Handlers finish independently without cancelling side effects. Cooldown notifications are best-effort.

### Runtime Worker Properties Admission

| Operation | Admission | State effect | Failure boundary | Reference |
| --- | --- | --- | --- | --- |
| Worker Properties observation | `ADAPTER -> SYSTEM platform.adapter.worker-properties.observed`; matching `adapterId`, empty `forward`, exact `{workerId, properties}` flat string map, ≤1,000,000 UTF-8 bytes | Collapse valid snapshots per Worker to last valid input in batch; verify Binding; replace Worker-owned Map via `WorkerProperties.upsertWorkerFactsBatch`; request Score invalidation only for `APPLIED` Workers | Mixed/malformed batch fails whole request; invalid items rejected per item; Facts infrastructure error `503` (SYSTEM drops batch) | [WorkerDeliveryService](src/main/java/com/xa/mass/server/delivery/application/WorkerDeliveryService.java) |
| Platform Properties PATCH | Existing Matching facts (Prepare alone is insufficient) | Patch Platform fields only (`null` deletes); request Score invalidation on `APPLIED` | Before first facts `400/15008`; Owner failure `503/15011` | [WorkerResourceCommandService](src/main/java/com/xa/mass/server/worker/resource/WorkerResourceCommandService.java) |

- Matching owns persistent facts and index maintenance ([Matching](../worker_matching_jvm/README.md#facts-writes-and-index-maintenance)); Server never merges snapshots with old facts or writes registration fields into Properties.
- `APPLIED`/`UNCHANGED` accepts all inputs collapsed into that Worker; `UNCHANGED` retries never replay Score invalidation. Facts commit before Score invalidation ([HOT lease protocol](../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md#properties-and-network-evidence)): an execution acquisition winning in between keeps its future hold and result-release fence, and invalidation failure preserves the facts commit with an aggregate diagnostic. Concurrent batches have no timestamp/version fence (Adapter cache/baseline rules live in [Netty Adapter](../transport/netty-adapter/README.md#worker-properties-projection)).

### Runtime Views and Scheduling Control

| Operation | Admission | State effect | Failure boundary | Reference |
| --- | --- | --- | --- | --- |
| Group batch-get / Preview | Explicit bounded IDs / one bounded sample | Read directory projections | Unreadable sampled rows counted & omitted; no completeness guarantee | [RuntimeViewService](src/main/java/com/xa/mass/server/runtimeview/RuntimeViewService.java) |
| Task Preview | Bounded top Score coordinates | Compose descriptor, supply and Score Band | Missing descriptor stays null; corruption `503` | [RuntimeViewService](src/main/java/com/xa/mass/server/runtimeview/RuntimeViewService.java) |
| Worker Preview | Named Group; bounded sample (batches ≤100) | Non-atomic join of Binding + Properties (no usable facts → empty Maps) | Missing Kernel descriptor counts unreadable; Owner failure fails request | [RuntimeViewService](src/main/java/com/xa/mass/server/runtimeview/RuntimeViewService.java) |
| Scheduling observe | Bounded IDs; complete Owner result | Return decoded states + shared read time (no raw Score) | Owner failure `503`, never synthetic state | [WorkerSchedulingService](src/main/java/com/xa/mass/server/worker/scheduling/WorkerSchedulingService.java) |
| Pause / resume | Kernel-owned resource/state admission | Map `APPLIED`/`UNCHANGED` to `ActionOutcome` | `MISSING`/`CONFLICT` use `15008..15010`; Owner failure `503/15004` | [Pause/resume Owner](../kernel_jvm/doc/score/worker-hot-acquire-lease-protocol.md#pause-and-recovery) |

`hot-score-overdue` indicates only that a positive HOT Score precedes the current 10ms slot; it is weaker than Kernel's floor-aware candidate range.

## Delivery and Direct Calls

### Worker Delivery

| Operation | Admission | State effect | Failure boundary | Reference |
| --- | --- | --- | --- | --- |
| Adapter Command consume | Valid bounded consume request | Destructively consume Commands (priority: Adapter Direct FIFO → Worker Commands → up to 100 coalesced Kernel Serviceability probes as 1 snapshot Command) | Consumed Commands are not retractable | [WorkerDeliveryService](src/main/java/com/xa/mass/server/delivery/application/WorkerDeliveryService.java) |
| Adapter Report append | Homogeneous destination batch | Route to `TASK`, `SERVER` (Direct Call), `KERNEL` (Serviceability) or `SYSTEM` (Properties) | Oversize/mixed/unsupported batch fails before writes; semantic errors per-item; `TASK`/`KERNEL` write failure `503` | [WorkerDeliveryService](src/main/java/com/xa/mass/server/delivery/application/WorkerDeliveryService.java) |
| Point Command poll | Verified Catalog Binding | Best-effort `worker-poll.observed` to KERNEL, then consume | Evidence failure does not affect poll result | [Polling](#endpoint-defaults-and-polling-evidence) |
| Point Report append | Verified Binding + matching `sourceId` + admitted `TASK` event | Append to Kernel `TASK` lane | Rejected items do not enter other lanes | [WorkerDeliveryService](src/main/java/com/xa/mass/server/delivery/application/WorkerDeliveryService.java) |

- **`dst=TASK` event mapping**: `WORKER + platform.worker.command.succeeded` → `EXECUTION_SUCCESS`; `WORKER + platform.worker.command.failed` or path-matching `ADAPTER + platform.adapter.command.delivery-failed` (`{"workerId":"...","reason":"DEADLINE_EXCEEDED"}`) → `FAILURE`; `WORKER + platform.worker.task-outcome.observed` → `OUTCOME_OBSERVATION` (after [strict admission](#later-task-outcomes)). Subsets append in order (`SUCCESS`, `FAILURE`, `OUTCOME_OBSERVATION`) without cross-lane rollback; Server never decodes opaque `forward`. `diagnosticCode` is required diagnostic text only and never controls routing/acceptance.
- **Polling vs Adapter-batch identity check**: Polling point reports verify the path Worker's Binding and require its identity in `sourceId`; Adapter-batch Worker execution Reports do **not** perform a Binding lookup or compare `sourceId` against the Worker encoded in `forward` (Kernel Result Routing decodes `forward` for execution evidence without comparing `sourceId`, whereas later outcome observations explicitly check that Worker identity match).
- **`dst=KERNEL`**: forwards all `ADAPTER -> KERNEL` Serviceability Reports (snapshots, route changes, delivery expiry) without parsing payloads or touching Worker Scores ([Serviceability policy](../kernel_pacer_jvm/doc/dispatch/worker-serviceability-scheduling.md)).
- **`dst=SYSTEM`**: routes only `platform.adapter.worker-properties.observed` to Properties admission; unknown events are rejected and never wake Direct Call waiters.
- **Route verification batcher**: one Server-owned bounded queue (default `100000`, `5s` timeout) drained by one virtual thread batches Catalog Binding reads (≤100) plus per-Group cooldown checks for `transport/netty-adapter`. Queue/timeout/Owner errors fail affected verifications without retry.

### Direct Calls and Network Observation

| Operation | Admission | State effect | Failure boundary | Reference |
| --- | --- | --- | --- | --- |
| Adapter Direct Call | Configured Adapter; top-level `opaquePayload` | Enqueue on instance-local FIFO and correlate reply | Capacity exhaustion `429` | [DirectCallService](src/main/java/com/xa/mass/server/delivery/directcall/DirectCallService.java) |
| Worker Direct Call | One Adapter + Group; 1..100 bound `workerId -> payload` | Offer non-overwriting Commands via `WorkerCommandRuntime` and correlate replies | `command-slot-occupied` rejects; timeout/cancel/shutdown ends wait without retraction | [DirectCallService](src/main/java/com/xa/mass/server/delivery/directcall/DirectCallService.java) |
| Network observe | One Adapter; bounded Worker IDs | Send `platform.adapter.worker-connections.snapshot` via Adapter Direct Call | Timeout/rejection/malformed payload `503`, never synthetic `unknown` | [WorkerNetworkObservationService](src/main/java/com/xa/mass/server/runtimeview/WorkerNetworkObservationService.java) |

- Adapter and Worker Direct Call modes are mutually exclusive; Server never partitions one call across Adapters or selects substitute targets. `messageType` and opaque payloads pass through unchanged (no Server execution whitelist; unknown events return Adapter `23005` or Worker `3302` failure events).
- Completion matches exact `src=SERVER` / `dst=SERVER` command success/failure replies (never Properties/connection observations); `observed` means a result arrived, not necessarily success. Worker Direct Commands may be overwritten by authoritative `TASK` Commands before consumption; in Adapter consume responses, only Worker Command map keys are `workerId` (Adapter and Kernel Command keys are response-local and opaque). Physical queues belong to Kernel ([delivery keyspace](../kernel_jvm/doc/runtime-redis/redis-keyspace.md)).

### Endpoint Defaults and Polling Evidence

Every configured transport type requires one explicit default; multiple addressable Endpoints of a type are allowed:

```yaml
xa.mass.worker-endpoints:
  defaults:
    POLLING: system-polling
    WEBSOCKET: adapter-a
  endpoints:
    system-polling:
      transport-type: POLLING
      public-uri: http://127.0.0.1:18082
    adapter-a:
      transport-type: WEBSOCKET
      public-uri: ws://127.0.0.1:18083/api/v1/worker-delivery/websocket
```

`WorkerEndpointDirectory` binds this immutable address model with no Redis connection; unknown or wrong-type defaults fail startup, and changing a default never migrates existing bindings.

Valid point polls verify Catalog Binding and best-effort append `platform.server.worker-poll.observed` (`SERVER/system-polling -> KERNEL`) before Command consumption; empty polls count, append failures never alter poll responses, and Adapter ingress rejects forged `SERVER` observations.

## Assembly Boundaries

Production packages separate HTTP adapters (`api.v1.controller`/`contract`), domain use cases,
`runtimeview` and `assembly` (`controlplane`, `kernel`, `matching`, `metrics`, `pacer`, `redis`, `runtime`).
Only `assembly` selects providers and imports `KernelPacerRuntime` ([application assembly Owner](../kernel_pacer_jvm/doc/application-assembly.md)).

### Kernel Providers

- Controllers and services depend only on `kernel_jvm` and `worker_matching_jvm` interfaces; `assembly.redis` owns connection and health only.
- `assembly.matching` registers one `MatchingComposition` lifecycle Bean exposing `WorkerMatchingCatalog` and `WorkerProperties` (with independent destruction disabled and one lazy Redis connection closed idempotently on shutdown; see [Matching Owner](../worker_matching_jvm/README.md#fixed-resource-composition)).

### Stored Result Lease Projections

- Scenarios may contribute immutable `TaskLeaseProjection` Beans (`taskId`, `projectId`, `group`, `pool`, pure `Result -> coordinate/deadline` function). Server owns one bounded queue (`256` notices, drain `16`, per-operation bound `1000`) and Matching writer, exposing `TaskLeaseProjectionAccess` (`acceptResults` + metrics) to authorized Project service callers.
- Fed both by Pacer's non-blocking `ResultObservation` (non-destructive read) and explicit `acceptResults` calls (projected to coordinates/deadlines before enqueueing, never queueing full business payloads). Repeated facts keep the greater deadline; queue/read/write failures are lossy and counted without blocking callers. Starts before Scenario admission and stops before platform resources (5s join); empty composition starts no thread.

### Worker And Scenario Assembly

- Startup sequence: Pacer & observation consumer → create-only configured WorkerGroups (`ServerWorkerGroupInitializer` skips already-registered Runtime descriptors) → `ProjectWorkerRequirementsValidator` → Adapter ingress → Scenarios. Shutdown reverses order within bounded deadlines and never clears the Redis scope; failed initialization tears down created resources.
- Extension points for Scenarios (configuration/static bindings only; never Task services or Redis clients):
  - `ProjectDefinition` & `ProjectWorkerRequirements`: declare module Projects and per-Group required `eventCodes`/`pools`/`functions`. `ProjectWorkerRequirementsValidator` point-reads required Groups in batches of 100 and verifies required events as subsets of registered descriptors (missing dependencies fail startup before Adapter/Scenario start; no rollback of prior Group registrations).
  - `FiniteTaskApprovalAdmission`: optional approval pre-check/billing hook bound by `exclusiveInputOwner`.
  - Matching Pool/function definitions (e.g. `QualifiedCountryDefinition`, partitioned lease pools): resolved with host window bindings before `MatchingComposition` creation.
- Server never manages Worker processes (owned by [Simulator Host](../worker_simulator_jvm/README.md)); [Boot](../server_boot_jvm/README.md#platform-and-preview) owns profile/scenario wiring and [Distribution](../distribution/server/README.md) owns packaging.

### Named Window Pool And Feedback Bindings

- `xa.mass.worker-assembly.fixed-window` pairs named fixed-window Pool qualification and allocation feedback under strict host startup config:
  - `pools.<name>`: `function-name`, `timestamp-property`, `count-property`, positive `window-millis`/`max-count`, and required `feedback.message-events` (observation event is always `worker.assigned`).
  - `groups.<group>.pools.<name>: {}`: enables the Pool, consumer function and feedback together (only `window-millis`/`max-count` may be overridden). Unreferenced definitions create nothing; collisions/duplicates fail startup.
- `WorkerGroupBindings` expands one immutable startup view (independent of `ProjectDirectory`). `AssignmentWindowProjection` increments in-window, resets on newer windows, retains max timestamp and ignores older windows (no timer/lifecycle; window-length changes require a new Redis scope; multi-seed Items still emit one allocation observation).
- `GET /api/v1/runtime-view/worker-groups/bindings` returns startup Group IDs and read-only `projects` topology with zero Redis I/O; `GET /api/v1/runtime-view/worker-groups/{workerGroupId}/bindings` point-reads the Group descriptor and embeds `matching`, `fixedWindowPools` (with `feedback`) and `projects`.

### Worker Allocation Observations

- Pacer emits `KernelPacerRuntime.WorkerObservation(workerGroupId, workerIds, observedAtMillis, messageEventName, observationEventName)` after Worker acquisition and Item claim (`worker.assigned` precedes Command publication).
- `WorkerObservationConsumer` runs one consumer thread with a 256-batch queue (drain ≤16; full queue drops the incoming batch without delaying Dispatch; no handlers → no thread/queue). Exact string matching (`Group + (messageEventName, observationEventName)`) filters notices before queueing (unmatched notices do not increment enqueue/drop counts; registering the same handler instance twice in one selection fails assembly).
- One `PlatformPropertiesHandler` serves all startup-expanded `WorkerPropertyProjection` definitions: groups times per Group/Worker, chunks `WorkerProperties` reads, and for each Worker runs projections in first-appearance order within the drain—each projection receives its time list in receipt order without sorting or deduplication (`A(10), B(20), A(30)` invokes `A([10,30])` then `B([20])`, not interleaved). Later projections see preceding local patches and overwrite overlapping keys (`null` deletes; unreturned keys stay unchanged), issuing at most one `patchPlatformProperties` per Worker per drain (followed by best-effort Score invalidation on `APPLIED`). Missing facts/read/patch errors skip the affected Worker without rolling back other writes.
- Starts before Pacer, stops after Pacer before Matching teardown (5s join). Only the consumer mutates the shared running flag; `PlatformPropertiesHandler` checks it at read/Worker/write boundaries so a read returning after stop starts no new write. Optional `xa.mass.WorkerObservation` JFR events record cumulative enqueue/drop/failure counts only.

### Pacer Lifecycle

Result Convergence starts before Dispatch Convergence; Worker/Adapter assembly starts only after `RUNNING`. Shutdown reverses order within one shared deadline; failed start rolls back started components. Server delegates scheduling lifecycle to [KernelPacerRuntime](../kernel_pacer_jvm/doc/application-assembly.md#lifecycle).

## Configuration

Default application limits (host coordinates live in [Boot](../server_boot_jvm/README.md#pages-and-configuration)):

```text
Managed Task Call wait         30s default / 60s maximum
Task Call waiters              10000 maximum
Task Call observations         100000 pending waiter-message associations
Task Call Probe batch          256 due message IDs per round
DIRECT_CALL wait               3s default / 10s maximum
Adapter Direct FIFO capacity   1000 per Adapter
Pending Direct targets         10000 per Server
Serviceability probe requests  10000 per Adapter handoff
Serviceability evidence       10000 per Redis scope
```

- `xa.mass.redis` owns URL and `scope` (data boundary; see [Redis Keyspace](../kernel_jvm/doc/runtime-redis/redis-keyspace.md)).
- `xa.mass.kernel-pacer` binds `enabled`, `preset`, `shutdown-timeout` and `assignment-batch-limit` ([Pacer config](../kernel_pacer_jvm/doc/application-assembly.md#production-configuration); proof-only presets are rejected outside `test_*` scopes). Exactly one Server per Redis scope may enable Pacer (no distributed leader election).

### Outcome Names and Scope Compatibility

`TaskItemOutcomeProperties` (`xa.mass.task-item-outcomes.names`) names terminal tags `6..9` (default `6=succeeded`; `5=failed` is reserved; `2..4` remain unnamed legal terminal states). Names must be nonblank and unique. Changing outcome semantics requires a stopped exact-scope rebuild (`SCAN` + `UNLINK`).

### Run

Use [Boot startup commands](../server_boot_jvm/README.md#run) or [Runtime distribution](../distribution/server/README.md).

Health endpoints: `GET /actuator/health/liveness` (JVM process) and `GET /actuator/health/readiness` (Worker Matching, Result Convergence, Dispatch Convergence and Kernel Redis). Note: `KernelPacerHealthIndicator` currently reports `DOWN` when Pacer is disabled (e.g. API-only replicas).

### Runtime Metrics

Micrometer core meters are isolated in `com.xa.mass.server.assembly.metrics` (`ServerArchitectureBoundaryTest` enforces zero `io.micrometer` outside this package):
- Spring Actuator provides `jvm.*`, `process.*`, `tomcat.threads.*` and template-keyed `http.server.requests`.
- Passive lock-free views expose `xa.mass.api.errors`, `xa.mass.pacer.*`, `xa.mass.rpc.*`, `xa.mass.direct_call.*`, `xa.mass.worker_observation.*` and `xa.mass.lease_projection.*`.
- Tags stay strictly low-cardinality (enums, outcome names, URI templates—never IDs or payloads). `server_jvm` stays vendor-neutral; [Boot](../server_boot_jvm/README.md#pages-and-configuration) adds Prometheus export at `/actuator/prometheus`.

### Diagnostics

Opt-in `xa.mass.diagnostics.enabled=true` plus JFR settings emit fixed-stage timing/count events for Direct Call, Task Call submission/probe/waiters and servlet async completion (never Worker IDs, payloads or results; 1/64 stateless SHA-256 Task sampling; no extra Redis I/O or sampling thread). The servlet filter attaches its listener on `startAsync`, records initial execution separately, and emits at most one completion (distinct from client receipt); platform-pool metrics do not apply to virtual threads. See [Call Performance](../integrations/worker-call-performance/README.md#jfr-diagnostics).

## Verification

[TESTING](../TESTING.md#lane-index) owns lane commands and CI routing; [Proof Registry](../doc/testing/proof-registry.md) owns registered claims ([JVM Contracts](../doc/testing/proof-registry.md#jvm_contracts), [Redis Owner](../doc/testing/proof-registry.md#redis_owner), [Runtime Boundary](../doc/testing/proof-registry.md#runtime_boundary)).

| Boundary | Focused evidence |
| --- | --- |
| Architecture & hot/cold storage isolation | [ServerArchitectureBoundaryTest](src/test/java/com/xa/mass/server/ServerArchitectureBoundaryTest.java) |
| Control-plane SQLite, Users & Projects | [ControlPlaneDatabaseTest](src/test/java/com/xa/mass/server/assembly/controlplane/ControlPlaneDatabaseTest.java), [UserDirectoryTest](src/test/java/com/xa/mass/server/user/UserDirectoryTest.java), [SqliteUserDirectoryTest](src/test/java/com/xa/mass/server/user/SqliteUserDirectoryTest.java), [ProjectDirectoryTest](src/test/java/com/xa/mass/server/project/ProjectDirectoryTest.java), [SqliteProjectDirectoryTest](src/test/java/com/xa/mass/server/project/SqliteProjectDirectoryTest.java) |
| Credits & Task billing | [CreditHttpTest](src/test/java/com/xa/mass/server/credits/CreditHttpTest.java), [CreditAccountRedisIntegrationTest](src/test/java/com/xa/mass/server/credits/CreditAccountRedisIntegrationTest.java), [TaskCreditRedisIntegrationTest](src/test/java/com/xa/mass/server/credits/TaskCreditRedisIntegrationTest.java) |
| Explicit RPC preparation & Project queries | [TaskRpcPreparationServiceTest](src/test/java/com/xa/mass/server/task/call/TaskRpcPreparationServiceTest.java), [ProjectTaskQueryServiceTest](src/test/java/com/xa/mass/server/project/ProjectTaskQueryServiceTest.java) |
| WorkerGroup management & Prepare stages | [WorkerGroupManagementServiceTest](src/test/java/com/xa/mass/server/worker/group/WorkerGroupManagementServiceTest.java), [WorkerPreparationServiceTest](src/test/java/com/xa/mass/server/worker/preparation/WorkerPreparationServiceTest.java) |
| Call batching, timeout handoff & finite export | [TaskCallSubmissionBatcherTest](src/test/java/com/xa/mass/server/task/call/TaskCallSubmissionBatcherTest.java), [TaskRpcConsumptionHandoffTest](src/test/java/com/xa/mass/server/task/call/TaskRpcConsumptionHandoffTest.java), [TaskRpcHttpTimeoutTest](src/test/java/com/xa/mass/server/task/call/TaskRpcHttpTimeoutTest.java), [TaskResultsExportServiceTest](src/test/java/com/xa/mass/server/task/result/TaskResultsExportServiceTest.java) |
| Report admission & observation projections | [WorkerDeliveryServiceTest](src/test/java/com/xa/mass/server/delivery/application/WorkerDeliveryServiceTest.java), [WorkerObservationConsumerTest](src/test/java/com/xa/mass/server/assembly/pacer/WorkerObservationConsumerTest.java), [PlatformPropertiesHandlerTest](src/test/java/com/xa/mass/server/assembly/pacer/PlatformPropertiesHandlerTest.java) |
| Real-Redis runtime boundary & disconnect proofs | [RuntimeBoundaryIntegrationTest](src/test/java/com/xa/mass/server/integration/RuntimeBoundaryIntegrationTest.java), [DirectCallHttpExecutionIntegrationTest](src/test/java/com/xa/mass/server/integration/DirectCallHttpExecutionIntegrationTest.java), [OfflineTaskDeliveryRuntimeBoundaryTest](src/test/java/com/xa/mass/server/integration/OfflineTaskDeliveryRuntimeBoundaryTest.java) |

Ordinary Server unit tests disable Pacer and point Redis at an unreachable port so local Redis cannot mask accidental connections; named integration tests enable real Owners and Pacer in unique `test_*` scopes.
