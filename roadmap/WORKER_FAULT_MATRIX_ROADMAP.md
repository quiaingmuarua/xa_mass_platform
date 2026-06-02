# Worker Fault Matrix Roadmap

Last updated: 2026-06-02

Status: active roadmap; partially implemented.

This roadmap defines how XA Mass Platform should move from mostly ideal worker
execution proof toward systematic resource and worker-fault proof.

This is partly current implementation and partly remaining roadmap. Current
facts are called out explicitly below; remaining WF-* sections are not proof
that the implementation already has every row, runner, or CI gate.

Current implementation state:

- WF-0 is largely landed: `WorkerFaultScenarioIndex` owns scenario ids, proof
  line owners, runner families, matrix axes, and trace analyzer mappings.
- WF-1 is partially landed: chaos, perf, transport-load, and soak reports write
  top-level matrix metadata through `WorkerFaultReportMetadata`.
- WF-2 is landed for the worker-pack sample surface: `SampleWorkerFaultProfile`
  owns deterministic profile state for delay, stall, drop, duplicate, late,
  malformed, wrong-identity, and disconnect behavior.
- WF-3 is partially landed: worker-pack sample commands expose the current
  `fault.*` execution/result/transport/state controls. `fault.worker.capacity.flap`
  is intentionally not implemented until an explicit capacity owner surface
  exists; do not fake it through capability attributes.
- WF-4 is partially landed: existing proof lines can be entered by scenario id,
  `run-worker-fault-scenario.sh` exposes a single-scenario entrypoint, and
  `list-worker-fault-scenarios.sh` / `WorkerFaultScenarioCli --list` expose the
  current Java ledger rows.
- WF-5 and later remain active roadmap work: noisy fleets, transport churn rows,
  Redis runtime restart recovery, long-run matrix sweeps, and broader gate
  promotion are not complete.
- Infra-fault rows remain active roadmap work: Redis process restart recovery
  currently has Redis runtime contract coverage and representative Redis E2E
  coverage, but no dedicated chaos runner; Redis partition/failover,
  non-monotonic lease-clock behavior, trace sink overflow analyzer semantics,
  and multi-node transport presence flap are not current distributed-edge proof.

## 1. Problem

The current testing system has useful proof ownership:

- `xa-mass-engine` owns deterministic scheduling and kernel proof
- `xa-mass-server` owns representative Boot-shell E2E proof
- `xa-mass-testing` owns chaos, soak, SDK transport, and perf lanes
- `xa-mass-trace` owns canonical observational proof

That structure is good, but worker behavior is still too clean in many tests.
Worker registration, dispatch receipt, execution, result submit, and terminal
convergence often happen in an ideal order with short execution latency.

The risk is false confidence:

- fast workers hide lease, retry, watchdog, and redispatch ordering bugs
- perfect result submit hides dropped, duplicate, malformed, or stale callback
  behavior
- stable online presence hides half-disconnect and stale reachability bugs
- isolated hand-written chaos cases do not prove the platform's full resource
  fault model

The next step is not just more tests. The next step is a reusable worker fault
model, a resource dependency matrix, and matrix-driven scenarios that run the
full task flow through normal platform entry points.

## 2. Goal

Build a fault-test lane that can express:

```text
task shell
  -> item append
  -> runtime enqueue
  -> dispatch / transport delivery
  -> non-ideal worker behavior
  -> result ingest or missing result
  -> retry / expiry / redispatch / terminal convergence
  -> runtime + trace + task assertions
```

The platform should be tested against realistic resource surprises while still
preserving proof ownership:

- engine tests prove deterministic kernel invariants
- worker-fault matrix runners prove full task-flow behavior under non-ideal
  worker and resource conditions
- Boot-shell E2E carries only representative fault cases that need real host
  wiring
- scheduled chaos and soak carry larger combinations, pressure, and long-tail
  timing

## 3. Current Testing Proof Lines

`xa-mass-testing` already has several concrete proof lines. The fault matrix
should extend these lines instead of creating a parallel testing universe.

Current runner-family proof lines:

| Proof line | Current entry | Current proof surface | Current role |
| --- | --- | --- | --- |
| PR chaos smoke | `scripts/run-chaos-smokes.sh` | task aggregate, `TaskWorkRuntime` counters, active lease drain, final receipts, `ExecutionEvent`, selected trace analyzers | PR-gated distributed-edge probes |
| perf smoke | `scripts/run-perf-smokes.sh` | dispatch timing, retry wakeup timing, callback overlap, runtime/task convergence | scheduled/manual fast latency and lane-isolation signal |
| full task-flow perf model | `TaskFlowLoadModelRunner` | callback cost, progress recompute, release cost, redispatch count, final runtime work stats | manual/scheduled hot-path cost model |
| SDK transport load | `scripts/run-sdk-transport-load.sh` / `SdkTransportLoadRunner` | terminal task state, runtime work stats, delivery diagnostics, worker metrics | embedded SDK transport composition across polling/websocket/socket |
| polling scheduling soak | `scripts/run-polling-scheduling-soak.sh` / `SdkPollingSchedulingSoakRunner` | `proof.runtimeInvariants`, result sequential read, worker metrics, worker lifecycle, delivery diagnostics, trace validation/analyzers | scheduled/manual long-running polling scheduling pressure |

