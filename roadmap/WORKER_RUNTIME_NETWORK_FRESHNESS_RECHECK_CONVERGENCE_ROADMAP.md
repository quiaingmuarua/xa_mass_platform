# Worker Runtime Network Freshness Recheck Convergence Roadmap

Status: proposed direction document.

## Summary

This roadmap corrects the worker-runtime positive recovery model after the
score-band pivot.

New rule:

```text
network freshness + valid worker declaration/group + no platform block/park/hold
  -> worker may return to ELIGIBLE_BAND during worker-runtime recheck
```

Worker attributes, event capability, handler availability, and task-specific
filters do not decide global worker eligibility. They are selection filters for
a concrete task after a worker is globally eligible.

This supersedes the earlier `RecoveryMode` / `FRESHNESS_EVIDENCE` direction.
A worker does not declare whether freshness may reopen scheduling. If a worker
is network-fresh and not platform-blocked, it is generally eligible for the
worker group candidate pool.

## Current Code Observations

- `WorkerDispatchRecoveryMode` currently models `EXPLICIT_ONLY` and
  `FRESHNESS_EVIDENCE`.
- `WorkerScoreBandSlotMetadata` currently stores `dispatchRecoveryMode` and
  derives `freshnessEvidenceRecoveryAllowed()`.
- `WorkerManager.recoverWorkerDispatch(...)` currently gates freshness-based
  recovery on `FRESHNESS_EVIDENCE`.
- `WorkerManagerTest` has tests that prove `EXPLICIT_ONLY` workers do not
  recover from freshness.
- `WorkerScoreBand.lowRecheckScore(nextRecheckAtMillis)` currently encodes
  low-recheck as a relative due-time score.
- Transport session connect currently stays transport-local endpoint lease
  evidence and does not call worker-runtime positive recovery.
- `WorkerScoreBand.isAcquireVisible(...)` only returns time-band scores. A
  `LOW_RECHECK_BAND` score will not return to normal selection by itself, so a
  worker-runtime-owned recheck caller is required.

Those were useful convergence scaffolding, but they now preserve the wrong
owner model.

## Boundary Decision

Worker-runtime owns the global worker eligibility score.

Transport owns network freshness evidence and exposes it only as a narrow
point-read to worker-runtime recheck. Transport must not push heartbeat events
into worker-runtime, must not call positive recovery on connect, and must not
write score-band state.

Adapter-confirmed current-session connect does not emit a worker-runtime signal
in the current mainline. A future positive recheck caller must be explicitly
approved and must remain request-only. Worker-runtime must validate worker
declaration, group membership, blocks, holds, capacity, and freshness before it
writes `ELIGIBLE_BAND`.

A future protocol-authenticated first worker message may become a positive
recheck request, but that is not part of this roadmap. It needs a separate
owner review because it adds another protocol-edge positive signal.

Worker does not own schedulability. Worker-originated reports may carry
diagnostics or explicit drain/offline signals, but a worker must not opt into or
expand its own positive recovery authority through attributes.

Task-specific selection owns attribute, target, event capability, and policy
filters. A task filter miss must not write `LOW_RECHECK_BAND`, `PARKED_BAND`,
or any other global eligibility score.

LOW_RECHECK recheck is worker-runtime owned. It is not task hot-path acquire,
not transport-owned, and not transport-direct-write. Worker-runtime decides when
to inspect low-recheck workers through allowlisted request-only signals.
LOW_RECHECK recovery alone does not justify targeted maintenance, broad
demand-driven recheck, or a timer.

External positive signals are request-only:

```text
external signal
  -> request worker-runtime recheck(workerGroupId, workerId, reason)
  -> worker-runtime validates owner facts and transport freshness
  -> only worker-runtime may write ELIGIBLE_BAND
```

No external signal may call a clear-capable gate, direct recovery API, or
score-band writer to reopen dispatch eligibility.

## High-ROI Mechanism Gate

