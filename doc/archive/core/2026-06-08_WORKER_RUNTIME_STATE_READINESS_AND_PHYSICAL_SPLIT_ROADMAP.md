# Worker Runtime State Readiness And Physical Split Roadmap

Status: complete; archived on 2026-06-08 after implementation, verification,
and residue scan.

Implementation progress:

- RSP-1 is implemented: worker state report vocabulary is classified in
  `xa-mass-worker-runtime/README.md` and `CONTRACTS.md`. The default
  dispatch-gate policy remains an allowlist: `DRAINING` disables
  `WORKER_STATE`, `AVAILABLE` clears only `WORKER_STATE`, and
  `DEGRADED`/`OFFLINE`/`READY` are diagnostic-only projection values.
- RSP-2 is implemented by `WorkerStateReportSchedulingIntegrationTest`, which
  starts from `WorkerControlService.applyWorkerStateReport(...)` and observes
  assignment, lease, and dispatch-binding outcomes.
- RSP-3 is closed by documenting `DRAINING` as upstream worker-control /
  dispatch-gate evidence in current scheduling; `WorkerSchedulingView` does not
  claim an independent DRAINING label.
- RSP-4 keeps `group:{groupId}:slots` canonical. `worker:meta` and
  `worker:occupancy` remain target-only split ideas until a future clean-runtime
  physical-split roadmap replaces the slot mutation boundary.
- RSP-5 is implemented: `WorkerOccupancyState#available()` was removed, and
  capacity proof shows `OCCUPIED` with remaining capacity can still assign while
  `CAPACITY_FULL` rejects.
- RSP-6 is deferred by decision: `available:{shard}`, `nextAvailableAt`, and
  `occupiedUntil` remain unimplemented until measured candidate-acquisition or
  reserve-rejection cost justifies them.
- RSP-7 is covered by current owner docs: legacy `statusName` / worker `status`
  fields are display-only compatibility and do not own runtime scheduling
  truth.

Previous context:
[2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INDEXING_ROADMAP.md](2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INDEXING_ROADMAP.md)
landed the current mainline-unblocking worker-state dimension slice:
group-local heartbeat, candidate-bucket vocabulary, logical readiness and
occupancy diagnostics, and the conservative Redis single-writer decision that
keeps `group:{groupId}:slots` canonical.

This roadmap closes the immediate successor residue:

- worker state report `DRAINING` is proven through worker-control dispatch-gate
  application to scheduling-visible assignment, lease, and binding outcomes,
- `DRAINING` is documented as upstream worker-control / dispatch-gate evidence;
  the current scheduling view does not claim an independent `DRAINING` label,
- `AVAILABLE`, `DRAINING`, `DEGRADED`, `OFFLINE`, and `READY` are classified
  for default worker state report handling,
- occupancy is documented and proven as diagnostic, not the admission predicate
  for multi-capacity workers.

Deferred target-only work remains out of scope for this closed roadmap:

- `worker:meta:{workerId}`, `worker:occupancy:{workerId}`, and
  `worker:group:{groupId}:available:{shard}` remain target-only physical split
  ideas, not current runtime truth,
- `INIT_REQUIRED`, `VERSION_MISMATCH`, `ACCOUNT_UNAVAILABLE`, and
  `HEALTH_UNAVAILABLE` remain target-only readiness vocabulary until a future
  roadmap gives them dispatch-gate semantics and runtime outcome proof.

## Current Code Observations

- `WorkerReadinessState` contains `READY`, `DRAINING`, `INIT_REQUIRED`,
  `VERSION_MISMATCH`, `ACCOUNT_UNAVAILABLE`, `HEALTH_UNAVAILABLE`, and
  `MAINTENANCE`, but the current scheduling view derives readiness from
  `dispatchEnabled` with `removing=false`.
- `DefaultWorkerDispatchAvailabilityPolicy` maps worker state report
  `DRAINING` to the `WORKER_STATE` dispatch-disable source and `AVAILABLE` to
  clearing only that source.
- `WorkerStateReport` currently accepts any non-blank state string. Current
  tests and integrations already emit or preserve `AVAILABLE`, `DRAINING`,
  `DEGRADED`, `OFFLINE`, and `READY`; only `DRAINING` and `AVAILABLE` have
  default dispatch-gate semantics.
