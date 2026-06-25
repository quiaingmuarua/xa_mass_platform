# Worker Runtime Selected Worker Mailbox Evidence Convergence Roadmap

Status: mainline complete; optional shared runtime projection deferred until
split-runtime deployment requires it.

## Summary

This roadmap narrows the remaining adapter-mailbox dispatch gap to one runtime
truth:

```text
selectedWorkerId -> adapterMailboxKey
```

The target is a minimal worker-runtime evidence path that allows delivery
integration to resolve the already selected worker into an opaque adapter
mailbox target. It must not grow into a worker statistics, dashboard,
inspection, endpoint inventory, or scheduling read-model system.

This roadmap supersedes
`WORKER_RUNTIME_DELIVERY_TARGET_EVIDENCE_RUNTIME_CONVERGENCE_ROADMAP.md`.

## Final Owner Model

Scheduling owner:

```text
workerGroup / task policy / worker runtime evidence -> selectedWorkerId
```

Worker-runtime evidence owner:

```text
selectedWorkerId -> adapterMailboxKey evidence
```

Delivery owner:

```text
dispatch queue keyed by adapterMailboxKey
```

Adapter owner:

```text
selectedWorkerId -> local worker session
protocol send / poll / receive result
```

Result owner:

```text
result queue -> engine result convergence
```

The intended high-level flow is one input queue into execution and one output
queue back into result convergence. The adapter remains transparent to business
logic and does not participate in task scheduling, retry, compensation, or
result policy. Delivery consumes mailbox evidence; it does not own or derive
the selected-worker-to-mailbox projection.

## Current Code Observations

- `TaskDispatchRoutingSubmitter` resolves selected-worker delivery target
  evidence after engine assignment and before transport handoff.
- `SelectedWorkerDeliveryTargetEvidence` currently carries `workerId`,
  `adapterMailboxKey`, generation, observed time, and expiry.
- `TaskDispatchRoutingSubmitter` already rejects missing, stale, or
  mismatched evidence and emits delivery failure evidence for engine
  compensation.
- `InMemoryWorkerPresenceRuntime` is currently the only production
  implementation that materializes selected-worker mailbox evidence from
  session presence.
- `WorkerSessionPresenceEvent` carries explicit `adapterMailboxKey`,
  `WorkerRuntimePresenceIngress` forwards that field into worker-runtime
  presence, and focused proof covers `adapterId != adapterMailboxKey`.
- `EngineConfig.setWorkerDeliveryTargetResolver(...)` now records explicit
  delivery-target-resolver configuration, and `MassApplication` fails fast for
  split engine-producer roles that do not provide an explicit resolver.
- Split runtime tests inject a selected-worker resolver as injection-mode
  proof. That proves the deployment-injected contract, not a shared runtime
  truth.
- `InMemoryWorkerPresenceRuntime.activeSessionCount(...)` has been removed;
  session count assertions are no longer part of the delivery-target proof.
- Transport endpoint lease stores and mailbox consumer leases are delivery
  executor evidence. They must not become the producer-side worker-to-mailbox
  source.

Implemented slice facts:

- embedded/in-memory session presence can produce
  `selectedWorkerId -> adapterMailboxKey`
- submitter rejects missing, stale, and mismatched delivery target evidence
- embedded assembly can receive an injected selected-worker resolver
- split engine-producer startup fails without an explicit delivery target resolver
- missing, expired, and mismatched target evidence each emit one producer-side
  delivery failure outcome
- delivery-target evidence mainline has no list/count/stats/snapshot/inspect
  API

Remaining roadmap work:

- decide later whether a shared runtime projection is needed beyond explicit
  deployment injection
- keep guards/docs aligned if a future shared projection is added

## Owner Review

Worker runtime owns selected-worker mailbox evidence.

Engine/starter may consume that evidence after worker selection. They must not
derive mailbox targets from endpoint leases, route keys, connection ids,
session handles, transport node ids, adapter ids, or delivery bucket ids.

Transport owns mailbox queueing, destructive mailbox poll mechanics, mailbox
consumer availability, and delivery outcomes. Transport does not own
`selectedWorkerId -> adapterMailboxKey`.

Concrete adapters own protocol sessions and final-hop local demux by worker id.
They may publish session observations that worker runtime projects into mailbox
evidence, but those observations are ingress facts, not assignment truth.

Result convergence remains engine-owned. Result queues and result ingress
carriers may move results across process boundaries, but they do not decide
retry, terminal state, public result shape, or task lifecycle mutation.

## Hard Rule: No Statistics In The Mainline

All statistics, dashboards, snapshots, list views, counts, and inspection views
must stay out of the selected-worker mailbox evidence mainline.

