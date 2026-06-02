# Observability And Test Proof Roadmap

Last updated: 2026-05-18

Status: current direction document.

This roadmap defines how XA Mass Platform should increase correctness
observability without growing a large pile of scattered tests.

## 1. Goal

Raise confidence in the platform kernel by making each critical mainline
provable through the same bounded evidence surfaces:

- runtime truth snapshots
- canonical trace events
- operator trace query and scenario analyzers
- invariant checkers
- soak / chaos reports

Trace is lifecycle evidence. It is not runtime truth, control-plane truth, or
an event-sourcing ledger.

## 2. Proof Layers

### Runtime Truth

Runtime owners remain the source of truth for current state:

- `TaskWorkRuntime` owns ready, delayed, lease, retry, expiry, and counters
- `TaskResultRuntime` owns stable visible result rows, result seq, staged
  drafts, and result barrier state
- worker / group / scheduling owners own capability and candidate-source facts

Diagnostic snapshots and invariant checkers may read these owners, but trace
must not drive runtime decisions.

### Canonical Trace

`ExecutionEvent` is the process evidence model. Trace answers:

- which owner made a decision
- which identity was involved
- which transition or outcome occurred
- why a decision was accepted, rejected, skipped, or retried

Trace should be queryable by stable identities such as task, message, worker,
command, trace id, and event type.

### Scenario Analyzer

Scenario analyzers live in `xa-mass-trace` and should replace scattered
hand-written trace assertions in unrelated tests. A scenario analyzer should:

- read canonical JSONL through the trace query backend
- verify event presence, event fields, and event order
- return a structured report with issues
- avoid reading compatibility projection, MDC logs, runtime queues, or storage
  tables

### Runtime Invariant Checker

Invariant checkers prove that runtime state did not drift after a scenario or
soak run. Initial high-value invariants:

- terminal tasks have no active leases
- terminal tasks have no ready or delayed work residue
- visible result count matches expected submitted work count
- result sequential read has strictly increasing seq and no duplicate message
  ids
- result barrier pending indexes are empty or explained
- worker candidate indexes contain no dangling group / worker references
- trace dropped count is zero when trace is part of the proof

### Report Bundle

Soak and chaos reports should converge toward one proof bundle:

- run config
- runtime invariant report
- trace validation report
- scenario analyzer report
- result sequential read report
- worker and runtime counters
- failure samples

## 3. Mainline Coverage Targets

### Scheduling

Proof should cover:

- task / item submit
- eventCode / worker group candidate narrowing
- worker match accept / reject
- assignment summary
- dispatch binding
- runtime lease
- polling / transport delivery

High-value analyzers:

- group capability routing
- target worker direct lookup
- worker offline eviction
- late worker backfill
- lease expiry redispatch
- multi-task worker competition

### Result Convergence

Proof should cover:

- callback accepted / rejected
- runtime result apply
- stable visible final row
- logical final publish barrier
- progress barrier
- task terminal convergence
- sequential result read and archive read

High-value analyzers and invariants:

- result visible once
- duplicate callback suppression
- pending barrier drain
- sequential read correctness

### Worker Control

Proof should cover:

- worker online / offline
- capability report applied
- state report applied
- worker command lifecycle
- command and state events remain separate owner facts unless an explicit
  owner connects them

High-value analyzers:

- worker command lifecycle order
- worker state report accepted
- offline worker excluded from new candidate sets

## 4. Phase Plan

### OBS-0: Inventory

Map current trace events, metrics, runtime counters, and scenario analyzers to
the scheduling, result, and worker-control mainlines.

Acceptance:

- no behavior change
- gaps are listed by mainline and owner

### OBS-1: Trace Query And Sequence Proof

Build reusable trace proof primitives in `xa-mass-trace`:

- bounded query by task, message, worker, command, trace id, and event type
- sequence verifier for ordered lifecycle evidence
- scenario analyzers use the verifier instead of local `anyMatch` chains when
  order matters

Acceptance:

- no engine scenario-test growth
- trace query rejects unbounded scans
- at least one existing analyzer proves event order through the shared verifier

### OBS-2: Runtime Invariant Checker

Introduce invariant checkers for soak / scenario reports.

Acceptance:

- terminal task active-lease drain is checked
- result sequential-read checker is reusable
- invariant failures produce structured issues, not only assertion messages

### OBS-3: Soak Proof Bundle

Make polling scheduling soak report include trace validation, analyzer result,
runtime invariants, result sequential read, and worker/runtime counters in one
report shape.

Acceptance:

- fast soak and manual soak write the same proof sections
- report identifies whether failure came from runtime truth, trace evidence, or
  result read correctness

### OBS-4: Critical Scheduling Analyzers

Add analyzers for the few scheduling cases that are hard to prove from terminal
state alone:

- worker offline eviction
- late worker backfill
- lease expiry redispatch
- multi-task worker competition

Acceptance:

- each analyzer uses canonical trace only
- each analyzer proves sequence and key fields
- tests call analyzer reports instead of duplicating trace assertions

## 5. Non-Goals

This roadmap does not introduce:

- a new observability Maven module
- trace-driven runtime decisions
- event sourcing
- broad engine test expansion
- projection-first correctness proof
- fake production wiring owners just to make tests look integrated

## 6. Guardrails

- Trace can prove process evidence but must not become runtime truth.
- Runtime invariant checkers can read runtime owners but must not mutate them.
- Scenario analyzers belong in `xa-mass-trace`, not scattered across engine,
  server, testing, and transport modules.
- Soak and chaos are scheduled/manual pressure lanes unless explicitly promoted
  to CI.
- Prefer one reusable analyzer over multiple near-identical E2E assertions.
