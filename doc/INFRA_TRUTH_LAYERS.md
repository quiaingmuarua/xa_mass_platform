# Infra Truth Layers

Status: current cross-module boundary contract.

Use this file when deciding where a new piece of truth belongs. This is the
high-density decision table for `control-plane storage`, `runtime state`, and
`trace / audit stream`.

Read with:

- [../AGENTS.md](../AGENTS.md)
- [DB_STORAGE_PRINCIPLES.md](./DB_STORAGE_PRINCIPLES.md)
- [RESULT_BOUNDARY_BASELINE.md](./RESULT_BOUNDARY_BASELINE.md)
- [TRACE_CONTRACT.md](./TRACE_CONTRACT.md)
- [../platform_infra/README.md](../platform_infra/README.md)

## 1. Core Rule

XA Mass infra has three truth layers: control-plane storage, runtime state, and
trace / audit stream. Do not collapse them just because one layer is currently
more implemented than another.

- missing trace implementation does not make trace-shaped data become DB truth
- missing trace implementation does not justify unbounded runtime residue
- temporary residue is allowed only when it is bounded, clearly non-authoritative,
  and kept smaller than the future trace surface it stands in for

## 2. Ownership Matrix

| Concern | Canonical layer | Why | Current allowed temporary placement | Must not drift into |
| --- | --- | --- | --- | --- |
| task shell truth (`Task` id/project/status/sharedConfig/terminal reason) | control-plane storage | restart recovery and operator task truth depend on it | none beyond caches | trace-only logs or hot queue state |
| worker registration / worker-context registration | control-plane storage | stable registration truth | runtime cache/projection for lookup speed | transient transport events |
| worker runtime route-owner view | runtime state | volatile online/reachability truth owned by transport adapters and nodes | Redis/in-memory presence records with lease expiry | control-plane worker registration or dispatch queues |
| rule definitions | control-plane storage | stable policy input | in-process evaluator cache | engine-only hidden defaults inside storage modules |
| principal / submitter credential truth | control-plane storage | stable auth binding truth | in-process auth cache | infra module exporting SDK surface |
| ready queue membership | runtime state | hot-path scheduling state | none beyond bounded mirrors | JDBC durable truth |
| delayed visibility / retry timing | runtime state | runtime scheduling truth | bounded mirrors for debug only | JDBC durable truth |
| active lease ownership / expiry | runtime state | hot-path callback and expiry truth | bounded mirrors for debug only | JDBC durable truth |
| worker lock / occupancy / online churn | runtime state | volatile worker execution state | bounded in-process residue | JDBC durable truth |
| task progress counters used to close tasks | runtime state plus bounded task aggregate projection | hot-path correctness first, operator summary second | bounded task aggregate snapshots on `Task` | large attempt history tables |
| per-message detail at scale | trace / audit stream | high-volume item history and reconstruction | bounded projection residue | JDBC durable event history |
| per-message attempt timelines at scale | trace / audit stream | execution-history / analysis surface | bounded projection residue | JDBC durable event history |
| engine -> transport dispatch payload | runtime state | claim/lease-owned hot-path delivery truth | bounded in-memory or Redis `TaskDispatchHandoff` after claim only | JDBC/message projection truth or a duplicate ready queue |
| transport -> engine result / dispatch-failure inboxes | runtime state | hot-path cross-JVM ingress back into engine-owned result/compensation ports | bounded Redis inboxes drained by engine process | server/API owner semantics or transport-owned lifecycle state |
| runtime result apply | runtime state | active lease, retry budget consumption, runtime apply status, counters, and recent receipts are hot-path truth | `TaskWorkRuntime.applyResultWithContext(...)` | message/attempt projection or transport envelope metadata |
| runtime result read | runtime state | stable-final public result rows, task-local result sequence, result repair anchors, and event/progress barriers are kernel runtime truth | `TaskResultRuntime` memory or Redis implementation | `TaskDetailStore`, JDBC result tables, server/controller projection reads |
| callback / dispatch / assignment histories | trace / audit stream | replay/debug/analysis, not control truth | structured logs or bounded queues | JDBC durable event history |
| cross-task failure analytics | trace / audit stream | analytical workload | external sink/export | task tables or runtime hot-path scans |