Current PR chaos smoke probes:

- `SdkPollingAllMessagesFailedChaosRunner`
- `SdkPollingMixedResultsChaosRunner`
- `SdkPollingMessageRetryExhaustedChaosRunner`
- `SdkPollingLeaseExpiryRedispatchChaosRunner`
- `SdkWebSocketDisconnectChaosRunner`
- `SdkWebSocketLeaseExpiryRedispatchChaosRunner`
- `SdkWebSocketLateResultAfterLeaseExpiryChaosRunner`

Current chaos trace proof profiles:

- `ALL_FAILED_TERMINAL_CONVERGENCE`
- `MIXED_RESULT_TERMINAL_CONVERGENCE`
- `LEASE_EXPIRY_REDISPATCH`
- `LATE_STALE_RESULT_REPLAY`

Current soak trace analyzer plans:

- `late-worker-backfill`
- `all-failed-terminal-convergence`
- `mixed-result-terminal-convergence`

Current source guardrails:

- chaos smoke runners must stay runtime/aggregate/trace-first
- perf smokes must stay runtime/timing-first
- SDK transport load must stay runtime/aggregate/transport-diagnostics first
- polling scheduling soak must stay runtime/result/trace-first

Current worker-pack fault controls:

- `mock.delay.response`
- `mock.drop.outbound`
- `mock.task.result.status`
- `mock.disconnect`
- `mock.reset`
- `fault.execution.profile`
- `fault.execution.delay`
- `fault.execution.stall`
- `fault.result.drop`
- `fault.result.duplicate`
- `fault.result.late`
- `fault.result.malformed`
- `fault.result.identity`
- `fault.transport.disconnect`
- `fault.worker.state.flap`
- `fault.reset`

Current fault-proof distribution:

- proof lines are real across engine, server E2E, trace analyzers, and chaos
  runners
- PR chaos probes are hand-written scenario runners
- sample fault controls are command-specific and dev-oriented
- worker delay controls are strongest in soak/perf paths
- first-class worker profiles cover the active runner set, while some fault
  modes remain scenario-specific controls
- perf smoke and soak own timing/jitter controls
- capacity flap is not a current control because public worker-pack capability
  reporting does not own `maxConcurrentWork`; wait for a real capacity owner
  surface before implementing that row

Current infra-fault proof boundary:

- worker-behavior distributed-edge proof is represented by current chaos
  runners for lease-expiry redispatch, all-failed convergence, mixed-result
  convergence, retry exhaustion, websocket disconnect, stale late replay, and
  polling soak late-worker backfill
- Redis runtime restart recovery is covered by Redis runtime contract tests and
  representative Redis E2E, but not by a dedicated chaos runner that injects
  restart as the failure mode during a full task flow
- Redis partition/failover and non-monotonic lease-clock behavior are not
  current proof rows
- trace sink overflow has sink-level `DROP` / `FALLBACK_SYNC` behavior tests,
  but no trace-analyzer proof that missing events produce an explicit
  incomplete/failure decision instead of a silent pass
- chaos reports record trace `droppedCount`, but current chaos analyzer pass
  does not use it as a completeness gate; polling soak already reports dropped
  trace events as an invariant issue
- multi-node transport routing competition and same-worker presence flap across
  nodes are not current proof rows
- these infra-fault rows belong in this roadmap rather than a parallel roadmap
  because they extend the same distributed-edge proof matrix without changing
  engine, transport, runtime, or trace ownership

## 4. Resource Dependency Matrix

| Resource | Platform dependency | Surprise conditions | Primary proof lane |
| --- | --- | --- | --- |
| worker process | executes task item and submits result | slow, stuck, crash, restart, duplicate submit | worker-fault matrix / chaos |
| worker execution time | controls lease overlap and terminal timing | p95/p99 delay, jitter, near-timeout success | worker-fault matrix / perf smoke |
| worker capacity | gates dispatch and backfill | saturated, capacity flap, slow drain | engine scheduling + soak |
| worker state | dispatch availability | `AVAILABLE`/`DRAINING`/offline flap | engine scheduling + Boot-shell representative |
| transport presence | worker reachability | false-online, half-disconnect, delayed offline | transport tests + chaos |
| dispatch delivery | dispatch item reaches worker | send failure, duplicate delivery, delayed delivery | transport runtime + worker-fault matrix |
| result submit | result reaches ingest channel | dropped, duplicate, late, malformed, wrong identity | engine kernel + worker-fault matrix |
| runtime work | ready/delayed/lease/retry/counter truth | lease expiry, retry delay, backpressure | engine runtime + chaos |
| result runtime | visible final rows and barriers | duplicate final, missing barrier, staged residue | engine kernel + result runtime contract |
| control-plane storage | task/worker/project/rule truth | stale read, write failure, slow write | storage contract + targeted E2E |
| trace sink | canonical evidence | dropped event, delayed event, partial file | trace validation + report bundle |
| scheduler/watchdog | dispatch and expiry progression | slow tick, duplicate tick, missed tick | engine deterministic + chaos |

