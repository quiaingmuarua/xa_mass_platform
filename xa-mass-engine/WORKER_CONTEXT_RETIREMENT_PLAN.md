# WorkerContext Retirement Plan

Last updated: 2026-05-16

Status: active phased plan. WorkerContext physical model, storage, SDK/server
API, frontend/operator resource pages, test-only worker-context fixtures, and
runtime/transport/projection payload use have been removed. Remaining
`workerContextId` references are limited to the nullable canonical trace schema,
trace logger schema population, source guards, and historical documentation.

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

`WorkerContext` physical model, storage CRUD, SDK registration/query surface,
server endpoints, runtime lease payloads, transport payloads, compatibility
projection rows, and public result rows have been deleted. Remaining references
are trace-schema residue, source guards, and migration documentation:

- legacy rule/read-model compatibility:
  - `workerContext*` variables have been retired from `WorkerMatchContext`
  - model tests verify worker-level scheduling fields and absence of legacy
    rule-context keys
- trace and diagnostics:
  - nullable canonical trace `identity.workerContextId`
  - historical traces may still contain legacy context fields, but current
    engine/runtime/transport payloads do not feed them
- tests and fixtures:
  - trace/operator scenarios that reject context-backed proof
  - worker scheduling test fixtures declare routing and capability directly on
    worker attributes

Current convergence work already reduced the blast radius:

- matching handoff uses `WorkerSchedulingCandidate`
- production matching candidate enumeration is one worker to one candidate and
  no longer reads WorkerContext storage
- matching rules prefer `workerScheduling*`
- dispatch resource usage is owned by `WorkerDispatchResourcePolicy`
- transitional context runtime lifecycle mutation has been removed from binder
  and release paths
- repeated reservation and worker-lock cleanup is owned by
  `WorkerDispatchResourceReleaser`
- attempt resource cleanup is task/worker based; `workerContextId` is no longer
  accepted as a `WorkerDispatchResourcePolicy` input
- dispatch binding now creates runtime claim targets through
  `WorkerClaimTarget.workerLevel(...)`; context identity constructors have been
  removed
- current dispatch binding generates attempt ids through the worker-level
  attempt-id helper; context-inclusive attempt-id construction has been removed
- result correlation and result runtime drafts no longer carry
  `workerContextId`; `workerId` / `batchId` / `attemptId` are the execution
  identity
- runtime work-contract, in-memory runtime, Redis runtime, SDK result rows,
  HTTP result rows, transport dispatch items, and compatibility projection rows
  no longer expose `workerContextId`
- dispatch bindings now use worker-level construction; context-backed dispatch
  payload construction has been removed
- architecture guards prevent context-first handoff types and scattered context
  state mutation from returning

## Worker-Management Boundary Inventory

Worker/device/account management should not become an engine scheduling
subsystem. The engine should keep only the kernel-facing surfaces it needs:

- a readable worker scheduling view
- transport reachability facts
- process-local load and reservation facts
- dispatch events and result convergence
- canonical trace/audit evidence

Everything else belongs to worker-management/system-event ownership:

- device/account CRUD
- account switch inventory
- account health and invalidation events
- worker capability refresh from heartbeat/system events
- operator-facing worker administration APIs

Remaining `WorkerContext` references should be retired by owner, not by a
repo-wide rename:

| Owner surface | Current WorkerContext use | Retirement direction |
| --- | --- | --- |
| Engine matching | no WorkerContext rule fields or candidate expansion remain | keep `WorkerSchedulingCandidate` / `WorkerSchedulingView`; do not reintroduce context payloads or context rule variables |
| Engine runtime resource lifecycle | no WorkerContext model or `workerContextId` runtime payload remains | keep worker lock, capacity reservation, attempt close, load finalization, and resource release as runtime proof; do not feed `workerContextId` into release policy |
| Engine diagnostics | assignment records snapshot `WorkerSchedulingView`; no context identity is recorded | keep worker scheduling evidence as the diagnostic subject; do not reintroduce context lifecycle snapshots |
| Storage | WorkerContext CRUD and lookup methods have been deleted | keep worker-only storage; do not recreate account/context CRUD in engine storage |
| SDK | `WorkerContextRegistration` and context query operations have been deleted | declare scheduling capability with `WorkerRegistration.attributes` / event bindings or future system events |
| Server/API | runtime worker-context endpoints and auth catalog entries have been deleted | do not replace with engine-owned account CRUD |
| Trace/operator | nullable canonical `identity.workerContextId` for historical schema compatibility | analyzers must use worker scheduling/load/resource proof; do not add new context-count evidence |
| Tests/fixtures | source guards and trace fixtures reject context-backed proof | default scheduling proof must be stateless worker attributes |