Current engine convergence rule: callback/expiry acceptance comes from runtime
lease truth first. Compatibility message/attempt rows may be reconstructed or
upserted afterward as bounded residue, but they do not decide whether a leased
work item is valid.
Result-side trace emission follows the same rule: emit from runtime-owned
message/lease state first, then repair bounded projection residue if needed.
Recent duplicate receipts for already-finalized work belong to bounded runtime
state as well; they are not an excuse to promote message projection back into
mainline callback acceptance. If runtime no longer has an active lease and no
recent final receipt exists, callback acceptance must not fall back to message
projection residue to recover a second acceptance truth.
Public result reads come from `TaskResultRuntime` committed stable-final rows.
Projection residue remains debug/audit material and must not be used for
`/results`, archive generation, or SDK result query.
A durable result ledger or archive materialized view still requires a separate
design and must not be implied by result ingress or projection residue.

## 3. Current Repo Reality

| Area | Current code truth | Interpretation |
| --- | --- | --- |
| `platform_infra/mass-storage-jdbc` | persists task/worker/rule/principal truth | correct control-plane role |
| JDBC-local message/attempt projections | process-local compatibility residue | not a storage expansion license |
| JDBC-local worker/context/lock residue | process-local runtime residue | not durable worker-runtime truth |
| `platform_infra/mass-storage-memory` | in-memory control-plane storage | current embedded/test implementation |
| memory/JDBC detail residue internals | neutral projection-record storage with compatibility materialization at the boundary | do not let legacy message models become the internal owner shape again |
| `mass-runtime-*` modules | queue/lease/counter semantics | canonical runtime-state home |
| `TaskResultRuntime` memory/Redis implementations | stable-final result rows plus stage/barrier repair state | canonical runtime result-read truth; memory is volatile local/dev, Redis is cross-process runtime truth |
| Redis transport dispatch handoff | post-claim assignment queue between engine and transport JVMs; node-targeted inboxes are keyed by `transportNodeId` | runtime-state handoff, not ready queue ownership and not task lifecycle truth |
| Redis worker presence / route-owner view | shared transport-owned reachability state | queryable runtime view for matching and dispatch routing, not a queue and not control-plane worker registration |
| Redis transport result / dispatch-failure inboxes | transport-to-engine runtime ingress | bounded cross-JVM channels drained into engine-owned result ingest and compensation ports, not server endpoints |
| `TaskDetailStore` engine usage | projection-first bounded compatibility upsert/snapshot reads through neutral records only | not message CRUD ownership and not runtime truth |
| engine assembly | wires `TaskStorage` and `TaskDetailStore` separately | prevents storage-shell truth from silently redefining detail/projection ownership |
| `doc/TRACE_CONTRACT.md` plus `platform_infra/mass-trace-sink` | required trace semantics plus the current canonical sink/module implementation | trace remains analysis/debug ownership, not lifecycle/runtime truth |

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

## 5. Temporary Residue Rules

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

## 6. Drift Alarms

Treat these as regression signals:

- storage module imports engine policy assembly or default policy factories
- infra implementation module exports `com.xa.mass.sdk.*` surface
- control-plane JDBC writes are proposed for callback, retry, lease, or dispatch history
- runtime state starts carrying large-scale historical reads "for observability"
- trace-shaped detail is justified with "there is nowhere else to put it"
- docs describe temporary residue as target architecture

## 7. Decision Outputs

When an agent makes a placement decision, summarize it in one line:

- `layer=control-plane storage; reason=stable restart-required truth`
- `layer=runtime state; reason=hot-path lease/queue/lock truth`
- `layer=trace/audit; reason=high-volume history or analysis surface`
- `layer=temporary residue only; canonical=trace/audit; bound=<scope>`

If that one-line decision cannot be written clearly, the design is probably not
ready to land.
