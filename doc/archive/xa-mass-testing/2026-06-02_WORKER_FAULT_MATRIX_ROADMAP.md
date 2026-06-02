# Worker Fault Matrix Roadmap

Last updated: 2026-06-02

Archive status: archived implemented matrix mainline on 2026-06-02.

Current active decision:
`../../../roadmap/WORKER_FAULT_MATRIX_INFRA_FAULT_DECISION.md`.

Status: archived implementation record. The remaining WF-12 infra-fault rows
were split out because Redis partition/failover, lease-clock skew, and
multi-node presence flap need environment or seam decisions before they can
become honest worker-fault proof.

This roadmap records the current worker-fault proof matrix and the remaining
work needed to keep resource and worker-fault proof systematic. WF-12 is not
ready for implementation as written because its remaining infra rows require
environment or seam decisions before they can become honest worker-fault proof.

This is partly current implementation and partly remaining roadmap. Current
facts are called out explicitly below; remaining WF-* sections are not proof
that the implementation already has every row, runner, or CI gate.

Current implementation state:

- WF-0 is landed for the current ledger scope: `WorkerFaultScenarioIndex` owns
  20 current scenario ids, proof line owners, runner families, matrix axes, and
  trace analyzer mappings; `ChaosTraceAnalysisPlanner` and
  `SoakTraceAnalysisPlanner` already resolve analyzer ids through the shared
  index.
- WF-1 is landed for current report families: chaos, perf, transport-load, and
  soak reports write matrix metadata through `WorkerFaultReportMetadata`.
  `WorkerFaultReportMetadata.merge` rejects conflicting matrix fields.
  `SdkTransportLoadRunner` keeps the aggregate `sdk-transport-load` row's
  scenario-axis `transport=multi` separate from actual run transport via
  `actualTransport`, writes mode-specific rows such as
  `sdk-transport-load-polling` with top-level `transport=polling`, and records
  WebSocket churn metrics for `sdk-transport-load-websocket-churn`.
- WF-2 is landed for the worker-pack sample surface: `SampleWorkerFaultProfile`
  owns deterministic profile state for delay, stall, drop, duplicate, late,
  malformed, wrong-identity, and disconnect behavior.
- WF-3 is largely landed except capacity control: worker-pack sample commands
  expose the current `fault.*` execution/result/transport/state controls.
  `fault.worker.capacity.flap` is intentionally not implemented until an
  explicit capacity owner surface exists; do not fake it through capability
  attributes.
- WF-4 is partially landed: existing proof lines can be entered by scenario id,
  `run-worker-fault-scenario.sh` exposes a single-scenario entrypoint, and
  `list-worker-fault-scenarios.sh` / `WorkerFaultScenarioCli --list` expose the
  current Java ledger rows. Polling soak processing jitter is now deterministic
  from `mass.soak.processingJitterSeed` and writes that seed into `config` and
  `proof.matrixProfile`. `SdkPollingSchedulingSoakRunner` accepts
  `mass.soak.scenarioId` for current polling soak rows, including the
  `polling-soak-noisy-mixed-result` row. `SdkTransportLoadRunner` accepts
  `mass.sdk.load.scenarioId` for current SDK transport load rows, including
  mode-specific polling/websocket/socket diagnostic rows. Remaining migration
  work is replacing only those hand-written runner paths that have equivalent
  matrix-row proof surface.
- WF-9 first slice is landed: `ProofRegistryClosureGuardTest` parses
  `doc/PROOF_REGISTRY.md` critical-invariant rows, checks required proof cells
  for covered rows, verifies named analyzer ids through `TraceScenarioRegistry`,
  and verifies named proof class tokens exist in the source tree.
- WF-10 first slice is landed and runtime-verified:
  `polling-redis-restart-recovery` is in the Java ledger, and
  `SdkPollingRedisRestartRecoveryChaosRunner` runs as a scheduled/manual infra
  chaos row that rebuilds the Redis-backed runtime owner with the same Redis
  namespace during active leased work. This is Redis runtime owner
  restart/reconnect proof, not Redis process kill, partition, or failover
  proof.
- WF-11 is landed for current trace overflow proof semantics: trace scenario
  analysis accepts an optional `droppedCount` completeness signal, emits
  `TRACE_INCOMPLETE` instead of pass when known dropped events make analyzer
  proof unsafe, and current chaos runners that invoke named analyzers pass their
  trace dropped count into that gate. Trace operator integration fixtures cover
  direct `DROP` overflow and `FALLBACK_SYNC` preservation behavior against a
  named analyzer.
- WF-5 has current rows on existing proof lines:
  `polling-soak-noisy-mixed-result` selects seeded jitter and every-N synthetic
  failure through `mass.soak.scenarioId`; `fault.dropped-result-retry` is a
  scenario-ledger alias over `SdkPollingLeaseExpiryRedispatchChaosRunner`, where
  the first polling worker claims work, stalls without submitting a result, the
  lease expires, and a steady worker completes takeover.