## 5. Fault Event Model

Introduce a reusable fault-event namespace for worker behavior.

Fault events should travel through normal platform paths. Tests configure a
worker or task scenario through SDK/API/event surfaces, then run a full task
flow. Tests should not mutate runtime internals to manufacture the main
scenario.

Hard boundary:

- `fault.*` is a worker-pack / sample-worker / test-harness control surface; it
  is not a new engine model, transport protocol, worker session, or runtime
  owner
- state, capacity, reachability, capability, and result effects must be
  expressed through existing owner surfaces such as worker state report,
  command acknowledgement, transport presence, capability report, and normal
  result ingest
- `fault.*` must not let tests mutate `TaskWorkRuntime`, `TaskResultRuntime`,
  `WorkerRegistrySnapshot`, `WorkerRegistry` / `WorkerSlot`, or dispatch gates
  directly
- production transport and engine paths must remain valid when the fault
  surface is absent

Control channel:

- first-slice `fault.*` events are sample-worker commands registered in
  `CommandRegistry` alongside existing `mock.*` controls
- the test harness issues `fault.*` commands before or during a scenario; the
  worker stores fault state in `SampleClientState` or its successor and applies
  it when dispatch/result/state-report code reaches the relevant phase
- `fault.*` is not a task work item event code and must not require a second
  worker protocol
- worker state and capacity flaps are harness-driven one-shot commands; the
  worker executes each requested flip through state report or capability/capacity
  report surfaces rather than running an autonomous local flap loop

Initial event families:

| Event | Parameters | Behavior |
| --- | --- | --- |
| `fault.execution.profile` | `profile`, `seed` | chooses worker speed and failure profile |
| `fault.execution.delay` | `minMs`, `maxMs`, `distribution`, `seed` | adds deterministic jitter before result submit |
| `fault.execution.stall` | `until=lease-expiry/forever/ms` | receives work and does not submit result during the stall |
| `fault.result.drop` | `mode=once/always/percent`, `seed` | executes but does not submit a result |
| `fault.result.duplicate` | `count`, `gapMs` | submits the same logical result multiple times |
| `fault.result.late` | `delayPastLeaseMs` | submits after lease expiry or takeover |
| `fault.result.malformed` | `kind`, `seed` | submits missing or invalid result fields |
| `fault.result.identity` | `kind=wrongTask/wrongMessage/wrongWorker/wrongLease` | submits with invalid correlation identity |
| `fault.transport.disconnect` | `phase`, `reconnectAfterMs` | disconnects before/after receive or before/after result |
| `fault.worker.capacity.flap` | `targetCapacity` or `toggle`, `stateVersion` | deferred until a public capacity owner surface exists; do not implement by faking capability attributes |
| `fault.worker.state.flap` | `state=AVAILABLE/DRAINING/OFFLINE/DEGRADED`, `stateVersion` | one-shot state report flip driven by the harness |
| `fault.reset` | `scope=worker/all` | clears test-harness fault state for one worker or all sample workers |

Existing `mock.*` events are dev/sample controls for current fixtures only.
They should not be expanded for new matrix rows. New matrix scenarios must use
`fault.*` names so grep points agents to the fault-test model rather than
generic sample behavior. Once `fault.*` reaches parity for the rows that still
need `mock.*`, those old fixture controls should be removed or demoted from the
proof path instead of preserved as a second live track.

## 6. Worker Profiles

Define worker profiles as reusable scenario inputs.

| Profile | Behavior |
| --- | --- |
| `FAST` | low delay, low jitter, no injected loss |
| `NORMAL` | realistic small jitter, no injected loss |
| `SLOW` | high p95/p99 delay, still usually succeeds before lease expiry |
| `NEAR_TIMEOUT` | often finishes close to lease expiry |
| `STUCK` | receives work and stops before result submit |
| `FLAKY_RESULT` | executes but drops or duplicates results by configured rate |
| `FLAKY_TRANSPORT` | disconnects or reconnects around dispatch/result phases |
| `MALFORMED_RESULT` | emits invalid result envelopes for rejection proof |
| `NOISY` | mixed delay, duplicate, and result-drop behavior with deterministic seed |

