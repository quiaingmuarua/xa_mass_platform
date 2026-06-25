# Worker Runtime Post Score-Band Residue Retirement Roadmap

Status: completed and archived on 2026-06-25.

Implementation note: the codebase now uses score-band worker slot runtime as
the production worker acquisition path. The deleted candidate, reservation, and
candidate-bucket surfaces named in this roadmap should be read as historical
pre-implementation observations.

This roadmap follows
[WORKER_RUNTIME_SCORE_BAND_SLOT_REGISTRY_CONVERGENCE_ROADMAP.md](../../../roadmap/WORKER_RUNTIME_SCORE_BAND_SLOT_REGISTRY_CONVERGENCE_ROADMAP.md).
The score-band worker slot state machine is now the production worker selection
source. This roadmap retires the pre-score-band candidate, reservation, and
candidate-bucket surfaces that remained to keep the first score-band migration
slice safe and verifiable.

The goal is not another scheduling redesign. The goal is deletion: remove old
runtime APIs, tests, guards, and Redis/memory registry shapes that still make
the project look like it has two worker-selection mechanisms.

## Current Code Observations

- Production worker selection now flows through score-band:

  ```text
  WorkerSelectionRuntime
    -> WorkerSelectionOwner
    -> WorkerScoreBandSlotRuntime.acquire(...)
    -> worker-runtime validation / ranking / exclusive-lock check
    -> WorkerScoreBandSlotRuntime.transition(FUTURE_INTERVAL)
  ```

- `WorkerSelectionOwner` no longer calls
  `WorkerCandidateRuntime.findWorkerCandidates(...)`,
  `WorkerCandidateIndex`, `WorkerRegistry.acquireCandidates(...)`,
  `WorkerAdmissionRuntime.reserveWorkerCapacity(...)`,
  `confirmWorkerReservation(...)`, `recordWorkClaimed(...)`, or
  `recordWorkFinal(...)` on the selection hot path.
- `WorkerCandidateRuntime` still exists and is still exposed through
  `WorkerManager` and `EngineConfig`.
- `WorkerCandidateSourceOwner` and `WorkerCandidateIndex` still implement the
  old Stage-1 candidate source path and still call
  `WorkerRegistry.acquireCandidates(...)`.
- `WorkerAdmissionRuntime` still exposes old reservation / active-work
  accounting methods even though selection now only needs exclusive lock and
  worker load reads from this side.
- Legacy `WorkerManager` constructors still default to
  `NoopWorkerScoreBandSlotRuntime.INSTANCE`. Direct construction through those
  paths creates a manager whose score-band acquire returns no workers. SDK and
  server production assembly provide real score-band runtimes, but the direct
  worker-runtime assembly surface is dangerous residue.
- `WorkerResourceOwner` also has a public constructor that defaults to
  `NoopWorkerScoreBandSlotRuntime.INSTANCE`. That path can register workers and
  update `WorkerRegistry` while never writing the score-band slot that
  production selection now acquires from.
- `xa-mass-testing/src/main` smoke/perf runners still contain direct
  `WorkerManager` / `WorkerSelectionOwner` assembly. These are support
  programs, not ordinary unit-test fixtures; silent no-op score-band assembly
  there makes performance and smoke validation misleading.
- `WorkerRegistry` still exposes and implements old candidate and reservation
  methods:

  ```text
  acquireCandidates(...)
  tryReserve(...)
  confirmReservation(...)
  releaseReservation(...)
  recordWorkClaimed(...)
  recordWorkFinal(...)
  activeWorkerCountForTask(...)
  ```

- Memory and Redis `WorkerRegistry` implementations still maintain candidate
  bucket indexes and candidate-bucket lifecycle deadline indexes.
- `ResolvedWorkerSchedulingPolicy.candidateBucketKeys` and
  `DefaultSchedulingPlaneResolver` still produce candidate-bucket keys, but
  current score-band acquisition consumes WorkerGroup ids and optional target
  worker id, not candidate bucket keys.