- WF-6 is landed for the current PR gate scope: `run-chaos-smokes.sh` resolves
  the three CI-gated distributed-edge probes through `WorkerFaultScenarioCli`,
  enforces source guardrails before running them, and
  `.github/workflows/maven.yml` uploads `target/chaos-reports`. The Java ledger
  may list additional chaos rows under `ProofLineOwner.PR_CHAOS_SMOKE`; that
  owner label is not the current CI bundle membership.
- WF-7 has current scheduled/manual rows:
  `workload-mix-slow-bulk-interactive-isolation` selects the workload-mix
  runner through `mass.workload.smoke.scenarioId`, uses worker-group capability
  truth for project support, and reports `workerProfile=SLOW_BULK` plus
  `faultShape=slow-bulk-interactive-isolation`. `sdk-transport-load-websocket-churn`
  selects the SDK transport-load runner through `mass.sdk.load.scenarioId`,
  performs a real WebSocket close/reconnect through the transport client
  surface, and reports `workerProfile=FLAKY_TRANSPORT`,
  `faultShape=transport-connection-churn`, plus churn disconnect/reconnect
  counters.
- WF-8 is closed for current scope without adding a new server E2E: current
  representative host/runtime proof already lives in the proof-registry E2E
  chains for retry redispatch, delayed worker availability, callback replay,
  mixed/all-failed results, and resource reuse. `TaskApiTargetedWorkerDebugIntegrationTest`
  remains secondary debug-task support and is not promoted as mainline fault
  proof.
- WF-12 remains a decision point rather than executable implementation work:
  Redis process kill/partition/failover, lease-clock skew, and multi-node
  presence flap are not complete, and current code does not yet provide the
  deterministic environment or owner seams needed to prove them as worker-fault
  matrix rows.
- Infra-fault rows are split: Redis runtime owner restart/reconnect currently
  has Redis runtime contract coverage, representative Redis E2E coverage, and a
  Redis-backed chaos proof. Redis process kill, partition/failover,
  non-monotonic lease-clock behavior, and multi-node transport presence flap
  are not current distributed-edge proof and need the WF-12 decision before
  implementation.

## 1. Current Boundary

The current testing system has useful proof ownership:

- `xa-mass-engine` owns deterministic scheduling and kernel proof
- `xa-mass-server` owns representative Boot-shell E2E proof
- `xa-mass-testing` owns chaos, soak, SDK transport, and perf lanes
- `xa-mass-trace` owns canonical observational proof

Current worker-behavior distributed-edge proof is real across lease expiry,
redispatch, retry exhaustion, disconnect, stale replay, all-failed convergence,
mixed-result convergence, and polling soak backfill. Future rows must keep the
proof rows report-visible, seed-replayable, and bounded by the same runtime,
aggregate, and trace evidence when new worker or infra fault rows are added.

Current risk areas:

- future noisy or transport-churn expansions must not become unowned one-off
  runners
- Redis process kill, partition/failover, lease-clock skew, and multi-node
  presence flap are not current distributed-edge proof rows
- trace incompleteness must keep absence-based analyzer claims from passing
  silently
- scenario-id and matrix-axis reporting must remain consistent as old runner
  paths are migrated

## 2. Active Work Shape

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

New rows must preserve current proof ownership:

- engine tests prove deterministic kernel invariants
- worker-fault matrix runners prove full task-flow behavior under non-ideal
  worker and resource conditions
- Boot-shell E2E carries only representative fault cases that need real host
  wiring
- scheduled chaos and soak carry larger combinations, pressure, and long-tail
  timing

## 3. Current Testing Proof Lines

`xa-mass-testing` already has several concrete proof lines. The fault matrix
extends these lines instead of creating a parallel testing universe.

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
- Redis runtime owner restart/reconnect recovery is covered by Redis runtime
  contract tests, representative Redis E2E, and
  `SdkPollingRedisRestartRecoveryChaosRunner` as a scheduled/manual
  Redis-backed chaos proof for active leased work takeover
- Redis process kill, Redis partition/failover, and non-monotonic lease-clock
  behavior are not current proof rows
- trace sink overflow has sink-level `DROP` / `FALLBACK_SYNC` behavior tests
  and trace-operator analyzer fixtures proving that dropped events produce an
  explicit `TRACE_INCOMPLETE` gate while `FALLBACK_SYNC` preserves enough trace
  evidence for analyzer pass
- chaos reports record trace `droppedCount`; current named chaos analyzer calls
  pass it into `TraceAnalyzeRequest`, and `droppedCount > 0` produces
  `TRACE_INCOMPLETE` instead of analyzer pass. Polling soak already reports
  dropped trace events as an invariant issue
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

The current reusable fault-event namespace is worker-pack / sample-worker /
test-harness owned.

Fault events travel through normal platform paths. Tests configure a worker or
task scenario through SDK/API/event surfaces, then run a full task flow. Tests
must not mutate runtime internals to manufacture the main scenario.

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
They must not be expanded for new matrix rows. New matrix scenarios must use
`fault.*` names so grep points agents to the fault-test model rather than
generic sample behavior. Once `fault.*` reaches parity for the rows that still
need `mock.*`, those old fixture controls can be removed or demoted from the
proof path instead of preserved as a second live track.