The runtime lifecycle, diagnostic snapshot, public API, SDK, transport,
projection, and storage deletions are complete for WorkerContext as a model and
as a runtime payload. Remaining cleanup is trace-schema/documentation residue
and must not reintroduce account CRUD into the engine.

## Proof Replacement Matrix

Scheduling proof has moved from WorkerContext identity to worker scheduling
evidence. This matrix records the replacement surface that must be preserved.

| Current proof habit | Replacement proof |
| --- | --- |
| `workerContextId` proves routing selected the right account/context | `workerSchedulingAttributes`, `workerSchedulingRoutingTags`, and `workerSchedulingMatchesRoutingCode` prove worker-level routing |
| `WorkerContextStatus IDLE -> RESERVED -> OCCUPIED` proves assignment resource ownership | `WORKER_MATCH_ACCEPTED`, worker capacity reservation fields, `WORKER_LOCK_ACQUIRED`, and dispatch binding summary prove scheduling/resource handoff |
| context project proves project eligibility | `Worker.supportedProjects`, `supportsProject`, and worker scheduling attributes prove project/capability eligibility |
| context release proves scheduling cleanup | attempt close, `RESOURCE_RELEASED`, `WORKER_LOCK_RELEASED`, and worker load finalization prove cleanup |
| unique worker context counts prove dispatch spread | `uniqueWorkerCount`, worker scheduling resource evidence, and assignment/binding counts prove dispatch spread |

The `worker-attribute-routing-without-context` trace scenario is the canonical
proof that stateless worker attributes can satisfy routing without
`workerContextId`. The `worker-resource-cleanup-without-context` trace scenario
is the canonical proof that stateless worker cleanup can be shown through
attempt close, worker lock release, and worker-level `RESOURCE_RELEASED` without
`workerContextId` or `WORKER_CONTEXT_STATUS_TRANSITION`. Legacy context
lifecycle fixtures may exist only as historical trace data. New scheduling
proof must prefer the replacement evidence above.

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

`workerContext*` rule variables are retired from the engine rule context after
all in-repo rules and fixtures moved to worker-level scheduling fields.

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

- add or extend source guards so new production scheduling code cannot call
  WorkerContext storage APIs
- guard against new `workerContext*` default rules, test fixtures, and engine
  rule-context fields
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

Status: complete. Representative engine and server routing proof now uses
stateless worker attributes instead of WorkerContext registration attributes,
and context-backed routing fixtures are no longer part of the normal scheduling
proof surface.

Goal: prove current routing behavior without `WorkerContext` as the source of
matching attributes.

Scope:

- update representative engine routing tests to register stateless workers with
  worker attributes instead of worker contexts
- update server focused routing E2E fixtures from
  `WorkerContextRegistration` to `WorkerRegistration.attributes`
- keep canonical trace stable enough for existing schedule analyzers

Out of scope:

- no account-switch protocol

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

Status: complete. `RuleBasedTaskWorkerMatchingStrategy` no longer imports
`WorkerContext` or reads WorkerContext storage. `WorkerSchedulingCandidateEnumerator`
now creates one worker-level candidate per worker and no longer expands legacy
context-backed candidates. Representative strategy tests prove normal routing,
trace, prefilter, load ranking, capacity reservation, and background sharing
with stateless worker scheduling attributes. Context-backed strategy fixtures
have been removed from the matching proof surface. Worker-level assignment
diagnostics consume `WorkerSchedulingCandidate`, so matching strategy code does
not unwrap WorkerContext payloads directly. `WorkerMatchContext` owns
the rule and diagnostic snapshot field map used by both QLExpress evaluation
and prefilter rejection records, so `RuleBasedTaskWorkerMatchingStrategy` no
longer carries a duplicate `workerScheduling*` / `workerContext*` snapshot
builder. WC-3E removed `workerContext*` variables from `WorkerMatchContext`
and stopped `WorkerSchedulingView` from flattening WorkerContext
status/project/routing/attributes into scheduling facts. Canonical trace includes worker scheduling evidence on worker match
rows, and `worker-attribute-routing-without-context` proves stateless worker
attribute routing without using `workerContextId`. `WorkerSchedulingCandidate`
now carries only `Worker` plus `WorkerSchedulingView`, and
`WorkerSchedulingView` no longer accepts or reads `WorkerContext`. Assignment
diagnostics now snapshot worker-level scheduling evidence through
`WorkerSchedulingSnapshot`; legacy context identity is always absent from
scheduling snapshots on the default path.