- `EngineSchedulingCoreArchitectureGuardTest` still contains guard rows that
  protect parts of the old candidate source path. Those guards must be changed
  from "preserve old candidate path" to "forbid production selection from
  returning to old candidate path".

## Owner Review

Score-band worker selection belongs to worker-runtime:

```text
WorkerSelectionRuntime
  -> WorkerScoreBandSlotRuntime
  -> memory / Redis score-band slot implementation
```

`WorkerRegistry` remains a low-level worker registry and slot metadata SPI, but
it must stop owning production candidate acquisition and reservation truth once
score-band is the production selection source.

`WorkerAdmissionRuntime` should not remain a broad admission lifecycle owner.
After score-band migration, the remaining live value is narrow:

```text
exclusive worker lock
worker load snapshot read
maybe bounded diagnostics for lock state
```

If the broad admission API only survives to satisfy old tests or config getters,
it is residue.

Candidate buckets are pre-score-band source indexes. They may remain during the
transition only as implementation residue. They must not be treated as worker
scheduling policy truth unless a new owner decision proves they are still
needed after score-band acquire.

The current score-band implementation is one active score-band hold per worker
resource. It does not attempt remaining-permit concurrent acquisition for
`declaredCapacity > 1`. That is the current mainline decision, not a
compatibility gap. Old reservation/capacity surfaces may be retired as residue
once no current caller needs them.
For active-hold v1, `declaredCapacity` is metadata, ranking/load evidence, and
diagnostic/configuration evidence. It is not current concurrent acquisition
proof or an active score-band acquire input.

This does not mean a worker can never execute multiple items. Future
non-exclusive task policy may release the score-band hold after dispatch
acceptance, letting the same worker be reacquired while earlier item execution
continues. That is a claim-close policy extension, not a reason to preserve old
reservation counters.

## Boundary Decision

There is one production worker-selection source:

```text
WorkerScoreBandSlotRuntime.acquire(...)
```

There must not be a second live production candidate path through:

```text
WorkerCandidateRuntime
WorkerCandidateSourceOwner
WorkerCandidateIndex
WorkerRegistry.acquireCandidates(...)
candidateBucketKeys
```

Task active-worker budgeting is not worker-runtime admission truth. It already
belongs to task-runtime lease truth through `TaskAssignmentRuntimePort`, and
this roadmap must not reintroduce `activeSelectedWorkerCount` or
`getActiveWorkerCountForTask` as selection-budgeting surfaces.

Score-band assembly must be explicit. New direct `WorkerManager` construction
must not silently fall back to a no-op score-band runtime that makes selection
return no candidates.

The capacity decision is explicit for this roadmap:

```text
one active score-band hold per worker resource accepted for current selection
declaredCapacity = metadata / ranking / diagnostic evidence
declaredCapacity != current concurrent acquisition proof
```

Future per-permit or per-resource-slot capacity can be designed separately if a
new product requirement appears, but it is not a blocker for this residue
retirement roadmap.

## Target Shape

After this roadmap:

- `WorkerManager` exposes `WorkerSelectionRuntime`, worker declaration/query,
  worker scheduling evidence, dispatch gate/block/recovery, and narrow
  exclusive-lock/load support only.
- `WorkerCandidateRuntime`, `WorkerCandidateSourceOwner`, and
  `WorkerCandidateIndex` are gone from main/test code unless a concrete
  non-selection owner is proven before implementation.
- `WorkerAdmissionRuntime` is either deleted or narrowed/renamed to the
  remaining exclusive-lock/load role. It no longer exposes reservation,
  claim/final accounting, or task active-worker count methods.
- `WorkerRegistry` no longer exposes old candidate acquisition methods once all
  callers are moved:

  ```text
  acquireCandidates
  ```

- `WorkerRegistry` no longer exposes old reservation / active-count methods:

  ```text
  tryReserve
  confirmReservation
  releaseReservation
  recordWorkClaimed
  recordWorkFinal
  activeWorkerCountForTask
  ```