- Scheduling integration proof now covers
  `WorkerStateReport(DRAINING) -> dispatch gate -> scheduling rejection` and
  `WorkerStateReport(AVAILABLE) -> recovery` through
  `WorkerStateReportSchedulingIntegrationTest`. Direct
  `disableWorkerDispatch(...)` tests remain support coverage only.
- `WorkerOccupancyState` is derived from active lease count, reservation count,
  declared capacity, and exclusive lease evidence. It is diagnostic only.
- Redis worker registry currently stores metadata, dispatch gate inputs,
  reservation counters, active lease counters, exclusive lease flag, and
  removing flag in the group-local `group:{groupId}:slots` hash.
- Redis heartbeat and candidate buckets are split indexes, but
  `worker:meta:{workerId}`, `worker:occupancy:{workerId}`, and
  `available:{shard}` are intentionally absent from production code.

## Owner Review

- Worker runtime owns worker lifecycle, scheduling evidence, dispatch gates,
  worker state report projection, candidate source, admission, and worker
  runtime diagnostics.
- Engine owns scheduling order, task admission, candidate enumeration,
  assignment binding, and runtime outcome proof.
- `mass-runtime-api` owns the low-level `WorkerRegistry` contract.
- `mass-runtime-redis` owns the Redis implementation and keyspace. It may
  change physical indexes only when the runtime owner contract and mutation
  atomicity remain single-writer.
- `TaskWorkRuntime` owns task work queue, lease, counters, and task-side runtime
  truth. Occupancy may read lease counts through worker runtime evidence, but
  must not take ownership of lease lifecycle or expiry semantics.

## Boundary Decision

The next work is not a new worker scheduling policy product. It is a runtime
state hardening track.

The boundary is:

```text
worker state report / command / registry evidence
  -> worker-runtime dispatch gate and scheduling evidence
  -> engine runtime worker selection
  -> assignment outcome proof
```

Redis physical split is a separate decision inside this same owner boundary.
It must either keep `group:{groupId}:slots` canonical or replace it in one
clean-runtime slice. It must not add `worker:meta` or `worker:occupancy` as
parallel writable truth beside slots.

## Do Not Start With

Do not start by adding `worker:meta`, `worker:occupancy`, or `available`
Redis keys. That creates dual writable truth before the runtime mutation owner
and atomicity model are settled.

Do not start by adding enum or field-copy tests. A readiness or occupancy
change counts as proof only when it changes a scheduling-visible outcome.

## Hard Rules

1. No production slice may write the same worker fact to both
   `group:{groupId}:slots` and `worker:meta` / `worker:occupancy`.
2. `available:{shard}` is a hint only. It cannot become the source of
   occupancy truth or assignment permission.
3. A stale candidate or stale available hint must be rejected by the reserve or
   admission truth path before binding.
4. `WorkerSchedulingPolicy` and `ResolvedWorkerSchedulingPolicy` remain static
   worker-universe inputs. They must not import reachability, readiness,
   occupancy, registry, admission, lease, or Redis runtime owners.
5. Worker state report values do not become scheduling truth until they are
   mapped through the worker-runtime dispatch/evidence owner and proven by
   runtime outcome.
6. Occupancy may read active lease counters as evidence, but this roadmap must
   not change task lease lifecycle, lease expiry, result convergence, or task
   terminal truth.
7. Source scans and architecture guards are residue sanity only. Primary proof
   must be integration or E2E behavior.
8. Do not add same-module bridge/facade/service layers unless they introduce a
   real owner boundary, protocol surface, lifecycle split, or external caller.
9. `UNKNOWN`, `STALE`, and `OFFLINE` reachability remain non-reachable for
   dispatch unless a separate transport/reachability roadmap changes that
   contract.
10. Any public server/frontend worker status projection remains a read model or
    display concern; it must not redefine runtime scheduling state.

## Non-Goals

- No public policy catalog, policy binding, or plugin framework.
- No server/frontend status model redesign before runtime semantics are proven.
- No rolling Redis migration. If physical split is implemented, the default
  cutover is clean runtime recreation unless a later decision explicitly
  accepts rolling migration cost.
- No Redis worker registry hot-path `SCAN` fallback.
- No task runtime queue, lease, result, or terminal-state ownership change.
- No attempt to prove target-only readiness values by object construction tests.

## RSP-0: Residue Drift Checkpoint

Goal: verify the successor residue baseline before implementation. This is a
checkpoint, not the first implementation slice.