## 6. Worker Profiles

Worker profiles are reusable scenario inputs. Current implementation lives in
`SampleWorkerFaultProfile` for the worker-pack sample surface.

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

Profiles are deterministic with a seed so CI failures are reproducible.
Profiles are named configurations that expand to primitive `fault.*` controls.
Current sample profile semantics must remain inspectable by tests; future
expansion must not create incompatible profile meanings across chaos, soak,
perf, and worker-pack sample code.

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

The matrix is described through a small number of axes rather than through
unbounded one-off scenario classes.

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

Current slice rules:

- WF-0 and WF-1 are landed report/ledger convergence work and map existing
  proof lines without changing their behavior or CI placement
- current behavior-bearing `fault.*` implementation is worker-pack sample
  surface only; it does not add engine or transport ownership
- existing websocket chaos probes may keep running through their current
  hand-written path until equivalent matrix rows carry the same assertion and
  trace evidence
- socket fault rows are scheduled/manual only in the first slice; promotion to
  PR requires equivalent transport-churn evidence from `SdkTransportLoadRunner`
  first
- new runners are not introduced unless the existing proof lines can report
  scenario id and matrix axes consistently

## 8. Initial Scenario Set

The initial matrix must reuse current proof lines. Do not promote scheduled or
manual chaos runners into PR until the matrix row proves it carries a distinct
distributed-edge invariant with the same runtime, aggregate, and trace evidence.

### Existing Probe Mapping

| Existing probe / profile | Current proof line | Matrix row it already covers | Active follow-up |
| --- | --- | --- | --- |
| `SdkPollingAllMessagesFailedChaosRunner` | scheduled/manual chaos support | polling all-failed terminal convergence | keep out of PR unless it proves a distributed-edge invariant beyond engine/server result convergence |
| `SdkPollingMixedResultsChaosRunner` | scheduled/manual chaos support | polling mixed-result terminal convergence | keep out of PR unless it proves a distributed-edge invariant beyond engine/server result convergence |
| `SdkPollingMessageRetryExhaustedChaosRunner` | scheduled/manual chaos support | retry exhaustion through repeated polling failure | connect to a generic retry-budget fault profile before promotion |
| `SdkPollingLeaseExpiryRedispatchChaosRunner` | PR chaos smoke | polling stall/drop-result -> lease expiry -> takeover, including the current `fault.dropped-result-retry` scenario alias | keep as canonical stall/drop-result takeover proof; do not duplicate it with a second runner |
| `SdkWebSocketDisconnectChaosRunner` | scheduled/manual chaos support | websocket disconnect/reconnect around active work | reduce to one crisp transport-churn invariant before PR promotion |
| `SdkWebSocketLeaseExpiryRedispatchChaosRunner` | PR chaos smoke | websocket disconnect without result -> lease expiry -> takeover | align with `fault.transport.disconnect` + `fault.result.drop` |
| `SdkWebSocketLateResultAfterLeaseExpiryChaosRunner` | PR chaos smoke | late stale result after takeover finality | keep as canonical `fault.late-stale-result` seed |
| `TaskWorkloadMixSmokeRunner` | perf smoke | interactive lane dispatch under bulk pressure plus `workload-mix-slow-bulk-interactive-isolation` slow-bulk row | add only additional noisy/jitter profiles that need distinct perf evidence |
| `TaskInteractiveRetryWakeupSmokeRunner` | perf smoke | delayed interactive retry wakeup under bulk pressure | add jittered retry wakeup profile |
| `TaskFlowLoadModelRunner` | full perf model | callback/progress/release cost model | add configurable worker delay distribution |
| `SdkTransportLoadRunner` | SDK transport load | aggregate `sdk-transport-load`, mode-specific polling/websocket/socket delivery diagnostics rows, and `sdk-transport-load-websocket-churn` | future transport churn rows are added only when the runner injects real transport degradation |
| `SdkPollingSchedulingSoakRunner` | polling soak | long-running polling scheduling, failure profiles, late worker join, and `polling-soak-noisy-mixed-result` seeded jitter/failure row | add only soak-specific result-loss profiles; current dropped-result/retry proof is the polling lease-expiry chaos alias |

### PR Gate Candidates

Rows promoted to PR must be deterministic and fast enough for normal CI.