- Memory and Redis worker-registry keyspace no longer maintains candidate
  buckets or candidate-bucket lifecycle deadline indexes unless a separate
  owner decision proves they are still needed for a non-selection feature.
- Engine scheduling policy no longer carries `candidateBucketKeys` as a
  runtime worker-selection input. Static worker-universe constraints remain as
  WorkerGroup ids, target worker id, routing code, route attributes, and target
  worker attributes.
- Architecture guards fail if production selection calls the removed candidate
  or reservation APIs.

## Non-Goals

- Do not redesign score-band bands, scores, transition rules, or Redis score
  key shape here.
- Do not change task assignment, dispatch binding, transport delivery, result
  convergence, or worker adapter behavior.
- Do not remove `WorkerRegistry` entirely. This roadmap shrinks old candidate
  and reservation surfaces; it does not delete worker metadata, dispatch gate,
  exclusive lease, or slot lifecycle support unless a slice proves a
  replacement owner.
- Do not remove `AdapterNodeRecord` or `NodeGroupBindingRecord` in this
  roadmap.
- Do not create compatibility aliases for deleted candidate/admission APIs.
- Do not preserve old tests by turning them into wrappers over the new score
  path. Tests should prove the current owner, not keep old vocabulary alive.
- Do not turn this cleanup roadmap into a per-permit capacity redesign.
  One active score-band hold per worker resource is the current score-band
  mainline.

## Do Not Start With

Do not start by deleting `WorkerRegistry` methods from `mass-runtime-api`.

That will break memory/Redis implementations and contract tests before callers
and guards have been moved. Start at the worker-runtime public/internal surface,
then shrink admission, then shrink the low-level registry SPI and Redis/memory
keyspace.

Also do not preserve old capacity/reservation methods on the assumption that
`declaredCapacity > 1` must still mean concurrent score-band acquisition.
One active score-band hold per worker resource is the current mainline; old
reservation methods should be removed once callers are moved. Future
non-exclusive reuse should be modeled through claim-close timing, not through
the old reservation SPI.

## PRR-0 Inventory And Guard Reclassification

Goal: classify remaining old symbols as production use, test fixture, guard
residue, or deletion target.

Scope:

- `WorkerCandidateRuntime`
- `WorkerCandidateSourceOwner`
- `WorkerCandidateIndex`
- `WorkerAdmissionRuntime`
- `WorkerAdmissionOwner`
- `WorkerAdmissionTarget`
- `WorkerRegistry.acquireCandidates`
- `WorkerRegistry.tryReserve / confirmReservation / releaseReservation`
- `WorkerRegistry.recordWorkClaimed / recordWorkFinal`
- `WorkerRegistry.activeWorkerCountForTask`
- `WorkerCandidateBucketPolicy`
- `ResolvedWorkerSchedulingPolicy.candidateBucketKeys`
- `DefaultSchedulingPlaneResolver` candidate-bucket output
- legacy `WorkerManager` constructors that default score-band runtime to no-op
- `WorkerResourceOwner` constructors that default score-band runtime to no-op
- `xa-mass-testing/src/main` smoke/perf direct worker-runtime assembly
- `EngineSchedulingCoreArchitectureGuardTest` old candidate/admission guard rows

Acceptance:

- The roadmap or sibling inventory lists each symbol, current caller, current
  classification, and target action.
- The inventory explicitly classifies legacy `WorkerManager` constructors that
  fall back to `NoopWorkerScoreBandSlotRuntime`.
- The inventory explicitly classifies `WorkerResourceOwner` constructors that
  fall back to `NoopWorkerScoreBandSlotRuntime`.
- The inventory includes `xa-mass-testing/src/main` smoke/perf runners and
  direct support assembly paths, not only unit tests.
- The inventory records the active-hold v1 decision: `declaredCapacity` is not
  current concurrent acquisition proof or an active acquire input, and future
  non-exclusive reuse belongs to claim-close policy rather than old reservation
  accounting.
