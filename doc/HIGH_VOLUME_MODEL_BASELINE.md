# High-Volume Model Baseline

Last updated: 2026-05-08

Status: current convergence baseline for high-volume runtime direction.

Trust: code and verified behavior override this baseline.

- [../AGENTS.md](../AGENTS.md)
- [./AGENT_BASELINE.md](./AGENT_BASELINE.md)
- [../xa-mass-engine/TASK_RUNTIME_PROFILE_DESIGN.md](../xa-mass-engine/TASK_RUNTIME_PROFILE_DESIGN.md)
- [../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md](../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md)
- [./TESTING_BASELINE.md](./TESTING_BASELINE.md)

## 1. Current Status

Already true in current code:

- the first `TaskWorkRuntime` slice is landed, and its shared runtime contract now lives in `platform_infra/mass-runtime-api`
- `TaskManager` still writes `Task`, while compatibility message projections
  are best-effort residue written after runtime enqueue instead of the ingest
  truth
- initial or appended work is also written into `TaskWorkRuntime`
- assignment claims ready work from runtime instead of scanning all `INIT` messages
- engine startup recovery can repopulate assignment signals from runtime-owned ready work instead of relying on `READY` task status scans alone
- runtime owns active lease and expiry indexes
- task progress and terminal policy already read runtime counters instead of aggregate message scans
- task terminal cleanup no longer needs to scan queued/non-final message
  projections; runtime active leases are the only terminal-drain ownership
- task cancellation no longer synchronously rewrites every queued compatibility
  message row; terminal task/message reads overlay the bounded final view
  instead of turning cancel into a per-message CRUD sweep
- bounded task-state validation no longer needs full message scans; deep projection checks are now an explicit audit path instead of the default validation meaning
- engine -> transport dispatch now carries a runtime-native binding built from
  claimed runtime work instead of transporting persisted message-projection input as the
  mainline dispatch carrier
- result ingest can recover a bounded compatibility message projection from
  runtime lease truth when the projection is missing, rather than treating the
  missing projection as callback truth
- compatibility attempt writes are no longer allowed to gate dispatch
  or callback convergence; at very high message volume they are trace residue,
  not queue truth
- result-side compatibility rewrites no longer need to preserve full
  message input payload; bounded residue should converge toward `payloadRef` plus
  logical status/output/error summary instead of replaying large inline payloads
- duplicate, late, and no-active-lease callback trace paths must not re-read
  attempt projections just to decorate events; bounded message projection plus
  runtime lease identity is the hot-path ceiling

Still too heavy on the hot path:

- `Task` still owns too much message-facing responsibility
- dispatch and result flow still write thick compatibility projections
- read models still assume one task can cheaply expose all messages
- full attempt history is still too expensive as default hot-path truth
- some persistence surfaces still expose full-message reads that are acceptable for audit only, not runtime readiness truth
- compatibility message residue is still too available in some query-time
  flows even though task-stop mainline no longer restamps every queued message
  row
- task orchestration is not fully separated from downstream detail-analysis needs yet

## 2. Frozen Design

Keep these decisions stable:

- `Task` shrinks toward a control-plane shell: lifecycle, ownership, shared config, intake state, aggregate counters, terminal reason
- runtime queue/lease/counter ownership should stay behind shared runtime modules instead of being re-embedded back into engine-local packages
- runtime workload selection should resolve once per task into an engine-owned profile; do not let hot-path scheduling repeatedly interpret arbitrary task attributes
- task strategy, worker matching, and start-gate decisions stay at the task or explicit task-slice level; do not reintroduce per-message rule matching as a scaling fallback
- the default runnable unit is a queue-native envelope, not a thick compatibility object graph
- convergence is counter-driven, not full-message-scan-driven
- attempt truth splits into active hot-path lease truth and optional off-path audit history
- ingress sources may differ at the API edge, but converge after ingest into one runnable-unit shape
- observability stays in logs, traces, counters, indexed reads, and explicit export sinks
- idempotent result, retry, and timeout handling matter more than rich mid-flight projections
- engine-owned task-detail reads stay bounded; large-scale detail analysis belongs in structured trace, audit sinks, or downstream storage engines

Minimal target shape:

- task shell: `taskId`, `status`, `project`, `user`, `sharedConfig`, `contract`, `intakeStatus`, aggregate counters, `terminalReason`, timestamps
- runnable envelope: `taskId`, `messageId`, `eventCode`, `payload` or `payloadRef`, `retryCount`, `leaseToken`, worker or routing hints, visibility/scheduling timestamp
- active lease truth: `taskId`, `messageId`, `leaseToken`, `workerId`, `workerContextId`, `payloadRef`, `leaseExpireAt`, `retryCount`
- trace or audit export event: task lifecycle, dispatch binding, attempt state transition, result acceptance or rejection, retry reset, lease-loss/expiry, terminal closure

## 3. Contracts To Preserve

Compression work must preserve these unless explicitly approved otherwise:

- `POST /api/v1/tasks` followed by `POST /api/v1/tasks/{taskId}/items`
- append + seal semantics for open intake
- polling worker contract around `TaskDispatchItem`
- result submission contract around `TaskResultReport`
- task terminal immutability to late results

## 4. Acceptable Slices

Allowed migration slices:

1. shrink `Task` toward a control-plane shell
2. add or tighten explicit ingest for different API-edge source forms
3. move dispatch toward queue-native runtime state
4. move result convergence toward counters and output processing
5. downgrade full attempt history from default hot-path truth

Do not combine these into one broad rewrite.

## 5. Required Proof

Every high-volume slice must prove:

- `perf`: queue pressure and hot-path cost
- `concurrency`: lease expiry, duplicate result, retry, and inflight recovery
- `Boot-shell E2E`: create -> ingest -> dispatch -> result -> convergence

Minimum proof points:

- bounded ingest for large task sources
- no hot-path full-task message scan
- counter-driven convergence
- retry and timeout recovery without double-finalization
- structured trace or sink export remains sufficient for downstream task-detail reconstruction without adding new hot-path scans