Profiles should be deterministic with a seed so CI failures are reproducible.
Profiles are named configurations that expand to primitive `fault.*` controls.
They should live in a shared worker fault profile registry when implemented so
chaos, soak, perf, and worker-pack sample code do not each invent different
profile semantics.

Initial profile composition:

| Profile | Primitive expansion |
| --- | --- |
| `FAST` | no configured fault, optional small fixed delay only when a proof line already models worker delay |
| `NORMAL` | `fault.execution.delay` with small deterministic jitter and no loss |
| `SLOW` | `fault.execution.delay` with high p95/p99 jitter that still normally completes before lease expiry |
| `NEAR_TIMEOUT` | `fault.execution.stall(until=lease-expiry)` or equivalent deterministic delay minus a small safety margin; not a separate code path |
| `STUCK` | `fault.execution.stall(until=forever)`; not a separate code path |
| `FLAKY_RESULT` | `fault.result.drop(mode=percent)` plus optional `fault.result.duplicate(count,gapMs)` using the same seed |
| `FLAKY_TRANSPORT` | `fault.transport.disconnect(phase,reconnectAfterMs)` with seeded phase selection |
| `MALFORMED_RESULT` | `fault.result.malformed(kind,seed)` |
| `NOISY` | seeded combination of `fault.execution.delay`, `fault.result.drop`, and `fault.result.duplicate`; exact rates belong in the shared registry and must be inspectable by tests |

## 7. Matrix Axes

The matrix should be generated from a small number of axes rather than from
one-off scenario classes.

Core axes:

- transport: `polling`, `websocket`, `socket`
- runtime backend: `memory`, `redis`
- contract: `BATCH`, `SESSION`
- workload class: `BULK`, `INTERACTIVE`
- worker profile: one of the profiles above
- fault phase: before receive, after receive, before result, after result,
  after lease expiry, after terminal
- result behavior: success, failure, dropped, duplicate, late, malformed,
  wrong identity
- worker fleet shape: single worker, takeover worker, late-joining worker,
  saturated worker pool

Do not expand this into a full Cartesian product for PR. Pick representative
rows by risk.

First slice:

- WF-0 and WF-1 are report/ledger convergence only and should map the existing
  chaos runners without changing their behavior or CI placement
- the first behavior-bearing `fault.*` implementation should be constrained to
  `polling` + `memory` + `BATCH` before Redis, `SESSION`, or additional
  transport modes are added
- existing websocket chaos probes may keep running through their current
  hand-written path until equivalent matrix rows carry the same assertion and
  trace evidence
- socket fault rows are scheduled/manual only in the first slice; promotion to
  PR requires equivalent transport-churn evidence from `SdkTransportLoadRunner`
  first
- no new runner should be introduced until the existing proof lines can report
  scenario id and matrix axes consistently

## 8. Initial Scenario Set

The initial matrix must reuse current proof lines. Do not promote scheduled or
manual chaos runners into PR until the matrix row proves it carries a distinct
distributed-edge invariant with the same runtime, aggregate, and trace evidence.

### Existing Probe Mapping

| Existing probe / profile | Current proof line | Matrix row it already covers | Gap to close |
| --- | --- | --- | --- |
| `SdkPollingAllMessagesFailedChaosRunner` | scheduled/manual chaos support | polling all-failed terminal convergence | keep out of PR unless it proves a distributed-edge invariant beyond engine/server result convergence |
| `SdkPollingMixedResultsChaosRunner` | scheduled/manual chaos support | polling mixed-result terminal convergence | keep out of PR unless it proves a distributed-edge invariant beyond engine/server result convergence |
| `SdkPollingMessageRetryExhaustedChaosRunner` | scheduled/manual chaos support | retry exhaustion through repeated polling failure | connect to a generic retry-budget fault profile before promotion |
| `SdkPollingLeaseExpiryRedispatchChaosRunner` | PR chaos smoke | polling stall/drop-result -> lease expiry -> takeover | keep as canonical `fault.stall-lease-takeover` seed |
| `SdkWebSocketDisconnectChaosRunner` | scheduled/manual chaos support | websocket disconnect/reconnect around active work | reduce to one crisp transport-churn invariant before PR promotion |
| `SdkWebSocketLeaseExpiryRedispatchChaosRunner` | PR chaos smoke | websocket disconnect without result -> lease expiry -> takeover | align with `fault.transport.disconnect` + `fault.result.drop` |
| `SdkWebSocketLateResultAfterLeaseExpiryChaosRunner` | PR chaos smoke | late stale result after takeover finality | keep as canonical `fault.late-stale-result` seed |
| `TaskWorkloadMixSmokeRunner` | perf smoke | interactive lane dispatch under bulk pressure | add non-ideal slow/noisy bulk worker profile |
| `TaskInteractiveRetryWakeupSmokeRunner` | perf smoke | delayed interactive retry wakeup under bulk pressure | add jittered retry wakeup profile |
| `TaskFlowLoadModelRunner` | full perf model | callback/progress/release cost model | add configurable worker delay distribution |
| `SdkTransportLoadRunner` | SDK transport load | polling/websocket/socket load and delivery diagnostics | add transport-level fault rows by mode |
| `SdkPollingSchedulingSoakRunner` | polling soak | long-running polling scheduling, failure profiles, late worker join | add noisy worker fleet and result-loss profiles |