- Guard rows that currently protect old candidate source behavior are
  identified for rewrite before the slice that deletes the protected symbol.
- Production `WorkerSelectionOwner` remains proven to acquire from
  `WorkerScoreBandSlotRuntime`, not from old candidate APIs.

## PRR-0A Close Dangerous Score-Band Assembly Residue

Goal: remove or fail-fast the direct worker-runtime construction paths that
silently install a no-op score-band runtime.

Scope:

- Inventory `WorkerManager` constructors, `WorkerResourceOwner` constructors,
  and support/runtime construction sites.
- Include `xa-mass-testing/src/main` smoke/perf runners in the inventory and
  migration scope.
- Delete legacy constructors that cannot provide `WorkerScoreBandSlotRuntime`,
  or route them to an explicit runtime supplied by test/support assembly.
- If a no-op score-band runtime remains for tests, it must be named and scoped
  as a test/support object, not the default production fallback.
- Update worker-runtime tests and SDK/server assembly tests to prove all
  production-like construction paths provide a real score-band runtime.
- Update smoke/perf runners to pass a real in-memory score-band runtime, or
  explicitly mark a runner as incompatible with score-band selection until it
  is migrated.
- Add a guard that prevents new production/support `WorkerManager` or
  `WorkerResourceOwner` construction without a `WorkerScoreBandSlotRuntime`.

Acceptance:

- Direct `WorkerManager` production construction cannot silently select zero
  workers because score-band runtime was omitted.
- Direct `WorkerResourceOwner` construction cannot register workers without
  writing score-band slots because score-band runtime was omitted.
- No public `WorkerManager` or `WorkerResourceOwner` constructor defaults to
  `NoopWorkerScoreBandSlotRuntime.INSTANCE`.
- `xa-mass-testing/src/main` smoke/perf runners use explicit score-band runtime
  assembly.
- Test-only use of no-op score-band runtime is explicitly scoped, or removed.
- SDK/server assembly still wires memory/Redis score-band runtimes.

## PRR-1 Remove Worker Candidate Runtime Surface

Goal: delete the old candidate-source API exposed above worker-runtime now that
production selection uses score-band.

PRR-3 must either land in the same implementation window or immediately after
this slice. The repo must not remain in a long-lived state where worker-runtime
candidate APIs are gone but engine still produces `candidateBucketKeys` as a
worker-selection hint that no owner consumes.

Scope:

- Delete `WorkerCandidateRuntime`.
- Remove `WorkerManager implements WorkerCandidateRuntime`.
- Remove `WorkerManager.findWorkerCandidates(...)`.
- Remove `WorkerManager.getWorkerCandidateIndex()` unless a same-slice caller
  proves a non-selection owner.
- Delete `WorkerCandidateSourceOwner`.
- Delete `WorkerCandidateIndex` and `WorkerCandidateIndexTest` unless PRR-0
  finds a required non-selection owner.
- Remove `EngineConfig.getWorkerCandidateRuntime()`.
- Update tests that use `findWorkerCandidates(...)` as a helper to use
  score-band slot setup plus `WorkerSelectionRuntime.selectAndReserve(...)`, or
  move the assertion to a lower-level score-band proof.
- Rewrite architecture guards so production code is forbidden from using
  `WorkerCandidateRuntime`, `WorkerCandidateSourceOwner`, or
  `WorkerCandidateIndex`.

Acceptance:

- No main or test source references `WorkerCandidateRuntime`.
- No main source references `WorkerCandidateSourceOwner`.
- `WorkerManager` no longer exposes `findWorkerCandidates(...)`.
- `EngineConfig` no longer exposes `getWorkerCandidateRuntime()`.
- Existing worker selection tests still prove group/event/project/target worker
  and routing constraints through `WorkerSelectionRuntime`.
- Guard language no longer says `WorkerCandidateIndex` is the required
  Stage-1 source owner.

## PRR-2 Narrow Worker Admission Runtime

Goal: remove old reservation and task-count semantics from the worker-runtime
admission surface.