This roadmap must not add runtime machinery just to make LOW_RECHECK recovery
faster. Threads, pollers, scanners, periodic jobs, lifecycle owners,
event-triggered wakeups, queues, secondary indexes, and cross-module push
signals are high-cost mechanisms.

Any future high-cost mechanism must name:

- the high-value invariant it protects;
- the owner that pays for it;
- the failure mode if it is absent;
- why request-only recheck during an existing high-value event is
  insufficient.

For this roadmap, LOW_RECHECK recovery latency is not enough. The default shape
is request-only targeted recheck from an existing high-value event. This roadmap
does not approve transport session connect as that event.

## Target Shape

```text
worker-runtime recheck(workerGroupId, workerId, reason)
  -> read worker declaration / slot metadata
  -> verify worker is a member of workerGroupId
  -> reject if worker is removed, parked, platform-blocked, exclusive-held, or future-held
  -> point-read transport freshness for workerGroupId + workerId
  -> if fresh:
       score -> ELIGIBLE_BAND(now)
     else:
       score -> LOW_RECHECK_BAND(retry priority / attempt count)
```

`workerGroupId` is a worker-runtime partition and membership validation input.
Transport may use it as a freshness-store partition hint, but it must not decide
membership or task selection from it.

`LOW_RECHECK_BAND` is not a default time queue:

```text
ordinary network disconnect / no-current-endpoint
  -> LOW_RECHECK_BAND(priority/count score)

handler stall / repeated suspicious failure / explicit backoff policy
  -> LOW_RECHECK_BAND(delayed score or maintenance projection), only if proven
```

Who rechecks LOW_RECHECK:

```text
worker-runtime targeted recheck service
  inputs:
    - explicit worker state/control/operator event
    - worker registration/redeclaration
    - assignment/claim close or exclusive-lock release
  never inputs:
    - transport session connect unless a successor roadmap approves it
    - transport heartbeat update
    - non-current/stale transport connected callback
    - connection-auth success or first protocol message in this roadmap
    - task attribute/filter miss
    - standalone timer/scanner created only to recover LOW_RECHECK workers
```

The first implementation should be synchronous and opportunistic: when an
allowlisted high-value caller requests recheck for a specific worker,
worker-runtime point-reads transport freshness and writes the next score.
LOW_RECHECK recovery by itself is not high-value enough to justify a dedicated
scanner, timer, or broad maintenance job.

If a later policy records `recheckAt`, it is only a best-effort gate evaluated
inside an existing recheck round:

```text
existing worker-runtime recheck round
  -> if recheckAt exists and now < recheckAt: skip/no-op
  -> otherwise point-read freshness and validate owner facts
```

## Non-Goals

- Do not implement same-worker multi-item concurrency in this roadmap.
- Do not restore old worker reservation/counter APIs.
- Do not let task attributes, event capability, or handler availability write
  global score bands.
- Do not let transport heartbeat events into worker-runtime.
- Do not let adapter/session connect directly reopen worker dispatch
  eligibility or request worker-runtime recheck in this roadmap.
- Do not add a positive recovery signal from connection authentication or the
  first protocol message in this slice.
- Do not add a compatibility bridge for `dispatchRecoveryMode`.
- Do not introduce a thread, poller, scanner, lifecycle owner,
  event-triggered wakeup, queue, or secondary index only to improve
  LOW_RECHECK recovery latency.
- Do not make endpoint lease, adapter mailbox, route key, connection id, or
  session token visible to worker selection.

## NFR-0 Inventory And Contract Decision

Scope:

- Inventory current uses of:
  - `WorkerDispatchRecoveryMode`;
  - `dispatchRecoveryMode`;
  - `freshnessEvidenceRecoveryAllowed`;
  - `WorkerScoreBand.lowRecheckScore`;
  - `LOW_RECHECK_EPOCH_MILLIS`;
  - `recoverWorkerDispatch`.
- Decide the narrow freshness read contract. Candidate shape:

```java
boolean isWorkerNetworkFresh(String workerGroupId, String workerId, long nowMillis);
```

- Decide the narrow request-only recheck contract. Candidate shape:

```java
boolean recheckWorkerNetworkEligibility(String workerGroupId, String workerId, String reason);
```

Candidate owner name: `WorkerNetworkEligibilityRecheckRuntime`. Do not reuse
`WorkerDispatchRecoveryRuntime`; that existing contract is controlled recovery
and lacks the WorkerGroup input required for network freshness validation.

- Decide where the freshness read contract lives. It must be a narrow provider,
  not the diagnostic endpoint lease view. The first implementation must not make
  worker-runtime depend on adapter/session internals.
- Confirm transport session connect has no positive worker-runtime caller. Any
  future positive recheck request sink must be introduced by a successor owner
  review and must not call worker-runtime direct recovery, clear-capable gate
  APIs, or score-band writers.
- Inventory `EngineConfig.getWorkerDispatchRecoveryRuntime()`. Target: delete
  this SDK/assembly getter. Controlled recovery stays worker-runtime/control
  owned, and `DefaultWorkerDispatchAvailabilityPolicy` can continue receiving
  the internal owner directly.

Acceptance:

- Inventory separates production code, test proof, docs, and transitional
  score-band scaffolding.
- Freshness read contract returns only fresh/not-fresh; it has no list, stats,
  endpoint, mailbox, route, session, or connection fields.
- `workerGroupId` is documented as membership/partition input, not a transport
  routing identity.
- Recheck caller contract is worker-runtime owned and does not expose
  clear-capable gate APIs to transport or adapters.
- SDK/transport assembly no longer treats `WorkerDispatchRecoveryRuntime` as the
  connect-path capability.

## NFR-1 Low-Recheck Score Semantics

Scope:

- Replace default low-recheck due-time vocabulary with priority/count
  vocabulary.
- Rename or replace `WorkerScoreBand.lowRecheckScore(nextRecheckAtMillis)` so
  ordinary network recovery no longer encodes `recheckAt`.
- Keep delayed low-recheck backoff as an explicit later sub-policy, not the
  default helper.
- Update memory/Redis contract tests to prove:
  - low-recheck score stays below `TIME_SCORE_FLOOR`;
  - ordinary disconnect/no-current-endpoint uses priority/count semantics;
  - `FUTURE_BAND` remains the only default time-due band.

Acceptance:

- Main code and tests do not describe ordinary network low-recheck as
  `nextRecheckAtMillis`.
- Any delayed low-recheck helper is explicitly named as delayed/backoff and is
  not used for ordinary disconnect.
- Score-band docs and tests agree on the same score semantics.

## NFR-2/3 Request-Only Network Recheck Slice

Scope:

- Add or wire a narrow network freshness point-read provider.
- Add `WorkerNetworkEligibilityRecheckRuntime` or an equivalent request-only
  contract. It accepts `workerGroupId`, `workerId`, and `reason`; it does not
  expose clear-capable gates, direct recovery, or score-band write APIs.
- Scope includes:
  - `AdapterSessionEvidencePublisher.connected(...)`;
  - embedded adapter runtime environment/session evidence wiring;
  - related WebSocket/Socket/Polling session and adapter runtime tests.
- Delete `WorkerDispatchRecoveryMode`.
- Remove `dispatchRecoveryMode` and related constants/methods from
  `WorkerScoreBandSlotMetadata`.
- Remove test fixtures that set `dispatchRecoveryMode=FRESHNESS_EVIDENCE`.
- Remove freshness from `WorkerManager.recoverWorkerDispatch(...)`.
  `recoverWorkerDispatch(...)` remains controlled recovery only.
  Network freshness must use `recheckWorkerNetworkEligibility(...)` or the
  equivalent request-only recheck path.