Allowed in this roadmap:

- the minimal evidence needed to decide whether the already selected worker has
  a current mailbox target
- generation or expiry fields only when they are directly used to reject stale
  delivery evidence
- test-only proof fixtures that are deleted or kept under test scope only

Forbidden in this roadmap:

- global worker inventories
- per-group worker counts
- adapter session lists
- endpoint/session dashboards
- `list*`, `stats*`, `snapshot*`, `count*`, or `inspect*` APIs on the delivery
  target mainline
- queue statistics used as worker reachability truth
- tracing or diagnostics fields that change delivery behavior
- opportunistic counters added to submitters, presence projection, or
  evidence resolution because they are convenient during implementation

If an operational view is genuinely needed later, it must be a separate
side-channel roadmap with its own owner, storage cost, read path, and proof. It
must consume runtime facts without driving scheduling or delivery decisions.

Implementation guidance:

- The mainline evidence path may use a store, projection, or injected view, but
  it must expose only point lookup by `selectedWorkerId`.
- No secondary index is allowed in this roadmap unless it is required for
  freshness cleanup and is not readable by dispatch.
- A cleanup/expiry index is not a view. It must not be used for scheduling,
  delivery routing, or operator inspection.

## Boundary Decision

The mainline read contract stays narrow:

```java
Optional<SelectedWorkerDeliveryTargetEvidence> resolveDeliveryTarget(String selectedWorkerId)
```

Required semantics:

- the returned `workerId` must match the requested `selectedWorkerId`
- `adapterMailboxKey` is opaque to engine/starter and transport producers
- missing, expired, or mismatched evidence means delivery is infeasible for the
  selected worker
- session reconnect on the same mailbox refreshes delivery evidence without
  changing worker identity
- moving a worker to another mailbox invalidates older evidence through
  generation, expiry, or replacement semantics

Field discipline:

- `workerId` and `adapterMailboxKey` are required.
- `expiresAtEpochMillis` is allowed only as freshness evidence.
- `generation` is allowed only as stale-target protection.
- `reachabilityState` is allowed only if submitter behavior requires a
  bounded deliverable/not-deliverable decision.
- If the only production caller needs a yes/no delivery decision, a later slice
  may narrow reachability to store-private state plus `isDeliverable(...)`
  behavior; do not add more reachability labels for inspection in this roadmap.
- `observedAtEpochMillis` must be re-evaluated in SWME-0. If no current
  strategy needs it, it should move to diagnostics or store-private metadata
  instead of staying in the hot-path evidence record.

## Proof Philosophy

Durable proof should protect owner boundaries and end-to-end behavior, not the
current internal strategy.

Stable proof:

- E2E or integration tests proving an assigned item reaches only the selected
  worker through the resolved adapter mailbox
- split-role proof that producer delivery target resolution can read real or
  explicitly injected worker-runtime evidence
- guards preventing endpoint/session/route/bucket/adapter ids from returning
  to producer-side routing
- guards preventing statistics/view APIs from entering the delivery target
  mainline

Support proof only:

- store implementation unit tests
- generation/expiry implementation details
- temporary counters used to prove a slice during migration

Support proof must not freeze internal strategy. If a later implementation
replaces the store, generation algorithm, expiry mechanism, or session
projection strategy while preserving the owner contract and E2E behavior, the
roadmap should allow it.

## Non-Goals

- Do not change worker selection or scheduling policy.
- Do not add adapter process authentication, authorization, or mailbox
  ownership policy.
- Do not implement transport carrier convergence in this roadmap.
- Do not move result ingress or task-result decode ownership.
- Do not introduce global worker statistics or operational dashboards.
- Do not make transport validate worker group membership or worker capability.
- Do not put `adapterMailboxKey` into `TaskDispatchBinding` or base assignment
  models.
- Do not reintroduce route-owner stores or endpoint lease projections as
  producer-side delivery target truth.

## Do Not Start With

Do not start by adding a convenient worker view, session dashboard, Redis scan,
or endpoint inventory and then making dispatch read it.

Start by inventorying the exact writer and reader of
`selectedWorkerId -> adapterMailboxKey`, then keep the runtime contract narrow
enough that it cannot become a second worker registry.

## SWME-0 - Inventory And Field Disposition

Status: inventory created in
`2026-06-25_WORKER_RUNTIME_SELECTED_WORKER_MAILBOX_EVIDENCE_CONVERGENCE_INVENTORY.md`;
test-only session-count residue has been removed from worker-runtime mainline.

Scope:

- Inventory all production, test, and fake selected-worker delivery target
  resolvers.