### PR Gate Candidates

These should be deterministic and fast enough for normal CI once implemented.

| Scenario id | Shape | Expected proof | Minimum invariant |
| --- | --- | --- | --- |
| `fault.slow-success-before-lease` | slow worker finishes near lease boundary | task terminal success, no retry, no active lease | terminal task status, retry counter unchanged, active leases = 0, visible result count = expected work items |
| `fault.stall-lease-takeover` | first worker stalls, second worker finishes after expiry | retry reset, attempt count increments, terminal success | terminal task status, retry/attempt counter increments, original lease expired, takeover worker final result visible once, active leases = 0 |
| `fault.late-stale-result` | original worker submits after takeover terminal success | no reopened task, no counter mutation | terminal task status unchanged, stale result rejected or no-op, visible result count unchanged, trace/analyzer records stale path |
| `fault.duplicate-result` | worker submits duplicate final result | idempotent finality, visible result once | terminal task status, duplicate final receipt is idempotent, visible result count = 1 per work item, no extra active lease |
| `fault.dropped-result-retry` | result is dropped once, work expires and redispatches | retry path and active lease drain | lease expiry observed, redispatch observed, terminal task status, final result visible once, active leases = 0 |
| `fault.wrong-identity-rejected` | result has wrong worker/message/lease identity | ingest rejected or accepted no-op, no task corruption | rejection/no-op issue code, task status unchanged until valid result, counters unchanged except expected rejection evidence, trace records invalid identity |

PR candidates should be promoted in this order:

1. map an existing PR chaos probe to a scenario id without behavior change
2. make the scenario id visible in the report
3. move shared setup/assertion code behind a matrix row
4. only then add a new fault row

### Scheduled Chaos Candidates

| Scenario id | Shape | Expected proof |
| --- | --- | --- |
| `fault.noisy-worker-fleet` | mixed worker profiles, seeded random delay/drop/duplicate | all tasks converge or report bounded expected failures |
| `fault.presence-flap-under-load` | workers flap online/offline while tasks arrive | no stale worker takeover, no orphaned ready work |
| `fault.bulk-interactive-isolation` | slow bulk lane plus interactive lane | interactive latency stays bounded |
| `fault.redis-runtime-restart-recovery` | Redis runtime profile with restart/reconnect | retry/dispatch recovery preserves invariants |
| `fault.trace-under-chaos` | high event count with trace enabled | trace validates, dropped count explained |
| `fault.redis-partition-recovery` | Redis runtime connection interruption or failover drill | scenario reports bounded recovery or explicit unsupported failure mode |
| `fault.lease-clock-skew` | lease expiry observes non-monotonic or skewed clock input | no double assignment; skew behavior is explicit and bounded |
| `fault.multi-node-presence-flap` | same worker identity flaps across node/presence observations | no stale routing, duplicate assignment, or orphaned ready work |

Scheduled candidates should reuse soak and SDK transport load before adding a
new runner:

- noisy polling fleets belong first in `SdkPollingSchedulingSoakRunner`
- transport-specific churn belongs first in `SdkTransportLoadRunner`
- latency and retry wakeup distributions belong first in perf smoke / perf
  model

### Manual Soak Candidates

| Scenario id | Shape | Expected proof |
| --- | --- | --- |
| `fault.long-run-noisy-polling` | polling workers with mixed delays and loss over 30m+ | no runtime residue, stable result windows |
| `fault.long-run-realtime-churn` | websocket/socket reconnect churn | delivery and result ingest remain bounded |
| `fault.archive-under-fault-load` | high result count plus failures | sequential read and archive behavior stay correct |
| `fault.redis-failover-drill` | Redis partition/failover or external restart drill | recovery behavior is explicit; no silent double assignment |

## 9. Assertion Surface

Every worker-fault matrix scenario should assert through these surfaces:

- task aggregate status and terminal reason
- `TaskWorkRuntime` counters
- active lease drain
- recent final receipt where applicable
- visible result window and result count where applicable
- worker receive/result metrics
- canonical `ExecutionEvent` trace
- named trace analyzer when the scenario has a stable sequence contract

Each scenario should declare which runtime, result, delivery, and trace fields
are pass/fail evidence.

## 10. Implementation Plan

Execution rule:

- WF-0 and WF-1 are baseline convergence work and must stay ahead of behavior
  changes that depend on scenario ids or report-visible matrix axes
- infra-fault fast track is independent of the worker-pack `fault.*` framework:
  run WF-11 first, then WF-9, then WF-10
