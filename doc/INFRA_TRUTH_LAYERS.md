# Infra Truth Layers

Status: current cross-module boundary contract.

Use this file when deciding where a new piece of truth belongs. This is the
high-density decision table for `control-plane storage`, `runtime state`, and
`trace / audit stream`.

Read with:

- [../AGENTS.md](../AGENTS.md)
- [TASK_LIFECYCLE_BASELINE.md](./TASK_LIFECYCLE_BASELINE.md)
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [../platform_infra/README.md](../platform_infra/README.md)

## 1. Core Rule

XA Mass infra has three truth layers: control-plane storage, runtime state, and
trace / audit stream. Do not collapse them just because one layer is currently
more implemented than another.

- server profiles may change infrastructure, seed source, logging, and
  operational defaults; they must not change public task, worker, credential,
  project, rule, or control-plane API contracts
- `memory-local` is the lightweight local server shape: file-backed H2
  control-plane storage, memory runtime/transport, and explicit local fixture
  operator auth when enabled.
- `durable-local` is the current inspectable fail-closed local shape, not a
  formal production profile: control-plane storage must be JDBC-enabled,
  runtime state must use Redis, and transport delivery/endpoint lease evidence
  must use Redis.
  Misconfiguration must fail startup instead of silently reverting to process
  memory.
- SQLite-first persistence is a control-plane storage direction only; it must
  not be read as moving runtime queues, leases, heartbeat, result convergence,
  or trace/audit streams into SQLite
- Redis remains the current cross-process runtime-truth direction for queues,
  leases, counters, worker runtime evidence, transport endpoint lease evidence,
  dispatch handoff, and result ingress
- trace-owned DB materialization may be added later through a trace-owned queue
  or sink, but it is a deferred analysis/read-model path, not control-plane
  storage and not runtime truth
- missing trace implementation does not make trace-shaped data become DB truth
- missing trace implementation does not justify unbounded runtime residue
- temporary residue is allowed only when it is bounded, clearly non-authoritative,
  and kept smaller than the trace/audit surface it substitutes for

## 2. Ownership Matrix

