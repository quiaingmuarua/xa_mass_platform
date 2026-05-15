# WorkerContext Retirement Plan

Last updated: 2026-05-15

Status: active phased plan. WC-0/WC-1 have begun; later phases are not
implemented baseline behavior.

## Position

`WorkerContext` should be retired from the engine scheduling kernel.

The core reason is ownership clarity:

- worker/device/account management belongs to a worker-management or
  system-event layer
- engine scheduling should consume readable worker scheduling attributes and
  send dispatch events
- account switching and account failure belong to worker execution/result
  handling, not to an engine-managed account-slot lifecycle

The project is not yet production launched, so this retirement should prefer
direct convergence and deletion over a compatibility track. Use small,
verifiable slices, but do not keep old and new live paths in parallel.

## Annotation Stance

Do not use `@Deprecated` as the main migration mechanism.

`@Deprecated(forRemoval = true)` is acceptable only as a short-lived marker when
a type or method cannot be deleted in the same slice because the current phase
needs a compileable intermediate state. It must not become a compatibility
contract, adapter path, fallback path, or long-term "compact" layer.

Preferred rule:

- in the same phase, update in-repo callers and delete the old path
- if deletion crosses too many modules, split the phase by owner boundary
- each split must remove one real use, not just rename or wrap it
- source guards should prevent new usage immediately after a path is retired

## Current Dependency Surface

`WorkerContext` is still live in multiple meanings:

- matching attributes:
  - routing tags
  - context attributes
  - context project
- runtime resource lifecycle:
  - `IDLE -> RESERVED -> OCCUPIED -> IDLE`
  - bind/release around dispatch attempts
- public/server/storage API:
  - context registration DTOs
  - context CRUD endpoints
  - context storage contract
- trace and diagnostics:
  - `workerContextId`
  - `uniqueWorkerContextCount`
  - `WORKER_CONTEXT_STATUS_TRANSITION`
  - context snapshots in assignment diagnostics
- tests and fixtures:
  - context-backed routing tests
  - mock worker-context JSON fixtures
  - startup/recovery tests that assert context release

Current convergence work already reduced the blast radius:

- matching handoff uses `WorkerSchedulingCandidate`
- matching rules prefer `workerScheduling*`
- dispatch resource usage is owned by `WorkerDispatchResourcePolicy`
- transitional context lifecycle is isolated in
  `LegacyWorkerContextResourceLifecycle`
- repeated reservation and worker-lock cleanup is owned by
  `WorkerDispatchResourceReleaser`
- architecture guards prevent context-first handoff types and scattered context
  state mutation from returning

## Retirement Target

The target engine shape is:

```text
Worker registration / system events
    -> Worker readable scheduling attributes
    -> WorkerSchedulingView
    -> matching rules + ranker
    -> WorkerSchedulingCandidate(worker + scheduling view)
    -> assignment allocation
    -> runtime claim/bind
    -> transport dispatch event
    -> normal result convergence
```

No engine-owned account/context slot lifecycle remains in the hot path.

`WorkerSchedulingCandidate` should eventually carry:

- `Worker`
- `WorkerSchedulingView`

It should not carry `WorkerContext`.

`WorkerSchedulingView` should eventually be built from:

- `Worker`
- reachability snapshot
- load snapshot
- dispatch-enabled flag
- worker attributes / scheduling attributes

It should not be built from `WorkerContext`.

## Replacement Semantics

### Routing Tags

Current `WorkerContext.routingTags` should move into worker scheduling
attributes.

Recommended representation:

- keep routing as attributes first, for example:
  - `region=us`
  - `country=us`
  - `account=foo`
  - `capability.profile=bar`
- if set semantics are still needed, use a conventional worker attribute value
  such as `routingTags=us,paid,account-a`, parsed only inside matching helpers
  or test fixtures

Do not add another engine-owned context-slot model just to preserve routing tag
shape.

### Context Attributes

Current `WorkerContext.attributes` should be moved to `Worker.attributes` or to
a system-event-updated worker scheduling attribute view.

During retirement, rule variables should converge to:

- `workerAttributes`
- `workerSchedulingAttributes`
- `workerSchedulingRoutingTags`
- `workerSchedulingProject`
- `workerSchedulingMatchesRoutingCode`

Remove `workerContext*` rule variables only after all in-repo rules and fixtures
are migrated.

### Context Project

Current `WorkerContext.project` should not remain a separate schedulable
resource owner.

Project support should come from:

- `Worker.supportedProjects`
- `Worker.supportedEventCodes`
- worker scheduling attributes when a narrower routing capability is needed

### Exclusive Resource Slot

The old context lifecycle duplicated resource ownership in engine scheduling.

Replacement:

- `ExecutionSpec.foreground=true` uses worker-level exclusive lock semantics
  through `WorkerDispatchResourcePolicy`
- `ExecutionSpec.foreground=false` uses worker capacity reservation through
  `WorkerLoadView`
- future account/device exclusivity must enter as worker-management state or
  scheduling attributes, not as engine-managed `WorkerContextStatus`

### Account Switching

Account switching is an execution-side concern.

If needed later, dispatch may carry an execution hint such as:

```text
dispatchInstruction.targetAccount
```

Account switch failure should return through normal result convergence with a
distinct failure reason, for example:

```text
ACCOUNT_SWITCH_FAILED
```

Do not recreate account slot leasing inside engine assignment.

## Phased Plan

### Phase WC-0: Freeze And Inventory

Status: started.

Goal: prevent new dependencies before deletion starts.

Scope:

- add or extend source guards so new production code cannot call WorkerContext
  storage APIs except allowed transitional owners
- guard against new `workerContext*` default rules or test fixtures
- record all remaining references by owner:
  - engine hot path
  - engine diagnostics
  - storage contract
  - SDK
  - server API
  - trace/operator
  - tests/fixtures

Out of scope:

- no behavior change
- no public API deletion yet

Verification:

```powershell
.\mvnw.cmd -pl xa-mass-engine -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=EngineSchedulingCoreArchitectureGuardTest,EngineSchedulingCoreSuite" test
```

Acceptance:

- the inventory is documented
- guards fail on new context-first scheduling dependencies

### Phase WC-1: Move Remaining Routing Fixtures To Worker Attributes

Status: partially implemented. Representative engine and server routing proof
now uses stateless worker attributes instead of WorkerContext registration
attributes. Broader legacy fixture retirement remains future work.

Goal: prove current routing behavior without `WorkerContext` as the source of
matching attributes.

Scope:

- update representative engine routing tests to register stateless workers with
  worker attributes instead of worker contexts
- update server focused routing E2E fixtures from
  `WorkerContextRegistration` to `WorkerRegistration.attributes`
- keep a small number of legacy context tests only to prove current deletion
  target until the next phase removes them
- keep canonical trace stable enough for existing schedule analyzers

Out of scope:

- no storage/API deletion
- no removal of runtime context lifecycle yet

Verification:

```powershell
.\mvnw.cmd -pl xa-mass-engine -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=RuleBasedTaskWorkerMatchingStrategyTest,WorkerMatchContextTest,EngineSchedulingCoreSuite" test
```

```powershell
.\mvnw.cmd -pl xa-mass-server -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=TaskApiWorkerAttributeRoutingIntegrationTest,TaskApiWorkerWithoutContextIntegrationTest,ServerSchedulingE2eSuite" test
```

Acceptance:

- behavior is still proven through worker scheduling attributes
- `WorkerContext` is no longer needed for routing proof

### Phase WC-2: Remove WorkerContext From Matching Handoff