- Split controlled recovery from network freshness recheck:
  - controlled recovery handles worker-runtime/control evidence such as worker
    state, worker command, and node/group binding;
  - network freshness recheck handles `LOW_RECHECK_BAND` recovery and validates
    worker/group/freshness facts.
- Recheck flow validates, in order:
  - worker declaration exists;
  - worker belongs to the requested WorkerGroup;
  - worker is not removed;
  - worker is not parked/platform-blocked;
  - worker is not future-held/exclusive-held;
  - transport freshness is fresh.
- If all checks pass, write `ELIGIBLE_BAND(now)`.
- If freshness is missing/stale, keep or write `LOW_RECHECK_BAND` with
  priority/count semantics.
- Do not add a transport session connect caller in this executable slice.
- Delete `EngineConfig.getWorkerDispatchRecoveryRuntime()` from SDK/transport
  assembly. Controlled recovery should remain worker-runtime/control owned.
- Do not add a standalone timer, scanner, or demand-triggered broad recheck job
  just to recover LOW_RECHECK workers.

Acceptance:

- No production or test code references `FRESHNESS_EVIDENCE`, `EXPLICIT_ONLY`,
  `WorkerDispatchRecoveryMode`, or `dispatchRecoveryMode`.
- Worker attributes remain task-selection/ranking metadata and cannot decide
  global eligibility recovery.
- Existing worker declaration and metadata serializers remain compatible only by
  dropping ignored recovery-mode input; no internal compatibility alias remains.
- A worker with fresh network evidence and no owner-owned closure can recover
  without a worker-declared recovery mode.
- A parked/platform-blocked worker does not recover from network freshness.
- A task attribute mismatch does not write a score band.
- Transport heartbeat/session freshness updates do not invoke worker-runtime.
- Transport session connect invokes no worker-runtime path in this roadmap.
- SDK/transport connect paths do not call `WorkerDispatchRecoveryRuntime`,
  `recoverWorkerDispatch(...)`, clear-capable gate APIs, or score-band writers.
- `WorkerManager.recoverWorkerDispatch(...)` does not read transport freshness
  and does not handle network freshness recovery. It remains controlled-only.
- LOW_RECHECK workers can be rechecked by a worker-runtime-owned caller; the
  roadmap must not rely on normal score-band acquire to see them.
- `recheckAt`, if present for a delayed/backoff policy, is checked only inside
  an existing worker-runtime recheck round and does not schedule one.
- Controlled recovery and network freshness recheck are separate method paths or
  separately named internal branches with tests proving transport cannot use the
  controlled recovery path.

Deferred caller expansion:

- Later worker state/control/operator or worker registration/redeclaration
  callers may reuse the same request-only path, but they are not the first proof.

## NFR-4 Guards, Docs, And Proof

Scope:

- Update:
  - `xa-mass-worker-runtime/README.md`;
  - `xa-mass-worker-runtime/CONTRACTS.md`;
  - `architecture/score-band-resource-slot-scheduling-blueprint.md`;
  - `doc/PROOF_REGISTRY.md`;
  - repo owner-rule references.
- Add guards against:
  - `WorkerDispatchRecoveryMode`;
  - `dispatchRecoveryMode`;
  - `FRESHNESS_EVIDENCE`;
  - default network low-recheck based on `nextRecheckAtMillis`;
  - adapter/session connect paths calling worker-runtime direct recovery,
    clear-capable gates, or score-band writers;
  - SDK/transport assembly exposing `WorkerDispatchRecoveryRuntime` as an
    adapter/session connect capability;
  - heartbeat paths calling worker-runtime recheck or recovery;
  - worker-runtime reusing endpoint lease diagnostic views as the freshness
    contract;
  - task attributes writing score bands.

Acceptance:

- Active docs no longer describe per-worker recovery mode as target behavior.
- Proof registry states that current global worker eligibility is based on
  score-band state plus network freshness, while task filters remain
  task-specific.
- Guards fail if the recovery-mode model or default low-recheck due-time model
  returns.