| Concern | Canonical layer | Why | Current allowed temporary placement | Must not drift into |
| --- | --- | --- | --- | --- |
| task shell truth (`Task` id/project/status/sharedConfig/terminal reason) | control-plane storage | restart recovery and operator task truth depend on it | none beyond caches | trace-only logs or hot queue state |
| worker declaration | control-plane storage | stable worker identity plus explicit group/node membership | runtime cache/projection for lookup speed | transient transport events or active scheduling truth |
| worker group capability | control-plane storage | `WorkerGroup.eventBindings` is the declared capability truth used to build candidate-source views | immutable runtime snapshot/index for lookup speed | worker declaration rows, transport events, or active scheduling state |
| project/event catalog metadata | control-plane storage | stable project identities, global event capability definitions, and project-event authorization bindings must survive restart | SDK in-memory bootstrap registry for embedded/test/local use | runtime worker topology, worker presence, or trace rows |
| transport endpoint lease evidence | runtime state | volatile endpoint/session lease evidence for already known workers and delivery buckets | Redis/in-memory endpoint lease records with lease expiry | control-plane worker declaration, worker lifecycle truth, post-assignment worker selection, or dispatch queues |
| rule definitions | control-plane storage | stable policy input | in-process evaluator cache | engine-only hidden defaults inside storage modules |
| principal / submitter credential truth | control-plane storage | stable auth binding truth | in-process auth cache | infra module exporting SDK surface |
| environment seed metadata for project/rule/catalog/credential initialization | control-plane storage | explicit new-environment setup record | one-shot importer state guarded by config | dev-only HTTP bootstrap APIs or runtime/trace state |
| ready queue membership | runtime state | hot-path scheduling state | none beyond bounded mirrors | JDBC durable truth |
| delayed visibility / retry timing | runtime state | runtime scheduling truth | bounded mirrors for debug only | JDBC durable truth |
| active lease ownership / expiry | runtime state | hot-path callback and expiry truth | bounded mirrors for debug only | JDBC durable truth |
| worker lock / capacity / reservation / online churn | runtime state | volatile worker execution state | bounded in-process residue | JDBC durable truth |
| task progress counters used to close tasks | runtime state plus bounded task aggregate projection | hot-path correctness first, operator summary second | bounded task aggregate snapshots on `Task` | large attempt history tables |
| per-message detail at scale | trace / audit stream | high-volume item history and reconstruction | server-local review materialization for bounded UI/export views | JDBC control-plane storage or engine runtime state |
| per-message attempt timelines at scale | trace / audit stream | execution-history / analysis surface | server-local review materialization for bounded UI/export views | JDBC control-plane storage or engine runtime state |
| engine -> transport dispatch payload | runtime state | best-effort delivery-attempt queue state: bounded admission, destructive mailbox-scoped poll, and observable known failures | bounded in-memory or Redis infra queue primitive behind `TransportDispatchQueue` carrying mailbox-targeted dispatch items | JDBC/review-row truth, a duplicate engine ready queue, or a transport-owned retry/final-recovery state machine |
| transport -> engine result ingress queue | runtime state | hot-path cross-JVM ingress back into engine-owned result ingest ports | bounded Redis channel drained by engine process | server/API owner semantics, transport-owned lifecycle state, or a dispatch-failure compensation inbox |
| runtime result apply | runtime state | active lease, retry budget consumption, runtime apply status, counters, and recent receipts are hot-path truth | `TaskWorkRuntime.applyResultWithContext(...)` | review rows or transport envelope metadata |
| runtime result read | runtime state | stable-final public result rows, task-local result sequence, result repair anchors, and attempt-closed/event/progress barriers are kernel runtime truth | `TaskResultRuntime` memory or Redis implementation | server review rows, JDBC result tables, controller projection reads |
| callback / dispatch / assignment histories | trace / audit stream | replay/debug/analysis, not control truth | structured logs or bounded queues | JDBC durable event history |
| cross-task failure analytics | trace / audit stream | analytical workload | external sink/export | task tables or runtime hot-path scans |

Current engine convergence rule: callback/expiry acceptance comes from runtime
lease truth first. Server review rows may be materialized afterward through the
review report queue, but they do not decide whether a leased work item is valid.
Result-side trace emission follows the same rule: emit from runtime-owned
message/lease state first, then let server materialization lag if needed.
Recent duplicate receipts for already-finalized work belong to bounded runtime
state as well; they are not an excuse to promote review rows back into
mainline callback acceptance. If runtime no longer has an active lease and no
recent final receipt exists, callback acceptance must not fall back to review
materialization or legacy message projections to recover a second acceptance
truth.
Public result reads come from `TaskResultRuntime` committed stable-final rows.
Server-local review rows remain operator/debug material and must not be used for
`/results`, archive generation, or SDK result query.
A durable result ledger or archive materialized view still requires a separate
design and must not be implied by result ingress or review materialization.

## 3. Current Repo Reality