- WF-11 comes first because trace completeness is the proof system's evidence
  gate; absence-based analyzer claims are unsafe until dropped-event semantics
  are explicit
- WF-9 follows because proof-ledger closure should fail before more registry
  claims are added
- WF-10 follows because Redis restart recovery is a real distributed-runtime
  gap and can reuse existing retry/redispatch proof surfaces without waiting for
  worker-profile DSL work
- WF-2 and WF-3 are worker-pack / test-harness surfaces only; they must not add
  engine or transport runtime ownership and must not block WF-9 through WF-11
- WF-4 is the point where existing proof lines begin moving behind matrix rows
- WF-5 and later add new worker-behavior coverage only after report-visible
  parity
- WF-10 and later infra-fault rows must keep Redis, trace, transport, and
  runtime ownership explicit instead of smuggling infra behavior into engine
  state-machine tests

### WF-0: Proof-Line Scenario Ledger

Create a small matrix ledger in `xa-mass-testing` that starts from the current
five proof lines and maps every existing runner, trace profile, and analyzer
plan to a scenario id. The ledger must be a shared Java source of truth, not
only a Markdown table.

Acceptance:

- no behavior change
- add a shared `WorkerFaultScenarioIndex`-style class or enum in
  `xa-mass-testing` that owns scenario ids, current proof-line owner, runner
  family, and trace analyzer scenario mappings
- `ChaosTraceAnalysisPlanner` and `SoakTraceAnalysisPlanner` reference the
  shared index instead of keeping unrelated local scenario-id truth
- every PR chaos smoke runner maps to a scenario id
- perf smoke, full perf model, SDK transport load, and polling soak each map
  their current runner/profile shape to one or more scenario ids
- every chaos trace profile and soak analyzer plan maps to the scenario ids that
  currently consume it
- report names keep the current proof-line owner visible; scenario ids do not
  hide whether the proof came from PR chaos, perf, transport load, or soak
- missing high-value rows are explicit
- CI placement remains unchanged

### WF-1: Report Schema Alignment

Make the existing proof lines report scenario ids and matrix axes consistently.
This should happen before behavior changes so reports can prove migration
parity.

Acceptance:

- chaos reports flatten to a consistent top-level shape that includes
  `scenarioId`, `transport`, `runtimeBackend`, `workerProfile`, and
  `faultShape`
- existing nested `runtime.transport` report shapes are normalized to the same
  top-level fields; reports must not leave polling, websocket, and lease-expiry
  runners with different schema shapes for the same matrix axes
- perf smoke reports include the same fields where applicable, without
  pretending to be chaos proof
- SDK transport load reports include transport mode, worker profile, delivery
  fault shape, and runtime work summary
- soak `proof` bundle includes matrix profile identifiers for failure profile,
  late-worker profile, processing delay, and jitter
- report schema keeps pass/fail evidence fields explicit

### WF-2: Worker Fault Profile Model

Add a reusable fault profile model to worker-pack sample state.

Framework gate:

- do not expand the shared profile model just because the matrix axes are
  available on paper
- prefer direct runner-local setup when a new scenario can be expressed cleanly
  without a shared DSL
- expand this profile model only after at least two new PR-gate candidate rows
  would otherwise duplicate incompatible delay, drop, duplicate, malformed, or
  disconnect semantics across proof lines
- the deliverable is cleaner proof rows, deterministic replay, and shared report
  semantics; the profile framework itself is not proof

Acceptance:

- existing `mock.*` behavior remains available
- the profile model lives in worker-pack sample state or the testing harness,
  not in engine or transport runtime ownership
- profiles support deterministic seed, delay distribution, result drop,
  duplicate submit, malformed result, and disconnect phase
- default worker behavior remains stable unless a fault profile is configured
- soak and perf can reuse the same naming even when their implementation remains
  module-local

### WF-3: Fault Event Surface And Worker Jitter

Add `fault.*` sample/worker events that configure profile and phase behavior.
Use this to make worker execution non-ideal through normal command paths rather
than by mutating runtime internals.

Framework gate:

- do not add a new `fault.*` command unless an active scenario needs to express
  that behavior through a normal worker-pack or harness owner surface
- if a hand-written chaos runner can express the scenario with lower owner
  risk, use the runner first and add the command later only when reuse is proven
- the `fault.*` surface must reduce duplicated scenario setup or make seed
  replay/reporting materially clearer; otherwise it is infrastructure for
  infrastructure

Acceptance:

- fault configuration enters through sample-worker `CommandRegistry` commands,
  not task work-item dispatch
- the harness may send `fault.*` commands before dispatch or during a scenario;
  the worker stores command state and applies it when the next matching worker
  execution, result-submit, state-report, or capability-report phase occurs
- worker state, capacity, and reachability effects are applied through existing
  owner surfaces, not direct runtime mutation; capacity flap stays deferred
  while there is no public capacity owner surface