Scope:

1. Inventory active code and docs for:
   - `WorkerReadinessState`,
   - `WorkerOccupancyState`,
   - `WorkerSchedulingView#readinessState`,
   - `DefaultWorkerDispatchAvailabilityPolicy`,
   - `WorkerStateReport` projection and handler tests,
   - `WorkerRuntimeStateRecord`,
   - `WorkerResourceRecord#statusName` and other operator read models,
   - `group:{groupId}:slots`,
   - `worker:meta`, `worker:occupancy`, `available`, `nextAvailableAt`, and
     `occupiedUntil`.
2. Classify every hit as current runtime truth, diagnostic projection,
   target-only residue, read-model display, support test, or stale doc.
3. Verify the archived WRSI roadmap status still says current slice complete /
   mainline unblocked with successor residue, not full roadmap complete.

Acceptance:

1. The inventory distinguishes implemented facts from target-only split ideas.
2. Archived WRSI docs still point to this roadmap for residual readiness and
   physical split work.
3. No active doc claims `worker:meta`, `worker:occupancy`, or
   `available:{shard}` is current production truth.
4. No proof suite treats enum construction, field-copy assertion, or source
   shape as primary readiness or occupancy proof.

Suggested checks:

```bash
rg -n "WorkerReadinessState|WorkerOccupancyState|readinessState|occupancyState|statusName|worker:meta|worker:occupancy|available:\\{shard\\}|nextAvailableAt|occupiedUntil" \
  xa-mass-engine xa-mass-worker-runtime platform_infra doc roadmap --glob '!**/target/**'
rg -n "^Status: complete" doc/archive/core/*WORKER_RUNTIME_STATE_DIMENSION* --glob '!**/target/**'
```

## RSP-1: Worker State Report Vocabulary And Dispatch Gate Contract

Goal: classify every worker state report value that can currently enter the
system, then make the dispatch-gate mapping explicit and bounded.

Scope:

1. Inventory current accepted, emitted, or preserved worker state report values
   from engine tests, worker-runtime tests, SDK/integration samples, and
   worker-pack command routes.
2. Classify at least:
   - `AVAILABLE`,
   - `DRAINING`,
   - `DEGRADED`,
   - `OFFLINE`,
   - `READY`.
3. For each state value, choose exactly one classification:
   - dispatch-gate input,
   - diagnostic-only projection,
   - rejected at the caller boundary,
   - future target-only value.
4. Define the implemented state-report scheduling values:
   - `DRAINING` disables dispatch through `WORKER_STATE`,
   - `AVAILABLE` clears only the `WORKER_STATE` disable source.
5. Classify `INIT_REQUIRED`, `VERSION_MISMATCH`, `ACCOUNT_UNAVAILABLE`, and
   `HEALTH_UNAVAILABLE` as either implemented mappings or target-only
   readiness residue.
6. Keep worker command drain separate from worker state report drain:
   `WORKER_COMMAND` and `WORKER_STATE` disable sources must not clear each
   other accidentally.
7. Document whether state reports affect only dispatch gate truth or also
   diagnostic readiness labels.
8. Decide whether `WorkerStateReport` remains an open diagnostic string or
   becomes a closed vocabulary. If it remains open, the dispatch-gate policy
   must be documented as an allowlist, not as a parser for all states.

Acceptance:

1. `AVAILABLE`, `DRAINING`, `DEGRADED`, `OFFLINE`, and `READY` are each
   classified as dispatch-gate input, diagnostic-only projection, rejected
   boundary input, or future target-only value.
2. `DRAINING` and `AVAILABLE` semantics are documented in the worker-runtime or
   engine control owner docs.
3. A test proves `AVAILABLE` clears `WORKER_STATE` without clearing
   `WORKER_COMMAND`.
4. `DEGRADED` and `OFFLINE` are not left as unclassified projection strings if
   any current integration can emit them.
5. Every readiness enum value is either runtime-implemented with an outcome
   proof target or explicitly marked target-only residue.
6. No scheduling rule consumes raw worker state report strings.

Suggested inventory:

```bash
rg -n "\"(AVAILABLE|DRAINING|DEGRADED|OFFLINE|READY)\"|WorkerStateReport|fault\\.worker\\.state\\.flap" \
  xa-mass-engine xa-mass-worker-runtime integrations sdk --glob '!**/target/**'
```