| Area | Current code truth | Interpretation |
| --- | --- | --- |
| `platform_infra/mass-storage-jdbc` | persists task shell/rule/principal/catalog truth; no JDBC worker declaration implementation currently exists | correct control-plane role |
| SQLite control-plane storage | lightweight persistence direction for new-environment control-plane setup; currently backs generic control-plane tables including project/event catalog metadata plus server API-key lifecycle, operator IAM, and low-volume usage evidence in JDBC modes; not a runtime backend | may host stable project/rule/catalog/credential truth; not a queue, lease, heartbeat, result-convergence, or trace store |
| JDBC-local worker lock residue | process-local runtime residue | not durable worker-runtime truth; worker locks/capacity must not become control-plane storage truth |
| `platform_infra/mass-storage-memory` | in-memory task shell, worker declaration adapter, and rule definition storage | current embedded/test implementation |
| `mass-runtime-*` modules | queue/lease/counter semantics | canonical runtime-state home |
| `TaskResultRuntime` memory/Redis implementations | stable-final result rows plus stage/barrier repair state | canonical runtime result-read truth; memory is volatile local/dev, Redis is cross-process runtime truth |
| Redis transport dispatch handoff | post-assignment queue between engine and adapter-host JVMs; delivery integration resolves the selected worker to an opaque `adapterMailboxKey`, while handoff-private mailbox consumer availability evidence gates bounded queue admission | runtime-state delivery-executor handoff, not ready queue ownership, not worker routing truth, not reliable-message ack/requeue ownership, and not task lifecycle truth |
| Redis transport endpoint lease view | shared transport-owned endpoint/session lease evidence | queryable runtime evidence for endpoint diagnostics and selected-worker delivery feasibility; not a queue, not control-plane worker declaration, and not a post-assignment routing engine |
| Redis transport result ingress queue | transport-to-engine runtime ingress | bounded cross-JVM channel drained into engine-owned result ingest, not server endpoints and not dispatch-failure compensation |
| server review materialization | task opt-in server-local review store populated from the review report queue; default mode is `OFF` | operator/read-model materialization, not engine runtime truth |
| engine assembly | wires kernel SPI task-shell ports and worker-runtime `WorkerDeclarationStore` explicitly | prevents shell/declaration truth from silently redefining review/export or runtime ownership |
| `doc/TRACE_CONTRACT.md` plus `platform_infra/mass-trace-sink` | required trace semantics plus the current canonical sink/module implementation | trace remains analysis/debug ownership, not lifecycle/runtime truth |

Local destructive schema reset is an environment-management guard for
pre-release file-backed SQLite only. The server records a sidecar fingerprint
for control-plane SQL resources before JDBC/Flyway startup. In `durable-local`,
an existing local SQLite DB without the sidecar, or with a mismatched sidecar,
is deleted and recreated by default so local schema churn does not block
iteration. This reset is denied outside explicitly allowlisted local profiles
and local SQLite file URLs. It is not migration compatibility and must not be
extended to runtime Redis state, trace/audit data, PostgreSQL, or remote JDBC
targets.

## 4. Fast Placement Test

Ask these in order:

1. If the process restarts, does correct behavior require this value?
2. Is it stable control truth, or is it churn/history?
3. Is the main reader the runtime hot path, or a human/operator/debugger?
4. Will this write on every dispatch, callback, retry, lease tick, or worker heartbeat?
5. Would this still exist if we had a proper trace sink tomorrow?

Default answers:

- `1=yes` and `2=stable` -> control-plane storage candidate
- `3=runtime hot path` and `4=yes` -> runtime-state candidate
- `2=history/churn` or `5=yes` -> trace/audit candidate

If the answer pattern points to trace but no trace sink exists yet, keep only
the smallest bounded temporary residue needed for correctness or immediate
debuggability.

## 5. DB Storage Guardrail

The control-plane DB is for recoverable, stable truth. It is not the default
home for runtime event streams, queue state, or high-volume history.

A field or table belongs in DB only when at least one of these is true:

- correct restart recovery requires it
- it is stable configuration, registration, rule, or credential truth
- it is a bounded task-level aggregate needed for operator task truth

Current DB scope:

- task shell truth: task identity, project, source/workload intent,
  `sharedConfig`, task status, terminal reason, and bounded task aggregates
- worker declaration truth: worker identity, group/node membership,
  adapter/transport hint, and static worker attributes
- worker group capability truth: supported projects, event codes, and
  group-level capability metadata
- project/event catalog metadata: project identities, global event capability
  definitions, and project-event bindings
- rule definitions
- principal credential truth
- explicit environment seed/import metadata for new control-plane setup
- optional bounded task-level review/read-model summaries when they do not
  drive runtime decisions

