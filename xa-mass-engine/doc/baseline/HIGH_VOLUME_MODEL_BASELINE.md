# High-Volume Runtime Facts

Last updated: 2026-05-13

Status: current high-volume runtime facts.

Trust: code and verified behavior override this summary.

- [../../../AGENTS.md](../../../AGENTS.md)
- [../../../doc/AGENT_BASELINE.md](../../../doc/AGENT_BASELINE.md)
- [../roadmap/TASK_RUNTIME_PROFILE_DESIGN.md](../roadmap/TASK_RUNTIME_PROFILE_DESIGN.md)
- [../../../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md](../../../transport/TRANSPORT_HIGH_VOLUME_EVENT_DESIGN.md)
- [../../../doc/TESTING_INDEX.md](../../../doc/TESTING_INDEX.md)

## 1. Current Status

Already true in current code:

- the first `TaskWorkRuntime` slice is landed, and its shared runtime contract now lives in `platform_infra/mass-runtime-api`
- `TaskManager` still writes `Task`, while server review materialization is
  best-effort residue written from the review report queue instead of the
  ingest truth
- initial or appended work is also written into `TaskWorkRuntime`
- assignment claims ready work from runtime instead of scanning all `INIT` messages
- engine startup recovery can repopulate assignment signals from runtime-owned ready work instead of relying on `READY` task status scans alone
- runtime owns active lease and expiry indexes
- task progress and terminal policy already read runtime counters instead of aggregate message scans
- task terminal cleanup no longer needs to scan queued/non-final review rows;
  runtime active leases are the only terminal-drain ownership
- task cancellation no longer synchronously rewrites every queued review row
- bounded task-state validation no longer needs full message scans; deep review
  checks are now an explicit audit path instead of the default validation meaning
- engine -> transport dispatch now carries a runtime-native binding built from
  claimed runtime work instead of transporting persisted review input as the
  mainline dispatch carrier
- result ingest uses runtime lease truth and recent final receipts rather than
  treating review row presence as callback truth
- review/attempt writes are no longer allowed to gate dispatch
  or callback convergence; at very high message volume they are trace/review residue,
  not queue truth
- duplicate, late, and no-active-lease callback trace paths must not re-read
  review attempts just to decorate events; runtime lease identity is the
  hot-path ceiling

## 2. Current Guardrails

Keep these decisions stable:

- high-volume work defaults to `BATCH + BULK`: finite workset, runtime-driven redispatch, and no stable per-item timeout meaning while retry budget remains
- low-latency conversational work defaults to `SESSION + INTERACTIVE`: persistent channel semantics, signal-driven wakeup, and bounded per-item feedback
- runtime queue/lease/counter ownership stays behind shared runtime modules instead of being re-embedded back into engine-local packages
- runtime workload selection resolves once per task into an engine-owned profile; do not let hot-path scheduling repeatedly interpret arbitrary task attributes
- task strategy, worker matching, and start-gate decisions stay at the task or explicit task-slice level; do not reintroduce per-message rule matching as a scaling fallback
- the runnable unit is a queue-native envelope, not a thick compatibility object graph
- convergence is counter-driven, not full-message-scan-driven
- attempt truth splits into active hot-path lease truth and off-path bounded
  review/trace residue
- ingress sources may differ at the API edge, but converge after ingest into one runnable-unit shape
- observability stays in logs, traces, counters, indexed reads, and explicit export sinks
- idempotent result, retry, and timeout handling matter more than rich mid-flight projections
- server-owned task-detail reads stay bounded; large-scale detail analysis belongs in structured trace, audit sinks, or downstream storage engines

## 3. Contracts To Preserve

High-volume changes must preserve these unless explicitly approved otherwise:

- `POST /api/v1/tasks` followed by `POST /api/v1/tasks/{taskId}/items`
- append + seal semantics for open intake
- polling worker contract around `TaskDispatchItem`
- result submission contract around `TaskResultReport`
- task terminal immutability to late results

## 4. Required Proof

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