Status: started. The remaining WorkerContext storage read was moved out of
`RuleBasedTaskWorkerMatchingStrategy` into `WorkerSchedulingCandidateEnumerator`.
The strategy now consumes candidates instead of owning context enumeration.
Representative strategy tests now prove normal routing and trace behavior with
stateless worker scheduling attributes; the remaining context-backed strategy
fixtures are explicitly named `legacyContext*` and guarded as transitional
coverage. `RuleBasedTaskWorkerMatchingStrategy` no longer imports
`WorkerContext`; the strategy package's only direct production import/storage
read is the transitional candidate enumerator. Worker-level assignment
diagnostics now consume `WorkerSchedulingCandidate`, so matching strategy code
does not unwrap `candidate.getWorkerContext()` directly. `WorkerMatchContext`
now owns the rule and diagnostic snapshot field map used by both QLExpress
evaluation and prefilter rejection records, so `RuleBasedTaskWorkerMatchingStrategy`
no longer carries a duplicate `workerScheduling*` / `workerContext*` snapshot
builder. `WorkerSchedulingCandidate` still carries nullable legacy
`WorkerContext` for runtime binding; full removal is not complete.

Goal: make engine matching fully worker-view based.

Scope:

- remove `WorkerContext` from `WorkerSchedulingCandidate`
- update `RuleBasedTaskWorkerMatchingStrategy.enumerateSchedulingCandidates(...)`
  to create one candidate per worker
- remove `WorkerManager.getWorkerContextsByWorkerIds(...)` from
  `RuleBasedTaskWorkerMatchingStrategy` first, then from the transitional
  candidate enumerator after context-backed matching is retired
- update `WorkerSchedulingView.from(...)` so it no longer accepts a
  `WorkerContext`
- remove context allocatability/project/routing prefilter branches
- update `WorkerMatchContext` to stop exposing `workerContext*` variables
- update assignment diagnostics to snapshot worker scheduling view, not context
  snapshot

Out of scope:

- no storage/API deletion yet
- no account-switch protocol

Verification:

```powershell
.\mvnw.cmd -pl xa-mass-engine -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=WorkerSchedulingCandidateTest,WorkerMatchContextTest,RuleBasedTaskWorkerMatchingStrategyTest,EngineSchedulingCoreSuite" test
```

Focused transitional verification:

```powershell
.\mvnw.cmd -pl xa-mass-engine -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=WorkerSchedulingCandidateEnumeratorTest,RuleBasedTaskWorkerMatchingStrategyTest,EngineSchedulingCoreArchitectureGuardTest" test
```

Acceptance:

- no production matching code imports `WorkerContext`
- no rule context exposes `workerContext*`
- context-first source guard is updated from "allowed transitional use" to
  "forbidden in matching"

### Phase WC-3: Remove Legacy WorkerContext Runtime Lifecycle

Goal: delete the engine-owned context slot state machine.

Scope:

- delete `LegacyWorkerContextResourceLifecycle`
- delete `WorkerDispatchResourceUsage.legacyWorkerContextResource` or collapse
  it to worker-level resource usage only
- remove context prepare/release calls from `SimpleTaskDispatchBinder`
- remove context release calls from `TaskResourceReleaseListener`
- remove `WORKER_CONTEXT_STATUS_TRANSITION` emission from engine mainline
- remove context-specific attempt binding fields where they are no longer
  needed by runtime contracts

Out of scope:

- no broader result protocol redesign
- no account-switch execution hint unless separately planned

Verification:

```powershell
.\mvnw.cmd -pl xa-mass-engine -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=SimpleTaskDispatchBinderTest,TaskResourceReleaseListenerTest,WorkerDispatchResourceReleaserTest,EngineSchedulingCoreSuite" test
```

Acceptance:

- no engine production code mutates `WorkerContextStatus`
- `WorkerDispatchResourcePolicy` only decides worker-level exclusive lock and
  capacity usage
- resource release is fully worker/load based

### Phase WC-4: Remove WorkerContext Storage And Public API

Goal: delete the remaining CRUD surface.

Scope:

- delete `WorkerContext`
- delete `WorkerContextStatus`
- delete worker context methods from `WorkerStorage`
- update memory storage implementation
- delete worker context storage contract tests
- delete `WorkerManager` context methods
- delete SDK `WorkerContextRegistration` and related client methods
- delete server worker-context endpoints and request/response DTOs
- delete mock worker-context JSON fixtures
- update startup/demo data loader to use worker attributes only

Out of scope:

- no compatibility endpoints
- no adapter DTO fallback

Verification:

```powershell
.\mvnw.cmd -DskipTests compile
```