## RSP-2: DRAINING Scheduling Outcome Proof

Goal: prove worker state report `DRAINING` changes runtime scheduling outcome,
not only dispatch-gate fields.

Scope:

1. Add or retarget an integration test that applies a worker state report
   `DRAINING` through the production control/report path available in the
   engine test harness.
2. Then dispatch a task with a draining worker and a backup worker.
3. Assert runtime-visible outcomes:
   - the draining worker does not receive an active lease,
   - no assignment binding is emitted for the draining worker,
   - rejection reason identifies dispatch/readiness unavailability,
   - the backup worker receives the assignment,
   - after `AVAILABLE`, the previously draining worker can receive new work.
4. If current `TaskSchedulingTestHarness` cannot apply state reports through a
   realistic owner path, extend the harness narrowly for worker state report
   projection and dispatch-gate application. Do not add a direct test-only
   bypass that writes the final gate while claiming state-report proof.

Acceptance:

1. The test starts from a worker state report, not direct
   `disableWorkerDispatch(...)`, when proving `DRAINING`.
2. The proof observes assignment, lease, task status, or worker binding
   outcomes.
3. The same test or a companion test proves `AVAILABLE` recovery without
   clearing unrelated command-drain state.
4. Source scans remain support checks only.

Suggested proof:

```bash
mvn -pl xa-mass-engine -am -DskipTests install
mvn -pl xa-mass-engine "-Dtest=WorkerStateReportSchedulingIntegrationTest" test
mvn -pl xa-mass-engine -am \
  "-Dtest=TaskWorkerEligibilityTest,WorkerControlServiceTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

`WorkerStateReportSchedulingIntegrationTest` is a required new or renamed proof
surface for this slice. Existing `TaskWorkerEligibilityTest` coverage that
directly calls `disableWorkerDispatch(...)` remains support coverage only and
must not be cited as proof of the state-report chain.

## RSP-3: Readiness Diagnostic Alignment

Goal: make readiness labels match the evidence the scheduling path actually
has.

Scope:

1. Decide whether `WorkerSchedulingView` should represent `DRAINING` as a
   distinct readiness value.
2. If yes, carry the needed removing/state-source evidence into the scheduling
   view without exposing live evidence to worker policy resolution or matching
   rule context.
3. If no, downgrade active docs so `DRAINING` is a worker-state-report and
   dispatch-gate concept, while scheduling diagnostics expose only
   `READY/MAINTENANCE` from the current view.
4. Keep `WorkerMatchContext#getRuleContext()` free of reachability, readiness,
   and occupancy evidence unless a separate rule-boundary decision approves a
   narrower rule input.

Acceptance:

1. Active docs do not claim `ONLINE + DRAINING + FREE` is representable in the
   scheduling view unless code can actually represent it.
2. If represented, integration proof shows a DRAINING worker is excluded before
   binding.
3. If not represented, `DRAINING` remains documented as upstream dispatch gate
   input, not as a scheduling-view readiness label.
4. No policy resolver or worker scheduling policy code imports live worker
   readiness/evidence owners.

## RSP-4: Redis Physical Split Decision

Goal: decide whether metadata and occupancy should remain encoded in slots or
move to physical split keys.

Scope:

1. Choose exactly one of these target tracks:
   - keep `group:{groupId}:slots` canonical and leave split keys target-only,
   - replace slot-embedded metadata/occupancy with physical split keys in a
     clean-runtime recreation slice,
   - defer the split until measured contention or read cost justifies it.
2. If keeping slots canonical, update docs and guards so no later slice adds
   split keys opportunistically.
3. If splitting, define the complete mutation owner set before coding:
   register, heartbeat, dispatch gate disable/clear, reserve, confirm, release,
   active lease counter updates, exclusive lease, removing, stale cleanup, and
   candidate cleanup.
4. If splitting, choose the atomicity mechanism:
   single Lua mutation, `WATCH` set over all touched keys, or another explicit
   single-writer protocol.
5. If splitting, specify cutover as clean runtime recreation unless a rolling
   migration roadmap is created separately.

Acceptance:

1. There is no implementation slice that writes both slot-embedded and split
   metadata/occupancy truth for the same fact.
2. The chosen track states whether `slots` remains canonical, is demoted, or is
   removed.
3. Any split implementation proves Redis and memory `WorkerRegistry` contract
   parity for reserve, release, dispatch gate, stale cleanup, and candidate
   acquisition.