Scope:

- Remove these methods from `WorkerAdmissionRuntime` if no production caller
  remains:

  ```text
  reserveWorkerCapacity(...)
  confirmWorkerReservation(...)
  releaseWorkerReservation(...)
  recordWorkClaimed(...)
  recordWorkFinal(...)
  getActiveWorkerCountForTask(...)
  ```

- Remove corresponding `WorkerManager` and `WorkerAdmissionOwner` methods.
- Keep or rename the remaining narrow role:

  ```text
  tryAcquireWorkerExclusiveLease(...)
  releaseWorkerExclusiveLease(...)
  hasWorkerExclusiveLease(...)
  getExclusiveLeaseWorkerIds()
  getWorkerLoad(...)
  ```

- Update `DefaultRuntimeDiagnosticsOperations` if it still reads the lock state
  through the broad admission getter.
- Update selection tests that mock `WorkerAdmissionRuntime` so they depend on
  the narrowed lock/load port instead of the old admission lifecycle API.

Acceptance:

- No production caller can reserve, confirm, claim, final, or release worker
  capacity through `WorkerAdmissionRuntime`.
- Task active-worker count is not available through worker-runtime admission.
- Worker selection still supports exclusive worker locks and load-aware ranking.
- Architecture guards prevent engine and SDK assembly from using old worker
  admission reservation methods.

## PRR-3 Remove Candidate Bucket From Engine Runtime Selection Contract

Goal: stop carrying candidate-bucket keys as worker-selection policy output
when score-band acquire does not consume them.

Ordering rule:

- Execute PRR-3 in the same implementation window as PRR-1 or immediately after
  PRR-1. Do not leave `ResolvedWorkerSchedulingPolicy.candidateBucketKeys` as a
  long-lived unused scheduling hint.

Scope:

- Remove `candidateBucketKeys` from `ResolvedWorkerSchedulingPolicy` if no
  remaining production caller consumes it.
- Remove candidate-bucket derivation from `DefaultSchedulingPlaneResolver`.
- Remove candidate-bucket assertions from
  `DefaultSchedulingPlaneResolverTest`, replacing them with proof that routing
  code / route attributes are preserved in `WorkerSelectionIntent`.
- Remove `WorkerTaskSelector.candidateBucketKeys` if PRR-1 deletes the old
  candidate runtime path.

Acceptance:

- Engine resolved worker scheduling policy does not expose
  `candidateBucketKeys`.
- Worker selection behavior still honors WorkerGroup ids, target worker id,
  routing code, route attributes, and target worker attributes.
- No engine production source imports `WorkerCandidateBucketPolicy`.

## PRR-4A Shrink WorkerRegistry Candidate Acquire SPI

Goal: remove old candidate acquisition methods from the low-level registry
contract after worker-runtime candidate callers and tests have moved.

Scope:

- Remove from `WorkerRegistry`:

  ```text
  acquireCandidates(...)
  ```

- Remove memory/Redis implementations for candidate acquisition.
- Remove or rewrite `WorkerRegistryContractTest`,
  `InMemoryWorkerRegistryTest`, and `RedisWorkerRegistryTest` cases that prove
  old candidate acquisition semantics.
- Keep registry methods that still own worker metadata, slot lifecycle,
  dispatch disable/block/recovery, cleanup/removing state, exclusive lease, and
  reservation/active-count surfaces until their own slice removes them.

Acceptance:

- `WorkerRegistry` no longer exposes candidate acquisition methods.
- Memory and Redis implementations compile without old candidate acquisition.
- Registry tests no longer prove old candidate acquisition behavior.
- Score-band runtime tests prove selection source and claim/release semantics.

## PRR-4B Shrink WorkerRegistry Reservation And Active-Count SPI

Goal: remove old reservation and active-count methods from the low-level
registry contract after worker-runtime admission callers and tests have moved.

Scope:

- Remove from `WorkerRegistry`:

  ```text
  tryReserve(...)
  confirmReservation(...)
  releaseReservation(...)
  recordWorkClaimed(...)
  recordWorkFinal(...)
  activeWorkerCountForTask(...)
  ```

- Remove memory/Redis implementations for reservation and active-count methods.
- Remove or rewrite `WorkerRegistryContractTest`,
  `InMemoryWorkerRegistryTest`, and `RedisWorkerRegistryTest` cases that prove
  old reservation / active-count semantics.
- Keep registry methods that still own worker metadata, slot lifecycle,
  dispatch disable/block/recovery, cleanup/removing state, and exclusive lease
  if they remain current owner truth.

Acceptance:

- `WorkerRegistry` no longer exposes reservation accounting or task
  active-count methods.
- Memory and Redis implementations compile without old reservation / active-count
  methods.
- Registry tests prove only the remaining registry responsibilities.
- Score-band runtime tests prove active-hold claim/release semantics.

## PRR-5 Remove Candidate-Bucket Keyspace And Policy Residue

Goal: delete pre-score-band candidate-bucket storage and policy after PRR-4A
removes candidate acquire SPI and no API or test needs it.

Scope:

- Remove `WorkerCandidateBucketPolicy` and
  `DefaultWorkerCandidateBucketPolicy` if no non-selection owner remains.
- Remove `WorkerCandidateBucketPolicies` from worker-runtime.
- Remove memory `candidateBuckets` maps and related key objects.
- Remove Redis candidate-bucket keys and lifecycle deadline zsets:

  ```text
  groupCandidateBucket(...)
  groupCandidateBucketsSet(...)
  candidateBucketLifecycleDeadlinesZset(...)
  ```

- Update Redis keyspace docs and tests to reflect score-band as the worker
  selection source.

Acceptance:

- No main source references `candidateBucketKey`, `candidateBucketKeys`,
  `WorkerCandidateBucketPolicy`, or `DefaultWorkerCandidateBucketPolicy`.
- Redis worker-registry keyspace no longer writes candidate-bucket indexes.
- Score-band worker runtime Redis keyspace remains the only production
  selection index.

## PRR-6 Documentation, Proof Registry, And Archive Readiness

Goal: make owner docs match the post-score-band implementation and prevent
future agents from preserving the old path.

Scope:

- Update `xa-mass-worker-runtime/README.md`.
- Update `xa-mass-worker-runtime/CONTRACTS.md`.
- Update `platform_infra/README.md`.
- Update `doc/PROOF_REGISTRY.md`.
- Update or delete stale architecture guard rows in
  `EngineSchedulingCoreArchitectureGuardTest`.
- Update the score-band roadmap status if this roadmap closes all listed
  follow-up residue.

Acceptance:

- Docs say score-band is the worker selection source and do not describe
  candidate buckets or worker admission reserve as current production selection
  truth.
- Proof registry points to score-band runtime/selection tests for worker
  selection source proof.
- Guards fail if production selection reintroduces old candidate or reservation
  APIs.
- This roadmap can be archived only after a residue scan has no old-symbol
  hits outside archive/history or explicitly documented non-production notes.

## Suggested Implementation Order

1. PRR-0: classify current symbols and update blocking guard expectations.
2. PRR-0A: close no-op score-band assembly residue.
3. PRR-1: delete candidate runtime surface above worker-runtime.
4. PRR-2: narrow admission runtime to exclusive lock / load support.
5. PRR-3: remove candidate-bucket keys from engine resolved policy.
6. PRR-4A: shrink `WorkerRegistry` candidate acquire SPI and memory/Redis
   candidate acquire implementations.
7. PRR-5: remove candidate-bucket keyspace and policy residue.
8. PRR-2 / PRR-4B: narrow admission runtime and remove registry reservation /
   active-count SPI.
9. PRR-6: update docs/proof registry and archive when residue is gone.

## Verification Candidates