| Scenario id | Shape | Expected proof | Minimum invariant |
| --- | --- | --- | --- |
| `fault.slow-success-before-lease` | slow worker finishes near lease boundary | task terminal success, no retry, no active lease | terminal task status, retry counter unchanged, active leases = 0, visible result count = expected work items |
| `fault.stall-lease-takeover` | first worker stalls, second worker finishes after expiry | retry reset, attempt count increments, terminal success | terminal task status, retry/attempt counter increments, original lease expired, takeover worker final result visible once, active leases = 0 |
| `fault.late-stale-result` | original worker submits after takeover terminal success | no reopened task, no counter mutation | terminal task status unchanged, stale result rejected or no-op, visible result count unchanged, trace/analyzer records stale path |
| `fault.duplicate-result` | worker submits duplicate final result | idempotent finality, visible result once | terminal task status, duplicate final receipt is idempotent, visible result count = 1 per work item, no extra active lease |
| `fault.dropped-result-retry` | result is dropped once, work expires and redispatches | retry path and active lease drain | lease expiry observed, redispatch observed, terminal task status, final result visible once, active leases = 0 |
| `fault.wrong-identity-rejected` | result has wrong worker/message/lease identity | ingest rejected or accepted no-op, no task corruption | rejection/no-op issue code, task status unchanged until valid result, counters unchanged except expected rejection evidence, trace records invalid identity |

PR candidates are promoted in this order:

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

Scheduled candidates reuse soak and SDK transport load before adding a
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

Every worker-fault matrix scenario asserts through these surfaces:

- task aggregate status and terminal reason
- `TaskWorkRuntime` counters
- active lease drain
- recent final receipt where applicable
- visible result window and result count where applicable
- worker receive/result metrics
- canonical `ExecutionEvent` trace
- named trace analyzer when the scenario has a stable sequence contract

Each scenario declares which runtime, result, delivery, and trace fields
are pass/fail evidence.

## 10. Implementation Plan

Execution rule:

- WF-0 and WF-1 are baseline convergence work and must stay ahead of behavior
  changes that depend on scenario ids or report-visible matrix axes
- infra-fault fast track is independent of the worker-pack `fault.*` framework:
  WF-11, WF-9, and WF-10 first slices are now landed
- WF-11 comes first because trace completeness is the proof system's evidence
  gate; absence-based analyzer claims are unsafe until dropped-event semantics
  are explicit
- WF-9 follows because proof-ledger closure must fail before more registry
  claims are added
- WF-10 follows because Redis runtime owner restart/reconnect recovery reuses
  existing retry/redispatch proof surfaces without waiting for worker-profile
  DSL work
- WF-2 and WF-3 are worker-pack / test-harness surfaces only; they must not add
  engine or transport runtime ownership and must not block WF-9 through WF-11
- WF-4 is the point where existing proof lines begin moving behind matrix rows
- WF-5 and later add new worker-behavior coverage only after report-visible
  parity
- WF-10 and later infra-fault rows must keep Redis, trace, transport, and
  runtime ownership explicit instead of smuggling infra behavior into engine
  state-machine tests

### WF-0: Proof-Line Scenario Ledger

Current implementation is a shared Java ledger in `xa-mass-testing`.
`WorkerFaultScenarioIndex` maps current runners, trace profiles, and analyzer
plans to scenario ids, proof owners, runner families, matrix axes, and analyzer
ids. The ledger is source truth for current scenario rows; this Markdown file is
only explanatory.

Current acceptance:

- no behavior change
- shared `WorkerFaultScenarioIndex` owns scenario ids, current proof-line
  owner, runner family, and trace analyzer scenario mappings
- `ChaosTraceAnalysisPlanner` and `SoakTraceAnalysisPlanner` reference the
  shared index instead of keeping unrelated local scenario-id truth
- every PR chaos smoke runner maps to a scenario id
- perf smoke, full perf model, SDK transport load, and polling soak each map
  their current runner/profile shape to one or more scenario ids
- every chaos trace profile and soak analyzer plan maps to the scenario ids that
  currently consume it
- report names keep the current proof-line owner visible; scenario ids do not
  hide whether the proof came from PR chaos, perf, transport load, or soak
- active high-value rows are explicit
- CI placement remains unchanged

### WF-1: Report Schema Alignment

Current proof lines report scenario ids and matrix axes consistently enough for
current landed runner families. Report schema must stay stable as new rows are
added.

Current acceptance:

- chaos reports flatten to a consistent top-level shape that includes
  `scenarioId`, `transport`, `runtimeBackend`, `workerProfile`, and
  `faultShape`
- nested runtime diagnostics such as `runtime.transport` remain allowed, but
  they must be clearly diagnostic fields and must not compete with top-level
  matrix axes
- `SdkTransportLoadRunner` must distinguish scenario-axis transport (`multi`)
  from actual run transport (`polling`, `websocket`, or `socket`) instead of
  using one ambiguous top-level `transport` field for both meanings
- perf smoke reports include the same fields where applicable, without
  pretending to be chaos proof
- SDK transport load reports include actual transport mode, worker profile,
  delivery fault shape, and runtime work summary without overwriting the
  scenario-axis metadata
- soak `proof` bundle includes matrix profile identifiers for failure profile,
  late-worker profile, processing delay, and jitter
- report schema keeps pass/fail evidence fields explicit

Current verification:

- `WorkerFaultReportMetadata.merge` rejects conflicting top-level matrix fields
  rather than silently allowing report bodies to overwrite `scenarioId`,
  `transport`, `runtimeBackend`, `workerProfile`, or `faultShape`