4. Any split implementation includes a Redis restart/recreation proof for the
   chosen keyspace.

Suggested proof if implementation starts:

```bash
mvn -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am \
  "-Dtest=WorkerRegistryContractTest,InMemoryWorkerRegistryTest,RedisWorkerRegistryTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## RSP-5: Occupancy And Capacity Semantics

Goal: prevent diagnostic occupancy labels from becoming a false admission
predicate.

Scope:

1. Review `WorkerOccupancyState#available()` and either:
   - remove it,
   - rename it to a diagnostic-only predicate, or
   - document that it does not define admission for multi-capacity workers.
2. Prove the meaningful runtime outcomes:
   - `CAPACITY_FULL` rejects or delays assignment,
   - an exclusive lock rejects assignment,
   - a multi-capacity worker may be `OCCUPIED` and still accept work when
     capacity remains.
3. For the multi-capacity case, the proof must observe this sequence:
   - first assignment makes the worker diagnostic occupancy `OCCUPIED`,
   - second assignment still reserves/binds to the same worker because capacity
     remains,
   - only the capacity-full attempt rejects.
4. Keep active lease counters as evidence read by worker runtime. Do not
   change task lease lifecycle.

Acceptance:

1. No production scheduler uses `WorkerOccupancyState#available()` as the
   admission predicate.
2. Tests prove capacity behavior with runtime-visible outcomes, including
   `OCCUPIED` with remaining capacity still assigning.
3. Docs describe occupancy as a diagnostic classification over canonical
   reservation/capacity/lease/lock facts.
4. Any read model exposing occupancy avoids implying `OCCUPIED` means
   unschedulable when capacity remains.
5. Registry-level support proof confirms `tryReserve(...)` accepts while
   capacity remains and rejects only when canonical capacity is exhausted.

Suggested proof:

```bash
mvn -pl xa-mass-engine,xa-mass-worker-runtime -am \
  "-Dtest=TaskSchedulingContentionTest,TaskSchedulingGateAndTargetingTest,WorkerAdmissionOwnerTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis -am \
  "-Dtest=WorkerRegistryContractTest,InMemoryWorkerRegistryTest,RedisWorkerRegistryTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## RSP-6: Available Hint Cost Gate

Goal: prevent `available:{shard}` from becoming speculative architecture.

Scope:

1. Do not implement `available:{shard}` unless both are true:
   - measured candidate acquisition or reserve rejection cost shows a real
     problem,
   - the lifecycle owner for `nextAvailableAt` / `occupiedUntil` is named and
     can update the hint atomically with occupancy truth.
2. If the gate is met, define stale-hint behavior:
   - stale available member cannot bind work,
   - reserve/admission truth wins,
   - cleanup is bounded and not scan-heavy.
3. If the gate is not met, explicitly keep available hint as deferred residue.

Acceptance:

1. Two concrete measured cost cases exist before implementation starts, or the
   feature is deferred.
2. `nextAvailableAt` and `occupiedUntil` have a lifecycle owner before they
   become writable facts.
3. Stale hint rejection is proven by runtime outcome if the hint is
   implemented.
4. No test proves available hint behavior by key membership alone.

## RSP-7: Public Read Model And Documentation Cleanup

Goal: make operator-facing worker status projections consistent with the
runtime dimensions without turning them into scheduling truth.

Scope:

1. Inventory `statusName`, `workerStatus`, `WorkerResourceRecord`, server
   worker read APIs, and frontend worker badges if present.
2. Classify each as display/read model, current runtime diagnostic, stale
   compatibility, or public contract.
3. If public output changes, update the owning server/frontend/API contract
   docs and include startup/API proof where required.
4. Remove docs that describe worker status as one axis
   `ONLINE/READY/BUSY/OFFLINE` driving scheduling.

Acceptance:

1. Operator read models may display a composite label only if documented as
   display-only.
2. Runtime scheduling docs use reachability, readiness/eligibility, and
   occupancy/availability as separate dimensions.
3. Server/frontend contract docs are updated only if the public API surface
   actually changes.
4. No active doc says the frontend or server owns worker runtime truth.

## RSP-8: Archive And Proof Registry Closure

Goal: close the successor only after proof and residue are complete.

Scope:

1. Update `doc/PROOF_REGISTRY.md` only with proof that has runtime outcome
   evidence.
2. Update owning module READMEs and Redis baseline with current facts.
3. Run residue scans for target-only keys and composite worker status.
4. Archive this roadmap only after every completion criterion below is true or
   explicitly moved to a newer successor roadmap.

Acceptance:

1. No active roadmap or owner doc claims target-only split keys are current.
2. Proof registry entries point to integration/E2E or runtime contract tests as
   primary proof.
3. Support checks are labeled as support, not proof.
4. Archive status does not use `complete` unless all stated completion criteria
   are satisfied.

## Verification Matrix

| Area | Primary proof | Support only | Not proof |
| --- | --- | --- | --- |
| State report readiness | DRAINING/AVAILABLE integration from report to assignment outcome | dispatch-gate unit test | enum field assertion |
| Dispatch gate source isolation | command vs state source behavior plus scheduling outcome | direct gate source unit test | string normalization only |
| Occupancy/capacity | assignment, lease, reservation, lock, or task counter outcome | load snapshot object assertions | `WorkerOccupancyState` value copy |
| Redis physical split | Redis `WorkerRegistry` contract parity plus restart/recreation proof | keyspace scan | key-name assertion |
| Available hint | stale hint rejected by reserve/admission outcome | cleanup set membership test | zset member exists |
| Public read model | API/startup proof if contract changes | DTO field unit test | badge text only |

## Roadmap Completion Criteria

1. Archived WRSI status is repaired to indicate current slice complete /
   mainline unblocked with successor residue, not full completion.
2. `DRAINING` is either proven through worker state report to scheduling
   outcome or downgraded in docs as upstream dispatch-gate input only.
3. `AVAILABLE`, `DRAINING`, `DEGRADED`, `OFFLINE`, and `READY` are classified
   for state-report handling and dispatch-gate effect.
4. `INIT_REQUIRED`, `VERSION_MISMATCH`, `ACCOUNT_UNAVAILABLE`, and
   `HEALTH_UNAVAILABLE` are implemented with outcome proof or explicitly
   classified as target-only residue.
5. No production code writes duplicate metadata or occupancy truth across
   `group:{groupId}:slots`, `worker:meta`, and `worker:occupancy`.
6. No `available:{shard}`, `nextAvailableAt`, or `occupiedUntil` writable fact
   exists without owner, cost proof, atomic update plan, and stale-hint outcome
   proof.
7. Occupancy diagnostics cannot be read as the admission predicate for
   multi-capacity workers.
8. `WorkerSchedulingPolicy` and `ResolvedWorkerSchedulingPolicy` remain free of
   live worker runtime evidence.
9. `TaskWorkRuntime` lease lifecycle remains owned by task runtime.
10. Public read models are either unchanged or updated with owner docs and
   startup/API proof.
11. Residue scans for composite worker status and target-only split keys are
    clean or fully classified.
12. The proof registry and owning READMEs reflect current implemented behavior.
13. The roadmap is archived only after these criteria are satisfied.

## Implemented First Slice

The implementation started with RSP-1 and RSP-2 after running RSP-0 as a drift
checkpoint.

The first implementation slice:

1. classify the current worker state report vocabulary,
2. add or rename a state-report-driven scheduling integration proof,
3. extend `TaskSchedulingTestHarness` narrowly if needed so the test can enter
   through `WorkerControlService.applyWorkerStateReport(...)`,
4. keep direct `disableWorkerDispatch(...)` tests as support coverage only.

Minimum first-slice verification:

```bash
mvn -pl xa-mass-engine -am -DskipTests install
mvn -pl xa-mass-engine "-Dtest=WorkerStateReportSchedulingIntegrationTest" test
mvn -pl xa-mass-engine -am \
  "-Dtest=TaskWorkerEligibilityTest,WorkerControlServiceTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "\"(AVAILABLE|DRAINING|DEGRADED|OFFLINE|READY)\"|WorkerStateReport|fault\\.worker\\.state\\.flap" \
  xa-mass-engine xa-mass-worker-runtime integrations sdk --glob '!**/target/**'
rg -n "worker:meta|worker:occupancy|available:\\{shard\\}|nextAvailableAt|occupiedUntil" \
  xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java platform_infra/mass-runtime-redis/src/main/java --glob '!**/target/**'
git diff --check
```

If the harness cannot exercise state reports without writing the final gate
directly, stop before RSP-2 proof claims and refine the harness/owner path
first.