- Inventory all writers of `WorkerSessionPresenceEvent` and any path that can
  feed selected-worker mailbox evidence.
- Classify every field on `SelectedWorkerDeliveryTargetEvidence` as one of:
  required delivery target, stale-protection evidence, diagnostic-only, or
  residue.
- Inventory any current `stats`, `snapshot`, `list`, `count`, or `inspect`
  access near worker presence, delivery target evidence, transport endpoint
  leases, and adapter mailbox routing.
- Confirm removed `InMemoryWorkerPresenceRuntime.activeSessionCount(...)`
  residue does not return as a delivery-target mainline read API.

Acceptance:

- The inventory separates runtime truth from diagnostics and tests.
- The inventory explicitly states whether `observedAtEpochMillis` remains
  mainline evidence or moves out of the hot path.
- No source claims that in-memory projection is sufficient for split runtime
  without deployment injection or shared runtime evidence.
- No view/statistics API is accepted as a prerequisite for delivery target
  resolution.
- The inventory records the removed `activeSessionCount(...)` residue and the
  guard prevents it from returning as a public mainline read API.

## SWME-1A - Formalize Injected Resolver And Startup Guard

Status: implemented.

Implemented proof:

- `EngineConfig` tracks whether a selected-worker delivery target resolver was
  explicitly configured.
- `MassApplication` fails startup for `ENGINE_PRODUCER` when no explicit
  delivery target resolver is configured.
- `WorkerRuntimePresenceIngressTest` proves `adapterId=websocket` with
  `adapterMailboxKey=mailbox-a` resolves to `mailbox-a`.

Scope:

- Formalize the selected-worker resolver injection seam in
  `EngineConfig` / `MassApplication` as the split-runtime producer contract.
- Define which profiles use embedded in-memory worker presence as the default
  resolver and which profiles require an explicit injected/shared resolver.
- Add startup failure for producer roles that enable adapter-mailbox dispatch
  but have neither embedded local presence nor an explicit delivery target
  resolver.
- Keep fake resolvers test-only and clearly named as injection-mode fixtures.
- Add SDK ingress proof that `WorkerRuntimePresenceIngress` preserves
  `adapterMailboxKey` when it differs from `adapterId`.

Acceptance:

- The injection contract is documented as worker-runtime evidence, not
  transport endpoint evidence.
- Embedded/local profile defaults remain explicit: local worker presence may be
  the delivery target resolver only when producer and session observations live in
  the same runtime.
- Split producer profile cannot silently fall back to an empty or local-only
  in-memory resolver when external evidence is required.
- Tests that use injected resolvers say they are testing injection mode; they do
  not imply a shared distributed projection already exists.
- `WorkerRuntimePresenceIngressTest` or an equivalent focused test proves
  `adapterId=websocket` and `adapterMailboxKey=mailbox-a` resolve to
  `mailbox-a`.
- Producer-side delivery target resolution imports no transport endpoint lease,
  route-owner, route-key, connection, session, or transport-node classes.
- No shared store is introduced in this slice.
- No statistics, list views, global scans, or dashboard projections are added
  to satisfy this slice.

## SWME-1B - Optional Shared Runtime Projection

Status: deferred until split-runtime evidence owner decision.

Scope:

- Add a worker-runtime-owned shared projection/store for
  `selectedWorkerId -> adapterMailboxKey` only if injection is insufficient for
  the accepted split runtime deployment model.
- If a shared store is added, its dispatch-facing read contract is point
  lookup by `selectedWorkerId`. It must not provide group-wide, mailbox-wide,
  adapter-wide, or global worker listing APIs.
- Keep writes near worker-runtime presence ingress, not transport handoff.
- Preserve generation/expiry semantics only to the degree needed for stale
  delivery protection.
- Do not reuse transport endpoint lease stores as the producer-side read model.

Acceptance:

- A split engine-producer can resolve the same mailbox evidence observed from a
  transport-consumer session without a fake test-only view.
- Reconnect on the same mailbox refreshes evidence without producing a
  spurious missing-target failure.
- Moving a worker to another mailbox invalidates older evidence.
- No statistics, list views, global scans, or dashboard projections are added
  to satisfy this slice.
- Any cleanup/expiry index is private to the store and not exposed as a
  dispatch or inspection read path.

## SWME-2 - Assembly And Failure Semantics

Status: implemented for producer-side delivery target evidence failures.

Scope:

- Keep missing, expired, and mismatched target evidence as delivery failure
  input for engine retry/reassign/compensation.
- Prove the producer-side failure path emits one delivery outcome and does not
  duplicate compensation when evidence is missing, stale, expired, or
  mismatched.