- Guards fail if an external signal directly reopens worker dispatch
  eligibility.
- Guards fail if SDK/transport connect plumbing can call
  `WorkerDispatchRecoveryRuntime`.
- Guards fail if heartbeat calls worker-runtime recheck or recovery.
- Guards fail if worker-runtime network freshness recheck imports endpoint
  lease diagnostic record types.

## Suggested Implementation Order

1. NFR-0 inventory and exact freshness contract.
2. NFR-1 low-recheck score API/test rewrite.
3. NFR-2/3 request-only network recheck slice:
   - add the recheck contract;
   - implement freshness validation;
   - remove recovery-mode gating;
   - keep `recoverWorkerDispatch(...)` controlled-only.
4. NFR-4 docs, guards, and proof registry.

Do not start by adding a new lifecycle enum. The goal is to delete the old
three-state/recovery-mode framing, not replace it with a larger status model.

## Verification Candidates

Compile:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis,xa-mass-worker-runtime,xa-mass-engine,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile "-DtrimStackTrace=true"
```

Focused tests:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am "-Dtest=WorkerScoreBandSlotRuntimeContractTest,InMemoryWorkerScoreBandSlotRuntimeTest,RedisWorkerScoreBandSlotRuntimeTest" test "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl xa-mass-worker-runtime -am "-Dtest=WorkerManagerTest,WorkerSelectionAtomicRuntimeTest,WorkerSelectionContractGuardTest" test "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am "-Dtest=AdapterSessionEvidencePublisherTest,TransportConvergenceArchitectureGuardTest,WebSocketSessionRegistryTest,WebSocketSessionEvidenceRefresherTest,SocketSessionManagerTest,PollingSessionEvidenceDriverTest" test "-DtrimStackTrace=true"
```

Residue:

```powershell
rg -n "WorkerDispatchRecoveryMode|dispatchRecoveryMode|FRESHNESS_EVIDENCE|EXPLICIT_ONLY" platform_infra xa-mass-worker-runtime xa-mass-engine sdk xa-mass-server --glob "*.java" --glob "!**/target/**"
rg -n "nextRecheckAtMillis|LOW_RECHECK_EPOCH_MILLIS|lowRecheckScore" platform_infra xa-mass-worker-runtime architecture --glob "*.java" --glob "*.md" --glob "!**/target/**"
rg -n "WorkerDispatchRecoveryRuntime|recoverWorkerDispatch\\(|clearWorkerDispatch|writeEligible|ELIGIBLE_BAND" transport sdk --glob "*.java" --glob "!**/target/**"
```

Expected residue after completion: no recovery-mode symbols; low-recheck
time/due symbols only remain if explicitly renamed as delayed/backoff policy and
not used for ordinary network disconnect. Transport/adapter/SDK main code must
not call direct recovery, clear-capable gates, or score-band writers.
`TransportConvergenceArchitectureGuardTest` or an equivalent guard must prove
heartbeat paths cannot call worker-runtime recheck or recovery.

## Completion Criteria

- Worker-global eligibility recheck no longer reads worker-declared recovery
  mode.
- Ordinary network disconnect/no-current-endpoint uses LOW_RECHECK priority or
  count semantics, not default `recheckAt`.
- Fresh network evidence can lead worker-runtime to reopen ordinary
  low-recheck workers after worker-runtime validates declaration, group
  membership, block/park/hold, and capacity facts.
- LOW_RECHECK recheck is triggered only through request-only recheck callers.
  Transport session connect, heartbeat/freshness update, connection-auth
  success, first protocol messages, task filter misses, and standalone
  low-recheck timers never trigger it in this roadmap.
- No external signal directly writes `ELIGIBLE_BAND`, clears a gate, or calls
  direct recovery.
- Parked/platform-blocked workers remain closed until their owner explicitly
  releases the closure.
- Task attributes, event capability, and handler availability remain
  task-specific filters and do not write global score bands.