- `fault.worker.state.flap` is a stateless one-shot command driven by the
  harness; repeated flap loops are modeled by repeated commands, not by a
  worker-local timer
- fault state can be read and reset
- event names are grep-friendly and documented
- invalid fault config fails fast with explicit error codes
- existing `mock.*` event names are not expanded for new fault families
- profiles can add bounded deterministic jitter to receive, execution, and
  result-submit phases

### WF-4: Existing Runner Migration

Introduce matrix execution gradually. Prefer adapting current proof lines before
adding a new standalone runner.

Acceptance:

- the current PR chaos probes and scheduled/manual chaos support probes can run
  from scenario ids while preserving their current assertions and report evidence
- SDK transport load can select transport fault rows by scenario id
- soak can select worker fleet/failure/jitter profiles by scenario id
- reports support deterministic seed replay
- existing hand-written chaos runners remain valid until replaced by equivalent
  matrix rows with the same proof surface

### WF-5: New Fault Rows On Current Proof Lines

Add new fault rows only after existing proof migration is report-visible.
Prefer extending the scheduled/manual lines first when the row has timing,
pressure, or transport-churn risk.

Acceptance:

- noisy worker fleet and result-loss profiles run first through polling soak
- transport churn rows run first through SDK transport load
- slow/noisy bulk plus interactive isolation runs first through perf smoke or
  full perf model
- each new row has a deterministic seed and issue code before promotion
- failures include the same runtime, result, delivery, and trace surfaces as
  the owning proof line

### WF-6: PR Fault Gate

Promote only stable deterministic matrix rows into the existing `chaos-smokes`
gate.

Acceptance:

- bundle preserves or replaces the current PR-gated distributed-edge chaos probes
  with equivalent scenario ids
- runtime/aggregate/trace are the proof surface
- report artifacts are uploaded by CI
- source guard keeps pass/fail proof on the declared runtime, aggregate, and
  trace evidence
- every promoted row is first validated in scheduled/manual runs

### WF-7: Scheduled Fault Chaos And Soak

Add larger combinations to scheduled lanes by extending current soak, perf, and
transport-load proof lines.

Acceptance:

- noisy worker fleet and result-loss profiles run through polling soak
- transport churn rows run through SDK transport load
- slow/noisy bulk plus interactive isolation runs through perf smoke or full
  perf model
- reports use the same schema as the PR bundle
- failures include stable issue codes

### WF-8: Boot-Shell Representative Fault E2E

Promote one or two representative fault cases into `xa-mass-server` E2E only
when real host wiring is the risk.

Acceptance:

- no full matrix moves into server E2E
- server tests prove real HTTP/SDK/transport wiring for selected faults
- proof ownership stays linked from `PROOF_REGISTRY.md` if the scenario becomes
  a critical invariant

### WF-9: Proof Registry Closure Guard

Add a reverse proof-ledger guard so `doc/PROOF_REGISTRY.md` cannot name
nonexistent proof surfaces.

This is a guard slice, not a new proof lane. Existing guards already prevent
some weak or duplicate tests from entering mainline suites; this slice checks
the opposite direction: registry claims must resolve to real analyzer ids and
real classes.

Acceptance:

- parse the `doc/PROOF_REGISTRY.md` critical-invariants table enough to extract
  rows, status, trace analyzer ids, and named Java proof classes
- for `status=covered` rows, primary, representative integrated, and trace
  proof cells must be non-empty and must not be placeholder-only prose
- analyzer ids named with `analyzer <id>` or equivalent registry wording must
  resolve through `TraceScenarioRegistry.require(id)`
- named chaos runner, suite, and integration-test classes in proof cells must
  exist on the test classpath or be explicitly marked as non-class prose
- the guard must not enforce `Last updated` against git history; that check is
  too brittle for rebases, shallow clones, and timezone differences
- failure messages identify the invariant id and the unresolved analyzer or
  class token

### WF-10: Redis Runtime Restart Recovery Chaos

Promote Redis runtime restart recovery from contract/E2E coverage into a
distributed-edge chaos row when restart is the injected failure mode.

This row belongs to the existing `sched.retry-redispatch` invariant. It must not
create a Redis-specific invariant id unless restart recovery proves a distinct
scheduling rule that the current retry/redispatch invariant cannot express.

Acceptance:

- add a scheduled/manual chaos runner or scenario row for
  `fault.redis-runtime-restart-recovery`
- the scenario runs a full task flow through normal task, dispatch, runtime,
  result, and trace paths while Redis runtime state is restarted or
  reconnected during active work
- proof uses runtime counters, active lease drain, final result visibility,
  task terminal state, and trace evidence
- reuse `lease-expiry-redispatch` or add a narrowly named analyzer only if the
  restart sequence has a distinct observable contract
