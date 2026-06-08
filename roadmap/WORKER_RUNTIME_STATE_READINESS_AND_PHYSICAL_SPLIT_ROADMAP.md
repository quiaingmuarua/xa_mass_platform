# Worker Runtime State Readiness And Physical Split Roadmap

Status: active successor roadmap; proposed for the next implementation slice.

Previous context:
[2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INDEXING_ROADMAP.md](../doc/archive/core/2026-06-08_WORKER_RUNTIME_STATE_DIMENSION_INDEXING_ROADMAP.md)
landed the current mainline-unblocking worker-state dimension slice:
group-local heartbeat, candidate-bucket vocabulary, logical readiness and
occupancy diagnostics, and the conservative Redis single-writer decision that
keeps `group:{groupId}:slots` canonical.

This roadmap owns the residue that should not be treated as complete:

- readiness values and worker state reports are not yet proven as scheduling
  outcomes beyond the existing dispatch gate,
- `DRAINING` is not yet represented end-to-end through the scheduling view as a
  distinct readiness state,
- `worker:meta:{workerId}`, `worker:occupancy:{workerId}`, and
  `worker:group:{groupId}:available:{shard}` remain target-only physical split
  ideas, not current runtime truth,
- occupancy is currently diagnostic and must not be confused with the admission
  predicate for multi-capacity workers.

## Current Code Observations

- `WorkerReadinessState` contains `READY`, `DRAINING`, `INIT_REQUIRED`,
  `VERSION_MISMATCH`, `ACCOUNT_UNAVAILABLE`, `HEALTH_UNAVAILABLE`, and
  `MAINTENANCE`, but the current scheduling view derives readiness from
  `dispatchEnabled` with `removing=false`.
- `DefaultWorkerDispatchAvailabilityPolicy` maps worker state report
  `DRAINING` to the `WORKER_STATE` dispatch-disable source and `AVAILABLE` to
  clearing only that source.
- Existing scheduling integration proof covers dispatch-disabled or
  maintenance-style exclusion, but does not prove a worker state report
  `DRAINING -> dispatch gate -> scheduling rejection -> AVAILABLE recovery`
  path.
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

## RSP-0: Residue Inventory And Status Repair

Goal: create a current inventory for the successor work and repair stale WRSI
status wording.

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
3. Repair the archived WRSI roadmap status so it says current slice complete /
   mainline unblocked, not full roadmap complete.

Acceptance:

1. The inventory distinguishes implemented facts from target-only split ideas.
2. Archived WRSI docs point to this roadmap for residual readiness and physical
   split work.
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

## RSP-1: Worker State Report To Dispatch Gate Contract

Goal: make the current state-report mapping explicit and bounded.

Scope:

1. Define the implemented state-report scheduling values:
   - `DRAINING` disables dispatch through `WORKER_STATE`,
   - `AVAILABLE` clears only the `WORKER_STATE` disable source.
2. Classify `INIT_REQUIRED`, `VERSION_MISMATCH`, `ACCOUNT_UNAVAILABLE`, and
   `HEALTH_UNAVAILABLE` as either implemented mappings or target-only
   readiness residue.
3. Keep worker command drain separate from worker state report drain:
   `WORKER_COMMAND` and `WORKER_STATE` disable sources must not clear each
   other accidentally.
4. Document whether state reports affect only dispatch gate truth or also
   diagnostic readiness labels.

Acceptance:

1. `DRAINING` and `AVAILABLE` semantics are documented in the worker-runtime or
   engine control owner docs.
2. A test proves `AVAILABLE` clears `WORKER_STATE` without clearing
   `WORKER_COMMAND`.
3. Every readiness enum value is either runtime-implemented with an outcome
   proof target or explicitly marked target-only residue.
4. No scheduling rule consumes raw worker state report strings.

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
mvn -pl xa-mass-engine -am "-Dtest=TaskWorkerEligibilityTest,WorkerControlServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

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
3. Keep active lease counters as evidence read by worker runtime. Do not
   change task lease lifecycle.

Acceptance:

1. No production scheduler uses `WorkerOccupancyState#available()` as the
   admission predicate.
2. Tests prove capacity behavior with runtime-visible outcomes.
3. Docs describe occupancy as a diagnostic classification over canonical
   reservation/capacity/lease/lock facts.
4. Any read model exposing occupancy avoids implying `OCCUPIED` means
   unschedulable when capacity remains.

Suggested proof:

```bash
mvn -pl xa-mass-engine,xa-mass-worker-runtime -am \
  "-Dtest=TaskSchedulingContentionTest,TaskSchedulingGateAndTargetingTest,WorkerAdmissionOwnerTest" \
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
3. `INIT_REQUIRED`, `VERSION_MISMATCH`, `ACCOUNT_UNAVAILABLE`, and
   `HEALTH_UNAVAILABLE` are implemented with outcome proof or explicitly
   classified as target-only residue.
4. No production code writes duplicate metadata or occupancy truth across
   `group:{groupId}:slots`, `worker:meta`, and `worker:occupancy`.
5. No `available:{shard}`, `nextAvailableAt`, or `occupiedUntil` writable fact
   exists without owner, cost proof, atomic update plan, and stale-hint outcome
   proof.
6. Occupancy diagnostics cannot be read as the admission predicate for
   multi-capacity workers.
7. `WorkerSchedulingPolicy` and `ResolvedWorkerSchedulingPolicy` remain free of
   live worker runtime evidence.
8. `TaskWorkRuntime` lease lifecycle remains owned by task runtime.
9. Public read models are either unchanged or updated with owner docs and
   startup/API proof.
10. Residue scans for composite worker status and target-only split keys are
    clean or fully classified.
11. The proof registry and owning READMEs reflect current implemented behavior.
12. The roadmap is archived only after these criteria are satisfied.

## Suggested First Slice

Start with RSP-0 and RSP-2 together only if the inventory confirms the current
state-report control path can be exercised from `TaskSchedulingTestHarness`
without fake bypasses.

Minimum first-slice verification:

```bash
mvn -pl xa-mass-engine -am "-Dtest=TaskWorkerEligibilityTest,WorkerControlServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
rg -n "worker:meta|worker:occupancy|available:\\{shard\\}|nextAvailableAt|occupiedUntil" \
  xa-mass-engine/src/main/java xa-mass-worker-runtime/src/main/java platform_infra/mass-runtime-redis/src/main/java --glob '!**/target/**'
git diff --check
```

If the harness cannot exercise state reports without writing the final gate
directly, stop after RSP-0 and refine the harness/owner path before adding
tests.