- Keep any profile-specific startup guard from SWME-1A aligned with failure
  semantics: startup catches missing required evidence wiring; runtime failure
  handles stale or temporarily unavailable evidence.

Acceptance:

- Missing, expired, and mismatched evidence each emit one delivery failure
  outcome for the selected command.
- `TaskDispatchBinding` and base assignment models still carry worker
  selection only; they do not carry `adapterMailboxKey`.
- No retry, reassignment, compensation, or final task policy moves into worker
  runtime or transport.

## SWME-3 - Guards, Docs, And Proof

Status: implemented for the current mainline; future shared projection work
must extend the same guard scope.

Scope:

- Update worker-runtime docs, transport baseline, proof registry, and active
  roadmaps to name worker-runtime selected-worker mailbox evidence as the
  source of `selectedWorkerId -> adapterMailboxKey`.
- Add guards that prevent producer/starter delivery code from reading endpoint
  lease stores, route owner stores, route keys, connection ids, session
  handles, transport node ids, or adapter ids for mailbox resolution.
- Add guards that prevent statistics/view APIs from becoming the delivery
  target read path.
- Guard scope should target the delivery-target mainline packages and callers:
  `SelectedWorkerDeliveryTargetEvidence`,
  worker-runtime delivery-target projection/store implementations,
  `TaskDispatchRoutingSubmitter`, and profile assembly that wires the
  resolver. Do not use broad repository-wide scans that would block unrelated
  diagnostics or test helpers.

Acceptance:

- Current owner docs and non-superseded roadmaps do not describe
  `deliveryBucketId + selectedWorkerId`, route-owner pointers, endpoint lease
  projection, adapter id, route key, or connection id as producer-side delivery
  target truth. Superseded historical roadmaps may mention older models only as
  explicit "do not restore" context.
- Architecture guards fail if assignment/base models gain `adapterMailboxKey`.
- Architecture guards fail if producer-side submitter imports transport
  endpoint lease or route-owner store classes.
- Architecture guards or tests fail if the selected-worker mailbox evidence
  mainline grows `stats`, `snapshot`, `list`, `count`, or `inspect` APIs or
  if submitter/profile assembly starts consuming a diagnostic view as routing
  truth.

## Verification Candidates

These are candidates and must be corrected after SWME-0 inventory. Completion
proof must not rely on missing test classes hidden by
`failIfNoSpecifiedTests=false`.

```powershell
.\mvnw -q -pl xa-mass-worker-runtime -DskipTests compile
.\mvnw -q -pl sdk/xa-mass-embedded-sdk -am test "-Dtest=WorkerRuntimePresenceIngressTest,MassApplicationDistributedTransportTest,TaskDispatchRoutingSubmitterTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw -q -pl transport/transport_runtime -am test "-Dtest=TransportConvergenceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Current verification note:

- `xa-mass-worker-runtime -Dtest=InMemoryWorkerPresenceRuntimeTest` is blocked
  by unrelated stale worker-runtime test sources that reference removed
  candidate/admission classes. Main compile passes, and SDK/transport focused
  proofs cover the SWME integration surface.
- If SWME-1B adds shared runtime evidence later, add mandatory focused tests
  for that implementation and run them without
  `-Dsurefire.failIfNoSpecifiedTests=false`.

Mandatory proof additions before this roadmap can be marked complete:

- `WorkerRuntimePresenceIngressTest` or equivalent must prove
  `adapterId != adapterMailboxKey` is preserved into
  `resolveDeliveryTarget(workerId)`.
- A split-profile assembly test must prove producer startup fails when a
  required delivery target resolver is absent, or explicitly documents and verifies
  injection mode.
- `TaskDispatchRoutingSubmitterTest` or equivalent must prove missing,
  expired, and mismatched delivery target evidence each emit one producer-side
  delivery failure outcome.
- If SWME-1B lands, shared projection tests must prove point lookup by
  `selectedWorkerId` without adding listing/statistics APIs.

## Completion Criteria

- Split engine-producer and transport-consumer roles have a real
  selected-worker mailbox evidence path or an explicit deployment-injected
  view.
- Delivery integration resolves `adapterMailboxKey` only after scheduling has
  selected `selectedWorkerId`.
- Transport remains an adapter-mailbox delivery executor and does not own
  worker-to-mailbox truth.
- Adapter remains transparent to business logic and only performs local
  selected-worker final-hop send/poll/result receive mechanics.
- Result ingress remains result-queue-to-engine convergence, not adapter or
  transport business logic.
- The selected-worker mailbox evidence mainline contains no statistics,
  dashboards, list views, global scans, or inspection APIs.
- Any temporary proof counters or test fixtures are test-scoped or removed
  before this roadmap is marked complete.