Compile:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-memory,platform_infra/mass-runtime-redis,xa-mass-worker-runtime,xa-mass-engine,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile "-DtrimStackTrace=true"
```

Focused score-band and selection proof:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-memory "-Dtest=InMemoryWorkerScoreBandSlotRuntimeTest" test "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis "-Dtest=RedisWorkerScoreBandSlotRuntimeTest" test "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl xa-mass-worker-runtime "-Dtest=WorkerSelectionAtomicRuntimeTest,WorkerSelectionRankingMechanicsTest,WorkerSelectionContractGuardTest,WorkerManagerTest" test "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl xa-mass-engine "-Dtest=TaskResourceReleaseListenerTest,TaskWorkerAssignListenerTest,TaskResultCorrelationSupportTest,TaskWorkAttemptIdSupportTest,EngineSchedulingCoreArchitectureGuardTest,SimpleTaskDispatchBinderTest" test "-DtrimStackTrace=true"
```

Registry proof after PRR-4A / PRR-4B:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-runtime-api "-Dtest=WorkerRegistryContractTest" test "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl platform_infra/mass-runtime-memory "-Dtest=InMemoryWorkerRegistryTest" test "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis "-Dtest=RedisWorkerRegistryTest" test "-DtrimStackTrace=true"
```

Use the compile command first with `-am`, then run strict focused tests on the
owning module without `-am`. Do not use `-Dsurefire.failIfNoSpecifiedTests=false`
as roadmap completion proof.

Residue checks:

```powershell
rg -n "WorkerCandidateRuntime|WorkerCandidateSourceOwner|WorkerCandidateIndex|findWorkerCandidates|getWorkerCandidateIndex" xa-mass-worker-runtime xa-mass-engine sdk xa-mass-server platform_infra --glob "*.java" --glob "!**/target/**"
rg -n "NoopWorkerScoreBandSlotRuntime|new WorkerManager\\(|new WorkerResourceOwner\\(" xa-mass-worker-runtime sdk xa-mass-server xa-mass-testing/src/main --glob "*.java" --glob "!**/target/**"
rg -n "reserveWorkerCapacity|confirmWorkerReservation|releaseWorkerReservation|recordWorkClaimed|recordWorkFinal|getActiveWorkerCountForTask|tryReserve\\(|confirmReservation\\(|releaseReservation\\(" xa-mass-worker-runtime xa-mass-engine sdk xa-mass-server platform_infra --glob "*.java" --glob "!**/target/**"
rg -n "candidateBucketKey|candidateBucketKeys|WorkerCandidateBucketPolicy|DefaultWorkerCandidateBucketPolicy|WorkerCandidateBucketPolicies" xa-mass-worker-runtime xa-mass-engine sdk xa-mass-server platform_infra --glob "*.java" --glob "!**/target/**"
git diff --check
```

Expected final residue:

- no Java production/test references to deleted candidate/admission symbols;
- no production/support construction path defaults to
  `NoopWorkerScoreBandSlotRuntime`;
- no Redis/memory candidate-bucket keyspace writes;
- only archived roadmap/history files may mention old symbols.

## Roadmap Completion Criteria

This roadmap is complete only when all are true:

- Production worker selection has exactly one source: score-band acquire behind
  `WorkerSelectionRuntime`.
- Old worker candidate runtime classes and config getters are deleted.
- Old worker admission reservation/counter methods are deleted or replaced by
  a clearly named narrow lock/load port.
- Direct `WorkerManager` construction cannot silently default to
  `NoopWorkerScoreBandSlotRuntime`.
- Direct `WorkerResourceOwner` construction cannot silently default to
  `NoopWorkerScoreBandSlotRuntime`.
- `WorkerRegistry` no longer exposes old candidate acquisition APIs.
- `WorkerRegistry` no longer exposes old reservation accounting APIs.
- Candidate-bucket policy and keyspace are removed from production/test mainline
  unless a separate owner decision keeps them for a non-selection feature.
- Tests and architecture guards protect the post-score-band owner model instead
  of preserving the pre-score-band implementation.
- Owning docs and proof registry match current code.