- update `doc/PROOF_REGISTRY.md` so Redis restart is described as current
  contract/E2E coverage until the chaos runner lands, and as distributed-edge
  proof only after it lands
- CI placement starts as scheduled/manual; PR promotion requires deterministic
  runtime and environment control

### WF-11: Trace Overflow Incomplete-Proof Semantics

Prove analyzer behavior when trace sink overflow causes missing events.

Sink-level tests already cover `DROP` and `FALLBACK_SYNC` write behavior. This
slice covers proof semantics: an analyzer must either make a valid decision from
the remaining trace or report an explicit incomplete/failure result. It must not
silently pass a scenario whose required evidence was dropped.

The critical case is absence-based proof. A missing required event is usually a
safe failure, but an analyzer that proves "event X did not happen" cannot
distinguish a true absence from a dropped event unless it first consumes a trace
completeness signal. For those analyzer paths, `droppedCount` or an equivalent
sequence/watermark completeness signal is part of the proof gate, not report
metadata.

Acceptance:

- add a trace/operator test or trace-observed fixture that removes required
  events from a known scenario trace
- analyzer output must not silently pass when required events are absent
- analyzer paths that use absence assertions such as unexpected-event rejection
  must require trace completeness first; if completeness is unknown or
  `droppedCount > 0`, they must return an explicit incomplete/failure result
  instead of pass
- chaos runners that execute named trace analyzers must treat trace
  completeness as a proof input, not only as report metadata; current
  `droppedCount` fields are insufficient when they do not gate analyzer pass
- introduce or reuse an explicit incomplete/failure issue code such as
  `trace-incomplete` if current analyzer result shape supports it
- `FALLBACK_SYNC` path proves full trace preservation for the selected fixture
- `DROP` path proves either correct remaining-evidence decision or explicit
  incomplete/failure decision
- update `TRACE_CONTRACT.md` only if a new canonical incomplete result code is
  introduced

### WF-12: Scheduled Infra Fault Rows

Add higher-cost infra-fault rows only after WF-9 through WF-11 clarify proof
ownership and report semantics.

Acceptance:

- Redis partition/failover rows remain scheduled/manual until environment
  control is deterministic
- lease-clock skew/non-monotonic clock behavior is tested through an explicit
  clock/runtime seam, not by mutating runtime internals after the fact
- multi-node transport presence flap uses transport/node presence owner
  surfaces; it must not directly mutate scheduling runtime state
- each row records whether failure means platform bug, unsupported environment
  condition, or incomplete proof evidence
- no row is promoted to PR until it has deterministic setup, bounded runtime,
  stable issue codes, and report-visible scenario metadata

## 11. CI Placement

Recommended placement:

| Lane | Content |
| --- | --- |
| PR `scheduling-core` | deterministic kernel surrogates only when engine invariants change |
| PR `server-scheduling-e2e` | at most representative host-wiring fault cases |
| PR `chaos-smokes` | current distributed-edge chaos probes first; scenario-id bundle only after WF-4 parity |
| scheduled/manual `perf-smokes` | current workload mix and retry wakeup proof plus latency/delay distribution regression |
| scheduled/manual `sdk-transport-load` | polling/websocket/socket delivery diagnostics plus transport-churn fault rows |
| scheduled/manual polling soak | current runtime/result/trace proof plus noisy fleet, result loss, and late-worker profiles |
| scheduled/manual infra-fault | Redis runtime restart recovery, trace overflow proof semantics, Redis partition/failover, lease-clock skew, and multi-node presence flap |
| manual | large matrix sweeps, Redis/failover drills, and overnight soak |

Socket fault rows start in scheduled/manual `sdk-transport-load`. They must not
be promoted into PR `chaos-smokes` until `SdkTransportLoadRunner` has equivalent
transport-churn evidence and the matrix row carries the same assertion and trace
surface as an existing PR probe.

Do not remove a current proof-line runner from CI because a scenario id exists.
Removal is allowed only after the scenario row carries the same setup, assertion
surface, trace/analyzer evidence, artifact shape, and source guard.

## 12. Non-Goals

This roadmap does not introduce:

- a full Cartesian product matrix in PR
- runtime decisions driven by test-only fault events
- test-only compatibility wrappers around engine or transport
- a second worker protocol
- direct runtime mutation as the primary scenario setup
- server E2E as the home for every worker fault case

## 13. Documentation Updates When Implemented

When this roadmap starts landing, update:

- `doc/TESTING_INDEX.md` for lane placement and minimum verification
- `doc/PROOF_REGISTRY.md` for promoted critical invariants and for wording that
  distinguishes current contract/E2E coverage from distributed-edge chaos proof
- `xa-mass-testing/README.md` for runner commands and report schema
- `integrations/xa-mass-worker-pack/README.md` for `fault.*` worker event behavior
- `doc/TRACE_CONTRACT.md` if new canonical event fields are required