```powershell
.\mvnw.cmd -pl xa-mass-engine -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=EngineSchedulingCoreSuite" test
```

```powershell
.\mvnw.cmd -pl xa-mass-server -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=ServerSchedulingE2eSuite" test
```

Acceptance:

- `rg "WorkerContext|workerContext|WorkerContextStatus" xa-mass-base xa-mass-engine xa-mass-sdk xa-mass-server platform_infra/mass-storage-api platform_infra/mass-storage-memory -n`
  returns only deliberate trace/history compatibility references, if any
- no worker context endpoint exists
- no worker context SDK method exists

### Phase WC-5: Trace And Operator Contract Cleanup

Goal: align canonical trace with worker-level scheduling truth.

Scope:

- remove context-specific trace events from current scheduling scenarios
- replace `workerContextId`-based evidence with worker scheduling resource
  evidence where needed
- update `xa-mass-trace assignment` rows:
  - prefer `workerId`
  - prefer `workerSchedulingResourceId` only if a generic scheduling resource
    concept remains
  - remove or null out `workerContextId` only after analyzers are updated
- update scenarios:
  - `assignment-success-binding`
  - `background-worker-sharing`
  - `capacity-reservation-under-concurrency`
  - `cross-task-worker-fairness`
- update `TRACE_CONTRACT.md`

Out of scope:

- no MDC/projection proof substitution
- no database-derived analyzer evidence

Verification:

```powershell
.\mvnw.cmd -pl xa-mass-trace -am test
```

```powershell
.\mvnw.cmd -pl xa-mass-server -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=TaskApiBackgroundWorkerSharingTraceObservedIntegrationTest,ServerSchedulingE2eSuite" test
```

Acceptance:

- canonical JSONL can prove schedule scenarios without WorkerContext evidence
- trace contract no longer describes WorkerContext as schedule truth

### Phase WC-6: Final Source Guard And Documentation Rewrite

Goal: make retirement permanent.

Scope:

- update `EngineSchedulingCoreArchitectureGuardTest` to forbid production
  `WorkerContext` references across engine
- update storage/server/sdk guards if useful
- rewrite `WORKER_SCHEDULING_VIEW_BASELINE.md` from transitional baseline to
  current baseline
- update `SCHEDULING_UPGRADE_ROADMAP.md`
- update module READMEs and SDK examples
- remove obsolete tests rather than keeping compatibility tests

Verification:

```powershell
.\mvnw.cmd -DskipTests compile
```

```powershell
.\mvnw.cmd -pl xa-mass-engine -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=EngineSchedulingCoreSuite" test
```

```powershell
.\mvnw.cmd -pl xa-mass-server -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=ServerSchedulingE2eSuite" test
```

```powershell
.\mvnw.cmd -pl xa-mass-trace -am test
```

```powershell
git diff --check
```

Acceptance:

- engine scheduling kernel has no WorkerContext dependency
- worker scheduling proof is worker/read-view based
- no deprecated WorkerContext compatibility track remains

## Risk Notes

### Biggest Risk: Trace And Tests

The hardest part is not deleting the model class. The hard part is replacing all
test and trace proof surfaces that currently use `workerContextId` as convenient
evidence. Do that before deleting the storage/API surface.

### Biggest Design Trap: Recreating Context Under Another Name

Do not replace `WorkerContext` with another engine-owned account slot model.
If the replacement has CRUD, status transitions, bind task id, expiry, and
release inside engine, the retirement failed.

### Runtime Ownership Constraint

The binder should remain runtime claim/bind owner. It should not become an
account switch protocol or worker device manager.

### Observability Constraint

Do not add engine-side scan-heavy statistics to compensate for deleted context
views. Use trace, runtime queue facts, and bounded read-side diagnostics.

## Recommended First Implementation Slice

Start with Phase WC-0 and WC-1 together only if the diff stays small:

- add source guard coverage for no new `workerContext*` rule usage
- migrate one representative engine routing test and one server routing E2E
  fixture to worker attributes
- update docs to state that context retirement has begun, but not completed

Do not delete storage/API in the first slice. Prove routing without context
first, then delete the model surface.