Do not add DB tables or hot writes for ready queue membership, retry/delay
indexes, active leases, lane dispatch state, worker online/offline churn,
worker locks/capacity/reservations, callback/dispatch streams, large item
history, or attempt timelines. Those belong to runtime state, trace/audit, or
bounded non-authoritative materialization.

H2, SQLite, and PostgreSQL use the same boundary. PostgreSQL durability does
not justify promoting trace-shaped history or runtime churn into control-plane
storage, and SQLite convenience does not justify collapsing runtime truth into
the control-plane DB.

Current product-stage DB rules:

- SQLite is the preferred lightweight control-plane DB direction; PostgreSQL is
  not a required mainline dependency at this stage.
- Server-owned API-key lifecycle, IAM/user-role, API usage ledger, and
  submitter-viewer session store decisions belong to `xa-mass-server`.
  `platform_infra` may own generic storage primitives and generic task/rule/
  principal/catalog migrations, but it must not grow server API/IAM table
  concepts.
- Server-owned schema notes belong under
  `xa-mass-server/src/main/resources/db/schema/server-control-plane`, and
  executable server-owned Flyway SQL belongs under
  `xa-mass-server/src/main/resources/db/migration/server-control-plane`.
  Generic platform storage SQL, including project/event catalog metadata,
  remains under
  `platform_infra/mass-storage-jdbc`.
- Submitter-viewer sessions are runtime/session convenience state. They must
  not be persisted in SQLite/JDBC as control-plane truth; future cross-process
  sharing belongs to a runtime/Redis session design.
- Submitter-viewer sessions are the current durable-local memory exception. They
  must not be used as precedent for memory fallback in control-plane storage,
  runtime queues/results, transport delivery, or transport presence.
- Server API-key lifecycle schema must keep auth projection separate from
  lifecycle truth and must distinguish omitted, wildcard, and bounded
  project/event scopes before persisting scope fields.
- Initial data loading is an explicit environment bootstrap import, not a
  public API and not an automatic migration path.
- Seed/import is off by default and should be enabled only for a new
  environment or an explicit local/test fixture.
- The project does not currently promise commercial-history migration or
  backwards-compatible schema evolution. Schema changes may require deleting
  and recreating local DBs in the current pre-release phase. A future
  schema-version check should fail fast with a clear "recreate or reseed this
  environment" message instead of half-running against stale local data.

## 6. Temporary Residue Rules

Temporary residue is acceptable only when all of these are true:

- bounded by task, worker, queue, or recent-attempt scope
- restart-volatile unless explicitly promoted by contract
- not used as the sole source of correctness when the canonical layer differs
- documented as current implementation drift in the owner README
- easy to delete or replace once the canonical layer lands
- task-level stop or terminal convergence does not require iterating the full
  residue set just to restamp state that runtime already owns

Temporary residue is not acceptable when it becomes:

- a new durable JDBC table for high-frequency history
- an unbounded in-memory analytics surface
- a hidden cross-module dependency that changes ownership
- a second effective mainline that callers start depending on

## 7. Drift Alarms

Treat these as regression signals:

- storage module imports engine policy assembly or default policy factories
- infra implementation module exports `com.xa.mass.sdk.*` surface
- control-plane JDBC writes are added for callback, retry, lease, or dispatch history
- runtime state starts carrying large-scale historical reads "for observability"
- trace-shaped detail is justified with "there is nowhere else to put it"
- docs describe temporary residue as target architecture

## 8. Decision Outputs

When an agent makes a placement decision, summarize it in one line:

- `layer=control-plane storage; reason=stable restart-required truth`
- `layer=runtime state; reason=hot-path lease/queue/lock truth`
- `layer=trace/audit; reason=high-volume history or analysis surface`
- `layer=temporary residue only; canonical=trace/audit; bound=<scope>`

If that one-line decision cannot be written clearly, the design is probably not
ready to land.