- `SdkTransportLoadRunner` keeps top-level `transport=multi` for the matrix
  aggregate scenario and writes the actual run mode as `actualTransport` at top
  level, under `config`, and under `runtime`
- `SdkTransportLoadRunner` accepts `mass.sdk.load.scenarioId` for current
  transport-load rows and rejects unknown, non-transport-load, or explicit
  transport-conflicting scenario ids
- mode-specific rows such as `sdk-transport-load-polling` write the concrete
  top-level `transport` axis and matching `actualTransport`
- verified polling transport-load report fields:
  `scenarioId=sdk-transport-load-polling`, `transport=polling`,
  `actualTransport=polling`, `runtimeBackend=memory`,
  `workerProfile=NORMAL`, and `faultShape=delivery-diagnostics`
- `sdk-transport-load-websocket-churn` enables one WebSocket close/reconnect
  cycle by default through the worker client surface and records churn counters
  under `workerMetrics`
- verified WebSocket churn report fields:
  `scenarioId=sdk-transport-load-websocket-churn`, `transport=websocket`,
  `actualTransport=websocket`, `runtimeBackend=memory`,
  `workerProfile=FLAKY_TRANSPORT`, `faultShape=transport-connection-churn`,
  `workerMetrics.transportChurnDisconnects=1`,
  `workerMetrics.transportChurnReconnects=1`, terminal task reason
  `ALL_MESSAGES_SUCCEEDED`, and `deliveryQueue.directFailedItems=0`

### WF-2: Worker Fault Profile Model

Current implementation has a reusable fault profile model in worker-pack sample
state.

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

Current acceptance:

- existing `mock.*` behavior remains available
- the profile model lives in worker-pack sample state or the testing harness,
  not in engine or transport runtime ownership
- profiles support deterministic seed, delay distribution, result drop,
  duplicate submit, malformed result, and disconnect phase
- default worker behavior remains stable unless a fault profile is configured
- soak and perf can reuse the same naming even when their implementation remains
  module-local

### WF-3: Fault Event Surface And Worker Jitter

Current `fault.*` sample/worker events configure profile and phase behavior
through normal command paths rather than by mutating runtime internals.

Framework gate:

- do not add a new `fault.*` command unless an active scenario needs to express
  that behavior through a normal worker-pack or harness owner surface
- if a hand-written chaos runner can express the scenario with lower owner
  risk, use the runner first and add the command later only when reuse is proven
- the `fault.*` surface must reduce duplicated scenario setup or make seed
  replay/reporting materially clearer; otherwise it is infrastructure for
  infrastructure

Current acceptance:

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

Matrix execution is being introduced gradually. Prefer adapting current proof
lines before adding a new standalone runner.

Active acceptance:

- the current PR chaos probes and scheduled/manual chaos support probes can run
  from scenario ids while preserving their current assertions and report evidence
- SDK transport load can select current transport diagnostic rows by scenario
  id inside the runner; future transport fault/churn rows must use the same
  entry mechanism rather than only external JVM/system properties
- soak can select worker fleet/failure/jitter profiles by scenario id inside
  the runner, not only through unrelated local flags
- reports support deterministic seed replay and record the seed/profile source
  used for the scenario row
- existing hand-written chaos runners remain valid until replaced by equivalent
  matrix rows with the same proof surface

Current landed slice:

- `SdkTransportLoadRunner` accepts `mass.sdk.load.scenarioId` for the current
  aggregate and mode-specific transport-load diagnostic rows
- current SDK transport-load mode rows are diagnostic rows only:
  `sdk-transport-load-polling`, `sdk-transport-load-websocket`, and
  `sdk-transport-load-socket`; they do not inject transport churn
- current SDK transport-load WebSocket churn row:
  `sdk-transport-load-websocket-churn` injects a real WebSocket close/reconnect
  cycle and records churn counters in `workerMetrics`
- polling soak processing jitter no longer uses `ThreadLocalRandom`; jitter is
  derived from `mass.soak.processingJitterSeed`, worker id, task id, message id,
  attempt id, and retry count
- `SoakConfig` accepts `mass.soak.scenarioId` for current polling soak scenario
  rows and rejects non-soak or unknown scenario ids
- `SoakConfig` writes `processingJitterSeed` into report `config`
- `proof.matrixProfile` writes `processingJitterSeed` beside
  `processingJitterMillis`, `failureProfile`, and `lateWorkerProfile`
- `SdkPollingSchedulingSoakRunner` passes trace sink `droppedCount` into named
  trace analyzer requests when trace is enabled
- verified minimal soak report fields:
  `proof.matrixProfile.scenarioId=polling-soak-noisy-mixed-result`,
  `workerProfile=NOISY`, `faultShape=noisy-mixed-result`,
  `failureProfile=every-5`, `processingJitterMillis=25`, and
  `processingJitterSeed=20260602`

### WF-5: New Fault Rows On Current Proof Lines

Active work adds new fault rows only after existing proof migration is
report-visible.
Prefer extending the scheduled/manual lines first when the row has timing,
pressure, or transport-churn risk.

Current landed slice:

- `polling-soak-noisy-mixed-result` is a scheduled/manual polling soak row in
  `WorkerFaultScenarioIndex`
- `mass.soak.scenarioId=polling-soak-noisy-mixed-result` selects current noisy
  defaults inside `SdkPollingSchedulingSoakRunner`: deterministic processing
  jitter seed `20260602`, jitter bound `25ms`, and `failureEveryNth=5`
- verified minimal report has one terminal mixed-result task with five visible
  results, four successes, one synthetic failure, active leases drained, and
  scenario/profile metadata in both `config` and `proof.matrixProfile`
- this row proves seeded noisy mixed-result soak behavior; it does not prove
  dropped-result/retry behavior
- `fault.dropped-result-retry` is a scenario-ledger alias over
  `SdkPollingLeaseExpiryRedispatchChaosRunner`; `WorkerFaultScenarioCli` passes
  the selected scenario id to the runner through `mass.workerFault.scenarioId`
  so the report uses `scenarioId=fault.dropped-result-retry`,
  `workerProfile=STALL_LEASE_TAKEOVER`, and `faultShape=dropped-result-retry`
- verified alias report reaches `ALL_MESSAGES_SUCCEEDED`, keeps active leases
  drained, records trace `droppedCount=0`, and runs analyzer
  `lease-expiry-redispatch` successfully

Active acceptance:

- noisy worker fleet and additional result-loss profiles run first through
  polling soak when they need soak-specific pressure or duration
- future transport churn rows run first through SDK transport load
- slow/noisy bulk plus interactive isolation runs first through perf smoke or
  full perf model
- each new row has a deterministic seed and issue code before promotion
- failures include the same runtime, result, delivery, and trace surfaces as
  the owning proof line

### WF-6: PR Fault Gate

Current implementation runs the existing stable distributed-edge probes through
scenario ids in the `chaos-smokes` gate. Future PR promotion is limited to rows
that are already stable in scheduled/manual runs.

Current landed slice:

- `run-chaos-smokes.sh` owns the current PR scenario-id bundle:
  `polling-lease-expiry-redispatch`, `websocket-lease-expiry-redispatch`, and
  `websocket-late-stale-result-replay`
- the script resolves each scenario id through `WorkerFaultScenarioCli` and the
  Java ledger instead of maintaining a separate runner class list
- the script enforces source guardrails before running scenarios so PR chaos
  proof stays runtime/aggregate/trace-first
- `.github/workflows/maven.yml` runs the bundle in the `chaos-smokes` job and
  uploads `**/target/chaos-reports/**`
- local direct `WorkerFaultScenarioCli` verification passed for all three
  current PR scenario ids

Active acceptance:

- bundle preserves or replaces the current PR-gated distributed-edge chaos probes
  with equivalent scenario ids
- runtime/aggregate/trace are the proof surface
- report artifacts are uploaded by CI
- source guard keeps pass/fail proof on the declared runtime, aggregate, and
  trace evidence
- every newly promoted row is first validated in scheduled/manual runs

### WF-7: Scheduled Fault Chaos And Soak

Active work adds larger combinations to scheduled lanes by extending current
soak, perf, and transport-load proof lines.

Current landed slice:

- `WorkerFaultScenarioIndex` includes
  `workload-mix-slow-bulk-interactive-isolation` as a `PERF_SMOKE` row on
  `TaskWorkloadMixSmokeRunner`
- `TaskWorkloadMixSmokeRunner` accepts `mass.workload.smoke.scenarioId` for
  current workload-mix rows and writes the selected scenario into top-level
  matrix metadata and report `config`
- workload-mix perf matching reads project support from WorkerGroup capability
  truth through `WorkerSchedulingViewRuntime.workerGroupReadView(...)`; it no
  longer depends on worker declaration residue for supported projects
- verified slow-bulk report fields:
  `scenarioId=workload-mix-slow-bulk-interactive-isolation`,
  `workerProfile=SLOW_BULK`, `faultShape=slow-bulk-interactive-isolation`,
  `interactiveDispatchedBeforeBulkTerminal=true`, and both bulk and
  interactive tasks terminal with `ALL_MESSAGES_SUCCEEDED`
- `WorkerFaultScenarioIndex` includes `sdk-transport-load-websocket-churn` as
  an `SDK_TRANSPORT_LOAD` row on `SdkTransportLoadRunner`
- `SdkTransportLoadRunner` enables transport churn for that row through
  `mass.sdk.load.scenarioId`, performs a real WebSocket close/reconnect, and
  writes the selected scenario plus churn counters into the report
- verified WebSocket churn report fields:
  `scenarioId=sdk-transport-load-websocket-churn`,
  `workerProfile=FLAKY_TRANSPORT`,
  `faultShape=transport-connection-churn`,
  `workerMetrics.transportChurnDisconnects=1`,
  `workerMetrics.transportChurnReconnects=1`,
  `tasks.terminalReasons.ALL_MESSAGES_SUCCEEDED=1`, and
  `deliveryQueue.directFailedItems=0`

Active acceptance:

- additional noisy worker fleet and result-loss profiles run through polling
  soak only when they need distinct evidence beyond the current seeded noisy
  mixed-result row
- future socket or long-run transport churn rows run through SDK transport load
  only when they inject real transport degradation, not only mode selection
- additional noisy bulk plus interactive isolation rows run through perf smoke
  or full perf model when they need distinct evidence beyond the current
  slow-bulk row
- reports use the same schema as the PR bundle
- failures include stable issue codes

### WF-8: Boot-Shell Representative Fault E2E

Current decision: do not add a new Boot-shell fault E2E for the current matrix
scope. The current representative host/runtime proof already exists in the
proof-registry server E2E chains; adding another server fault test now would
duplicate existing proof instead of covering a distinct host-wiring risk.

Current representative host proof:

- `TaskApiRetryRedispatchTraceObservedIntegrationTest` represents assignment
  retry/redispatch host runtime wiring for `sched.retry-redispatch`
- `TaskApiDelayedWorkerAvailabilityTraceObservedIntegrationTest` represents
  late-worker availability/backfill host wiring
- `TaskApiCallbackReplayTraceObservedIntegrationTest` represents duplicate
  callback idempotence on the server lifecycle path
- `TaskApiMixedResultsTraceObservedIntegrationTest` and
  `TaskApiAllMessagesFailedTraceObservedIntegrationTest` represent result
  terminal convergence on the server lifecycle path
- `TaskApiSingleWorkerReuseTraceObservedIntegrationTest` represents resource
  cleanup/reuse host wiring
- `TaskApiTargetedWorkerDebugIntegrationTest` remains `secondary-proof`
  debug-task support; it must not be promoted as mainline worker-fault proof

Active acceptance:

- no full matrix moves into server E2E
- add a new server E2E only when the risk is real HTTP/SDK/transport host
  wiring that is not already represented by the proof-registry E2E chain
- proof ownership stays linked from `PROOF_REGISTRY.md` if a future scenario becomes
  a critical invariant

### WF-9: Proof Registry Closure Guard

Current implementation has a reverse proof-ledger guard so
`doc/PROOF_REGISTRY.md` cannot name nonexistent proof surfaces.

This is a guard slice, not a new proof lane. Existing guards already prevent
some weak or duplicate tests from entering mainline suites; this slice checks
the opposite direction: registry claims must resolve to real analyzer ids and
real classes.

Current landed slice:

- `ProofRegistryClosureGuardTest` lives in `xa-mass-testing` so it can verify
  trace analyzer ids without adding server or SDK dependencies
- covered rows in the critical-invariants table must keep primary,
  representative integrated, and trace proof cells populated
- analyzer ids named as `analyzer <id>` in proof cells must resolve through
  `TraceScenarioRegistry.require(id)`
- Java proof tokens named as Test/Suite/Runner/Scenario/Guard classes must
  exist as source files somewhere in the repository
- the guard intentionally does not validate `Last updated` against git history
  and does not parse semantic prose as proof

Current acceptance:

- parses the `doc/PROOF_REGISTRY.md` critical-invariants table enough to extract
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

Current implementation has promoted Redis runtime owner restart/reconnect from
contract/E2E coverage into a scheduled/manual distributed-edge chaos row.

This row belongs to the existing `sched.retry-redispatch` invariant. It must not
create a Redis-specific invariant id unless restart recovery proves a distinct
scheduling rule that the current retry/redispatch invariant cannot express.

Current landed slice:

- `WorkerFaultScenarioIndex` includes `polling-redis-restart-recovery` as a
  `SCHEDULED_INFRA_CHAOS` row with Redis runtime backend and
  `lease-expiry-redispatch` analyzer pairing
- `ChaosRuntimeHarness` can create a Redis-backed polling runtime using
  `RedisTaskWorkRuntime` and `RedisTaskResultRuntime`
- `ChaosRuntimeHarness.restartPollingRedisRuntime()` closes the current SDK app
  and rebuilds the runtime owner with the same in-memory task shell store,
  trace sink, and Redis namespace
- `SdkPollingRedisRestartRecoveryChaosRunner` models the current
  restart/reconnect scope: active work is claimed, the first worker stalls, the
  Redis runtime owner is rebuilt with the same namespace, and a steady worker
  takes over after lease expiry

Current verification:

- local Maven exec verification has passed for the new runner and harness path
- verified run facts: terminal task status, `ALL_MESSAGES_SUCCEEDED`,
  `attempts=2`, `retryCount=1`, active lease drain, trace `droppedCount=0`,
  and analyzer `lease-expiry-redispatch` returned `ok=true`
- this slice does not inject Redis process kill, partition, or failover; those
  remain WF-12 infra-fault rows

Current acceptance:

- scheduled/manual chaos runner and scenario row exist for
  `fault.redis-runtime-restart-recovery`
- the scenario runs a full task flow through normal task, dispatch, runtime,
  result, and trace paths while Redis runtime state is restarted or
  reconnected during active work
- proof uses runtime counters, active lease drain, final result visibility,
  task terminal state, and trace evidence