Goal: make engine matching fully worker-view based.

Scope:

- remove `WorkerContext` from `WorkerSchedulingCandidate` (done)
- update `RuleBasedTaskWorkerMatchingStrategy.enumerateSchedulingCandidates(...)`
  to create one candidate per worker
- remove `WorkerManager.getWorkerContextsByWorkerIds(...)` from
  `RuleBasedTaskWorkerMatchingStrategy` and from matching candidate enumeration
  (done)
- update `WorkerSchedulingView.from(...)` so it no longer accepts a
  `WorkerContext` (done)
- remove context allocatability/project/routing prefilter branches (done)
- update `WorkerMatchContext` to stop exposing `workerContext*` variables
  (done)
- update assignment diagnostics to snapshot worker scheduling view, not context
  snapshot (done)

Out of scope:

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
- no production matching code reads WorkerContext storage
- `WorkerSchedulingView` does not import, accept, or expose WorkerContext
  identity
- no context-backed matching fixtures remain in
  `RuleBasedTaskWorkerMatchingStrategyTest`
- no rule context exposes `workerContext*`
- context-first source guard is updated from "allowed transitional use" to
  "forbidden in matching"

### Phase WC-3: Remove Legacy WorkerContext Runtime Lifecycle

Status: complete.
`LegacyWorkerContextResourceLifecycle` has been deleted and engine
binder/release paths no longer mutate `WorkerContextStatus`. Assignment
diagnostics no longer snapshot `WorkerContext` lifecycle state.
`WorkerSchedulingCandidate` no longer carries a nullable `WorkerContext`
payload. `WorkerDispatchResourceUsage.legacyWorkerContextResource` has been
collapsed into worker-level exclusive-lock usage, and the default binder no
longer passes candidate `workerContextId` into runtime claim targets.

Goal: delete the engine-owned context slot state machine.

Prerequisite: trace and tests for the scheduling path must already have
worker-level proof replacements for routing, resource handoff, and cleanup.
Do not start this phase while a scheduling scenario still depends on
`workerContextId` as its primary success evidence.

Scope:

- delete `LegacyWorkerContextResourceLifecycle` (done)
- remove context prepare/release calls from `SimpleTaskDispatchBinder` (done)
- remove context release calls from `TaskResourceReleaseListener` (done)
- remove `WORKER_CONTEXT_STATUS_TRANSITION` emission from engine mainline (done)
- delete engine-owned `WorkerContextSnapshot` assignment diagnostics (done)
- delete `WorkerDispatchResourceUsage.legacyWorkerContextResource` or collapse
  it to worker-level resource usage only (done)
- remove context-specific attempt binding fields from runtime contracts (done)

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

Goal: delete the remaining CRUD surface. Status: complete in the current
mainline; only nullable canonical trace schema residue remains.

Scope:

- delete `WorkerContext`
- delete `WorkerContextStatus`
- delete worker context methods from `WorkerStorage`
- update memory storage implementation
- delete worker context storage contract tests
- delete `WorkerManager` context methods
- delete SDK `WorkerContextRegistration` and related client methods
- delete server worker-context endpoints and request/response DTOs
- delete legacy mock worker-context JSON fixture inputs
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

Status: mostly complete. Current schedule analyzers use worker scheduling,
load, resource, and assignment evidence rather than WorkerContext identity.
`uniqueWorkerContextCount` has been removed from assignment rows. The nullable
canonical trace `identity.workerContextId` field remains for historical schema
compatibility.

Goal: align canonical trace with worker-level scheduling truth.

Scope:

- keep context-specific trace events out of current scheduling scenarios
- keep `workerContextId` out of scheduling success evidence
- update `xa-mass-trace assignment` rows:
  - prefer `workerId`
  - prefer `workerSchedulingResourceId` only if a generic scheduling resource
    concept remains
  - keep `workerContextId` nullable and unused by analyzers until a dedicated
    canonical schema cleanup removes it
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