- current proof reuses `lease-expiry-redispatch`; a narrowly named analyzer is
  only justified if a future restart sequence has a distinct observable
  contract
- `doc/PROOF_REGISTRY.md` describes Redis runtime owner restart/reconnect as
  current distributed-edge proof after verified chaos execution
- CI placement starts as scheduled/manual; PR promotion requires deterministic
  runtime and environment control

### WF-11: Trace Overflow Incomplete-Proof Semantics

Prove analyzer behavior when trace sink overflow causes missing events.

Current landed slice:

- `TraceAnalyzeRequest` carries an optional `droppedCount` completeness signal
  while preserving the existing three-argument request constructor
- `TraceOperatorService.analyze` appends `TRACE_INCOMPLETE` and returns
  `ok=false` when a caller reports `droppedCount > 0` for an otherwise passing
  analyzer result
- current chaos runners that execute named trace analyzers pass
  `traceArtifacts.droppedCount()` into the analyzer request, so dropped JSONL
  events are no longer report-only metadata for those proof paths
- `TraceOperatorServiceIntegrationTest` covers both direct overflow-policy
  paths: `DROP` produces a dropped-count completeness gate and `FALLBACK_SYNC`
  preserves the selected replay trace for analyzer pass
- `TRACE_CONTRACT.md` documents `TRACE_INCOMPLETE` as insufficient trace
  evidence rather than a direct runtime behavior failure

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

Current acceptance:

- trace/operator tests cover a known replay trace with overflow-policy behavior
  and analyzer completeness gating
- analyzer output does not silently pass when known dropped events make proof
  incomplete
- analyzer paths that use absence assertions such as unexpected-event rejection
  require trace completeness first; if completeness is unknown or
  `droppedCount > 0`, they must return an explicit incomplete/failure result
  instead of pass
- chaos runners that execute named trace analyzers must treat trace
  completeness as a proof input, not only as report metadata; current
  `droppedCount` fields are insufficient when they do not gate analyzer pass
- current incomplete issue code is `TRACE_INCOMPLETE`
- `FALLBACK_SYNC` path preserves enough trace evidence for the selected fixture
  to pass
- `DROP` path produces explicit incomplete/failure decision when dropped events
  make proof unsafe
- `TRACE_CONTRACT.md` documents `TRACE_INCOMPLETE`

### WF-12: Scheduled Infra Fault Rows

WF-12 is a definition checkpoint. Higher-cost infra-fault rows must not be
implemented as worker-fault matrix rows until their environment and owner seams
are explicit enough to avoid fake proof.

Current definition issue:

- Redis process kill / partition / failover has no current deterministic
  test-harness environment in `xa-mass-testing`. The current Redis chaos row
  proves runtime owner restart/reconnect with the same Redis namespace; it does
  not kill Redis, partition Redis connectivity, or model failover.
- lease-clock skew / non-monotonic clock behavior has no explicit runtime clock
  seam for worker-fault matrix injection. Runtime and transport presence code
  still use current-time calls in multiple owners; a test that mutates runtime
  internals after the fact would violate this roadmap's own boundary rules.
- multi-node transport presence flap has transport-owned store semantics and
  stale connection protections at the transport layer, but there is no current
  worker-fault matrix harness that composes split transport nodes,
  node-targeted handoff, engine retry/compensation, and task convergence into
  one deterministic scenario.

Decision needed before implementation:

- choose whether WF-12 becomes a new infra-fault roadmap with a deterministic
  Redis partition/failover harness and explicit clock seams
- or narrow WF-12 to owner-local transport/runtime tests and manual drills
  without claiming distributed-edge worker-fault matrix proof
- or defer these rows until split-runtime and infra-drill setup are first-class
  test fixtures

Active acceptance:

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
| PR `chaos-smokes` | current distributed-edge chaos probes through scenario-id bundle; new rows only after scheduled/manual validation |
| scheduled/manual `perf-smokes` | current workload mix and retry wakeup proof plus latency/delay distribution regression |
| scheduled/manual `sdk-transport-load` | polling/websocket/socket delivery diagnostics plus transport-churn fault rows |
| scheduled/manual polling soak | current runtime/result/trace proof plus noisy fleet, result loss, and late-worker profiles |
| scheduled/manual infra-fault | current Redis runtime owner restart/reconnect recovery and trace overflow proof semantics; Redis partition/failover, lease-clock skew, and multi-node presence flap need the WF-12 decision before they become scheduled rows |
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

## 13. Documentation Maintenance

When a row changes current proof status, update the owning document in the same
change:

- `doc/TESTING_INDEX.md` for lane placement and minimum verification
- `doc/PROOF_REGISTRY.md` for promoted critical invariants and for wording that
  distinguishes current contract/E2E coverage from distributed-edge chaos proof
- `xa-mass-testing/README.md` for runner commands and report schema
- `integrations/xa-mass-worker-pack/README.md` for `fault.*` worker event behavior
- `doc/TRACE_CONTRACT.md` if new canonical event fields are required
