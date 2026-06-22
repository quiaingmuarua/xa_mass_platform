# Transport Routing Envelope And Adapter Mailbox Convergence Roadmap

Status: slice complete, roadmap active; embedded adapter-mailbox dispatch
mainline and result `RoutingEnvelope` ingress are implemented and verified.
Distributed worker delivery target evidence and external-adapter process phases
remain. Dispatch carrier convergence is superseded and implemented by
`../doc/archive/transport/2026-06-22_TRANSPORT_DISPATCH_ROUTING_ENVELOPE_CARRIER_CONVERGENCE_ROADMAP.md`.

## Summary

Converge transport dispatch toward an adapter-mailbox routing boundary, with a
minimal routing carrier that can later cover result ingress:

```java
public record RoutingEnvelope(
        String envelopeId,
        RoutingTarget target,
        String payload,
        Map<String, String> diagnostics,
        long createdAtEpochMillis
) {}

public record RoutingTarget(
        String ownerKind,
        String ownerRef
) {}
```

The immediate goal is not to run adapters as independent processes. The
immediate goal is to make assigned dispatch use the same mailbox boundary that
an independent adapter process could use later.

Current embedded adapters may remain in-process. They must stop depending on
Java object identity, endpoint/session internals, or worker-group bucket queue
selection as the cross-owner routing contract. The durable routing contract
uses the selected worker plus a worker-runtime supplied adapter mailbox target:

```text
dispatch:
  target.ownerKind = adapter-mailbox
  target.ownerRef  = adapter mailbox key
  payload          = assignment-derived adapter dispatch payload

result:
  target.ownerKind = result-ingress
  target.ownerRef  = result correlation / partition key
  payload          = starter/engine-owned result callback payload
```

`RoutingEnvelope` is a queue and process-boundary carrier. It is not a task
model, worker lifecycle model, worker capability model, or observability model.

This is a roadmap, not a plan-mode checklist. It keeps the full convergence
boundary visible while making the next executable slice clear. Do not split the
work into tiny rename-only phases; do not collapse worker-runtime evidence,
transport handoff, adapter demux, result inbox, and external adapter lifecycle
into one implementation pass.

## Relation To Existing Roadmaps

This roadmap supersedes the target shape in
`../doc/archive/transport/2026-06-22_TRANSPORT_WORKER_INGRESS_ENVELOPE_CONVERGENCE_ROADMAP.md`.
That older roadmap only covered worker-to-platform ingress and introduced
`ingressCode`. The new direction is broader and simpler: route by target owner,
let the target owner decode payload, and keep business classification inside
the owner-owned payload.

This roadmap also intentionally supersedes the bucket-derived dispatch queue
direction from earlier delivery-queue convergence work. The first dispatch
slice has moved the assigned-delivery handoff to adapter-mailbox addressing;
remaining references to bucket-derived dispatch queues are historical,
archived, or test guard vocabulary unless proven otherwise by source scan. The
new target is:

```text
Delivery physical target = adapterMailboxKey
Delivery correctness     = selectedWorkerId
WorkerGroup/bucket       = scheduling or indexing domain, not queue owner
```

`TRANSPORT_PUSH_ADAPTER_FINAL_HOP_BOUNDARY_CONVERGENCE_ROADMAP.md` remains
useful for WebSocket/socket final-hop cleanup, but it should align to this
roadmap before implementation: final-hop code should consume adapter-mailbox
dispatch payloads, not revive routeKey, endpointAddress, or adapter-local
addressing as external routing contracts.

## Current Implementation State

- Engine assignment still reaches transport through
  `TaskDispatchRoutingSubmitter`; engine/base assignment remains
  mailbox-free.
- `TaskDispatchRoutingSubmitter` converts `TaskDispatchBinding` into
  `DeliveryCommand(...)`, then resolves the already selected worker through
  worker-runtime `WorkerDeliveryTargetView` to obtain an opaque
  `adapterMailboxKey`; the resolved evidence must still match the selected
  worker id from the assignment binding.
- Embedded adapter bindings now require an explicit `adapterMailboxKey`; the
  binding layer does not silently fall back to `adapterId`.
- Worker session presence events carry explicit `adapterMailboxKey`, allowing
  worker-runtime delivery target evidence to remain stable even when mailbox
  identity later diverges from adapter binding id.
- `TransportAssignedDeliverySubmitter` now submits
  `AdapterMailboxDeliveryCommand(adapterMailboxKey, command)` groups to
  `TransportDeliveryCommandHandoff`.
- In-memory and Redis delivery-command handoff now queue by
  `adapterMailboxKey` and use mailbox-level `AdapterMailboxConsumerAvailability`
  availability, not selected-worker consumer indexes.
- Adapter-owned mailbox consumers consume adapter-mailbox items from a
  mailbox-scoped dispatch handoff poll, invoke the concrete adapter's embedded
  `AdapterCommandExecutor.dispatch(List<DispatchRoutingItem>)`, and emit
  retryable delivery-failure evidence for known final-hop failures. There is no
  central mailbox mount or dispatch ack/complete owner.
- Endpoint lease publishers no longer project handoff-private selected-worker
  consumer evidence.
- Polling final-hop uses the adapter mailbox as the pull-buffer queue address;
  `selectedWorkerId` remains an entry-level demux constraint.
- WebSocket/socket/polling result paths currently normalize worker results
  into `RoutingEnvelope(target=result-ingress:<resultCorrelationRef>)`.
- `RuntimeTaskResultIngestChannel` decodes result `RoutingEnvelope` payloads
  using starter-owned `TaskResultCallbackCodec`, then calls
  `TaskResultIngestFacade`.
- `TransportResultIngressEnvelope` has been removed from production code.
- `RoutingEnvelope` is the result inbox carrier. Dispatch still uses
  `DeliveryCommand` / adapter-mailbox handoff records as the transport-owned
  assigned-delivery shape.
- The only production `WorkerDeliveryTargetView` implementation is currently
  the in-memory worker presence runtime. Split runtime tests can inject a fake
  view, but a shared/runtime projection is not yet a completed truth. That
  follow-up is tracked by
  `WORKER_RUNTIME_SELECTED_WORKER_MAILBOX_EVIDENCE_CONVERGENCE_ROADMAP.md`.
- Adapter mailbox consumer availability now uses finite leases with starter
  refresh; it is not an eternal process claim.
- `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  `doc/PROOF_REGISTRY.md`, Redis keyspace guards, and transport architecture
  guards have been updated and verified for the adapter-mailbox dispatch slice.

## Owner Review

Engine and scheduling own:

- task assignment truth
- selected worker choice
- retry, reassign, compensation, and final result policy
- task result application and final result rows

Worker runtime owns:

- worker declaration and scheduling evidence
- worker reachability projections when scheduling needs them
- selected-worker delivery target evidence when delivery reachability is part
  of worker selection. This evidence is not session/channel truth; it is the
  scheduling/runtime projection that says the selected worker currently maps to
  one opaque adapter mailbox.

Delivery-target strategy / embedded assembly owns:

- choosing the opaque adapter mailbox key for a deployment or embedded binding
- deciding when a selected worker's delivery target moves to another mailbox
- projecting `selectedWorkerId -> adapterMailboxKey` through worker-runtime
  delivery target evidence
- keeping mailbox key choice out of task assignment and out of concrete adapter
  business logic

Adapter host / adapter process owns:

- mounting and consuming the mailbox key it is configured with
- publishing mailbox consumer availability only when the handoff requires a
  bounded queue-safety fact
- keeping ordinary worker reconnects local to adapter session state instead of
  changing worker identity or task assignment

Transport routing owns:

- queue carrier shape
- adapter mailbox enqueue/drain mechanics
- bounded admission plus destructive mailbox poll mechanics
- delivery outcomes and failure evidence
- mailbox consumer availability, not worker-to-mailbox selection

Concrete adapters own:

- protocol I/O
- adapter mailbox consumption
- local selected-worker session or pull-buffer lookup
- final-hop send attempts
- wire/request normalization into routing envelopes

Starter / embedded assembly owns:

- delivery integration from `TaskDispatchBinding` into adapter-targeted
  dispatch envelopes by resolving a worker-runtime-owned delivery target view
  for the already selected worker
- later translation from engine-targeted result routing envelopes into
  `TaskResultIngestFacade`
- task-result payload and correlation codecs

`adapterId`, endpoint lease ids, connection ids, session handles, route keys,
and channel objects are not the cross-owner routing contract. They may remain
inside concrete adapter/runtime evidence, but routing across queues and future
process boundaries should use `RoutingEnvelope.target`.

`adapterMailboxKey` is not an `adapterId` rename. It is a stable delivery
mailbox address consumed by an adapter host. It is chosen by deployment,
assembly, or worker-runtime delivery-target strategy, not by concrete adapter
protocol/session code. It is not a protocol type, connection id, session
handle, route key, endpoint lease id, worker id, or public worker registration
field. A worker reconnect must not change the worker identity or force a new
mailbox key. A worker migration to another mailbox changes worker-runtime
delivery target evidence generation. For embedded Java adapters, the mailbox
key must be an explicit `TransportBinding` / `TransportAdapterContribution`
fact before it is used by worker-runtime evidence. The short-term default may
derive from the configured adapter id, but only as an embedded default source;
it is not conceptual identity with `adapterId`.

## Boundary Decision

`RoutingEnvelope.target.ownerKind` names the logical destination owner. Payload
decoding may be performed by that owner's bridge or assembly handler. It must
stay coarse and stable. Initial owner kinds are:

```text
adapter
result-ingress
```

`transport` is the queue/carrier owner in this roadmap, not a V1 payload target
owner. Do not introduce owner kinds such as `task.result`,
`task.result.success`, `worker.heartbeat`, `websocket`, `polling`,
`transport-dispatch`, or `diagnostics` in V1. Those are business event,
protocol, or payload categories, not payload-owner layers.

`RoutingEnvelope.target.ownerRef` is the target-owner routing handle:

- for `adapter`, it is the adapter mailbox key selected through
  worker-runtime delivery target evidence. In embedded mode the key is an
  explicit binding/contribution fact supplied by configuration or assembly; it
  is not derived by the concrete adapter from protocol/session state;
- for `result-ingress`, it is the result partition/correlation key consumed by
  the starter result bridge before engine result apply.

`RoutingEnvelope.payload` is opaque to the queue layer and to non-target
owners. The target owner or its bridge may decode it.

`RoutingEnvelope.diagnostics` is optional bounded debug data only. It must not
participate in routing, selected-worker correctness, retry policy, or lifecycle
decisions.

V1 intentionally omits:

- `source`
- `ingressCode`
- business success/failure fields
- adapter/session/connection fields
- payload content type negotiation

Those can be added only after a concrete owner needs them and the proof surface
shows they cannot live inside the owner-owned payload.

## Target Flow

### Dispatch

```text
TaskDispatchBinding
  -> binding carries selectedWorkerId + opaque payload/context
  -> delivery integration resolves WorkerDeliveryTargetView(selectedWorkerId)
  -> selected-worker delivery target evidence carries adapterMailboxKey
  -> dispatch translator
  -> RoutingEnvelope(target = adapter:<adapterMailboxKey>,
                     payload = adapter dispatch payload)
  -> adapter mailbox queue
  -> adapter mailbox consumer
  -> selectedWorkerId local demux
  -> protocol final-hop send
  -> DispatchOutcome / delivery failure evidence
```

Engine/base assignment must not carry `adapterMailboxKey` as scheduling output.
The delivery integration layer may resolve the mailbox only through a
worker-runtime-owned `WorkerDeliveryTargetView` or equivalent read surface for
the already selected worker. It must not calculate `adapterMailboxKey` by
reading endpoint lease, route, adapter registry, connection, or session state.
Transport must not perform a post-assignment worker-to-mailbox lookup.

The worker-runtime view/evidence shape should be narrow:

```text
WorkerDeliveryTargetView.resolve(selectedWorkerId)
  -> SelectedWorkerDeliveryTargetEvidence
  workerId
  adapterMailboxKey
  reachabilityState
  generation / observedAt / expiresAt
```

The exact class name may change during implementation, but the owner decision
does not: worker-runtime owns the projection that maps the selected worker to a
current adapter mailbox. Deployment/assembly owns the mailbox-key strategy.
The adapter host consumes the configured mailbox and performs local worker
session demux. Transport consumes the mailbox address as an opaque physical
delivery target.

The adapter dispatch payload should be the minimal data that the adapter needs
to deliver to the already selected worker:

```text
deliveryId
selectedWorkerId
worker payload
resultCorrelationRef
delivery observation timing
```

It must not include task shell metadata, worker group capability truth,
routeKey, endpointAddress, connection id, session handle, or queue owner facts.

### Result

```text
worker result frame / pull submit
  -> adapter normalizes to RoutingEnvelope(target = result-ingress:<resultCorrelationRef>,
                                          payload = starter-owned result callback payload)
  -> result routing queue / inbox
  -> starter-owned result bridge
  -> TaskResultCallbackCodec
  -> TaskResultIngestFacade
  -> engine result apply / retry / compensation
```

For current task-result payloads, `payload` may stay as the existing callback
JSON containing `resultCorrelationRef`, `success`, `resultCode`, and `result`.
Transport does not parse those fields. Engine/starter decides whether a failed
result is final, retryable, or compensating evidence.

`target.ownerKind = result-ingress` means the current payload is owned by the
starter result bridge before engine result apply. It must not be mislabeled as
`engine` unless a future engine-owned result payload schema is approved.

The bridge must validate that `target.ownerRef` matches the decoded
`resultCorrelationRef`, or derive `ownerRef` from the decoded payload before
enqueuing.

## Why Adapter Independent Process Can Be Deferred

Adapter independent process support is not required for the first implementation
slice because the hardest current problem is not process lifecycle. The hardest
problem is that embedded adapter code still leaks Java object wiring and
transport-internal ids into routing decisions.

Defer these to a later roadmap:

- adapter process lifecycle and heartbeat
- adapter process registration/discovery
- authentication, authorization, and tenant isolation
- envelope encryption or end-to-end payload privacy
- remote adapter deployment packaging
- cross-language SDK for adapter authors
- full result-inbox carrier convergence if dispatch mailbox convergence has
  not landed yet

Do not defer these:

- queue carrier shape
- adapter mailbox target contract
- worker-runtime selected-worker delivery target evidence
- removal of worker-group bucket queue selection from the dispatch routing
  contract

If this roadmap lands cleanly, a future remote adapter process can consume the
same adapter mailbox envelope without forcing another dispatch model rewrite.

## Non-Goals

- Do not rewrite engine assignment, worker matching, or task lifecycle.
- Do not make transport choose workers.
- Do not move retry, reassign, compensation, or finality into transport.
- Do not redesign public Java worker APIs in the first slice.
- Do not introduce authentication, permissions, or encryption.
- Do not preserve old carrier shapes as long-term aliases once callers move.
- Do not add an `ingressCode` or business subtype router in V1.
- Do not treat `deliveryBucketId` as adapter mailbox identity.
- Do not derive adapter mailbox from `deliveryBucketId + selectedWorkerId`
  inside transport.

## Do Not Start With

Do not start by adding a transport-owned mailbox resolver. That keeps transport
as a post-assignment routing engine. First make worker-runtime capable of
projecting selected-worker delivery target evidence, then carry the opaque
mailbox key through assignment.

Do not start by deleting `DeliveryCommand`. It remains the assigned-delivery
intent while dispatch mailbox convergence proceeds. Result ingress has already
converged to `RoutingEnvelope`; do not restore `TransportResultIngressEnvelope`
as a compatibility carrier.

Do not start by building a remote adapter process. That would add lifecycle,
security, and deployment noise before the routing contract is stable.

Do not start by adding a generic router that parses every payload. Routing uses
`target`; payload parsing belongs to the target owner.

## RTE-0 Inventory And Baseline Supersession

Status: implemented for the adapter-mailbox dispatch slice; keep active for
result carrier and external-adapter follow-up inventory.

Scope:

- Inventory dispatch producers and consumers:
  `TaskDispatchRoutingSubmitter`,
  `TransportAssignedDeliverySubmitter`,
  `TransportDispatchHandoff`,
  adapter-owned mailbox consumers, adapter command executors, and polling pull
  buffers.
- Inventory current selected-worker consumer evidence:
  `DeliveryCommandConsumerClaim`, `DeliveryCommandConsumerRegistry`,
  memory/Redis selected-worker consumer indexes, ready refs, and inflight refs.
- Inventory worker-runtime selected-worker evidence and assignment carriers:
  `WorkerReachabilityView`, `WorkerPresenceRuntime`, `SelectedWorkerHandle`,
  `TaskDispatchBinding`, `SimpleTaskDispatchBinder`, and starter dispatch
  translation.
- Classify all current uses of `deliveryBucketId`, `deliveryQueueKey`,
  `adapterId`, `endpointDriverId`, `routeKey`, endpoint lease id, connection
  id, and session handle.
- Pick the delivery integration read surface that resolves
  `selectedWorkerId -> adapterMailboxKey` after assignment. The assignment
  carrier remains selected-worker and payload/context only.
- Pick the embedded mailbox source. The target is an explicit
  `adapterMailboxKey` on `TransportBinding` / `TransportAdapterContribution`
  or their successor; deriving it from configured `adapterId` is only an
  embedded default, not a semantic identity rule.
- Mark `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  `doc/PROOF_REGISTRY.md`, Redis keyspace guards, and transport architecture
  guards as required updates when the corresponding code slice lands.

Acceptance:

- Bucket-derived dispatch queue truth is treated as historical/superseded
  context, not current owner truth or target direction.
- Adapter mailbox identity owner is adapter runtime/process lifecycle;
  worker-runtime only owns worker-to-mailbox delivery target evidence.
- The delivery integration read surface is named and the engine/base
  assignment carrier is explicitly mailbox-free.
- Embedded mailbox key source is named and is not hidden behind protocol,
  route, session, or implicit adapter id fallback.
- The first implementation slice does not include result inbox carrier
  convergence.

## RTE-1 Routing Envelope And Mailbox Vocabulary

Status: routing vocabulary implemented; carrier usage remains future work.

Scope:

- Add `RoutingEnvelope` and `RoutingTarget` to a transport-neutral package when
  the first dispatch caller is ready to use them.
- Add a small owner-kind constants class only if it prevents string drift; do
  not introduce an expanding enum of business event types.
- Normalize/validate nonblank `envelopeId`, `target.ownerKind`,
  `target.ownerRef`, and `payload`.
- Define `AdapterMailboxKey` as a stable adapter process/runtime mailbox
  address. It is not public worker API, not `adapterId`, not protocol type, not
  connection/session identity, and not route key.

Acceptance:

- `transport_api` exposes the envelope without depending on
  `transport_runtime`, adapter modules, SDK starter, or engine packages.
- `ownerKind` tests cover at least `adapter` and `engine`, and prove protocol
  labels or `transport-dispatch` are not accepted owner categories.
- Envelope tests prove no `source`, `ingressCode`, adapter/session fields, or
  task-shaped fields are top-level routing fields.
- No caller parses `payload` outside the target-owner bridge in this slice.

## RTE-2 Dispatch Boundary And Worker Delivery Target Resolution

Status: implemented and verified for embedded dispatch through
`WorkerDeliveryTargetView`.

Scope:

- Add or identify a worker-runtime-owned delivery target read surface for
  selected-worker dispatch:

```text
WorkerDeliveryTargetView.resolve(selectedWorkerId)
  -> SelectedWorkerDeliveryTargetEvidence
  workerId
  adapterMailboxKey
  reachabilityState
  generation / observedAt / expiresAt
```

- Keep engine/base assignment output mailbox-free. It carries
  `selectedWorkerId` and opaque payload/correlation context; delivery
  integration resolves `adapterMailboxKey` after assignment.
- Keep endpoint/session facts out of assignment: no route key, endpoint lease
  id, session handle, connection id, endpoint address, adapter-local channel,
  or protocol label.
- Delivery integration emits adapter-targeted dispatch only by consuming the
  selected worker's opaque `adapterMailboxKey` from `WorkerDeliveryTargetView`;
  it does not calculate mailbox ownership.
- Define adapter dispatch payload as the minimal data required for final-hop:
  delivery id, selected worker id, opaque worker payload, result correlation
  ref, and timing.

Acceptance:

- Worker-runtime delivery target view is the only production owner that maps
  selected worker to `adapterMailboxKey`.
- `TaskDispatchBinding` / engine assignment output does not grow
  `adapterMailboxKey` or raw endpoint/session fields.
- `TaskDispatchRoutingSubmitter` or its successor does not call
  endpoint lease lookup, route-owner lookup, adapter registry lookup, or
  mailbox resolver APIs.
- Missing, stale, or expired delivery target evidence is observable as
  scheduling exclusion or delivery outcome/failure evidence; transport does
  not reselect workers.
- Focused worker-runtime proof covers fresh evidence, stale/expired evidence,
  reconnect without mailbox churn, and migration generation change.

## RTE-3 Adapter Mailbox Handoff And Adapter Demux

Status: implemented and verified for delivery-command handoff and polling demux.

Scope:

- Move dispatch handoff queue ownership from bucket-derived
  `deliveryQueueKey` to `adapterMailboxKey`.
- Replace selected-worker consumer evidence with mailbox-level consumer lease:

```text
AdapterMailboxConsumerAvailability
  adapterMailboxKey
  consumerId
  generation
  leaseDeadline
```

- Delete, narrow, or replace `DeliveryCommandConsumerClaim`,
  `DeliveryCommandConsumerRegistry`, and selected-worker consumer indexes so
  they no longer own worker-to-mailbox truth.
- Redis and in-memory handoff implementations use the same mailbox-key model.
- Handoff records must carry delivery outcome facts outside the opaque adapter
  payload, so transport can emit backpressure, unavailable mailbox, no
  consumer, invalid envelope, and destructive-poll corruption without parsing adapter
  payload:

```text
DispatchOutcomeContext
  deliveryId
  selectedWorkerId
  correlationRef
```

- Adapter mailbox consumers parse adapter-targeted payloads and use local
  selected-worker demux. If the selected worker is not local, the adapter
  returns `NO_ENDPOINT`, `STALE_TARGET`, or an equivalent retryable delivery
  outcome.
- Polling is a mailbox consumer too: it buffers accepted adapter dispatch
  payloads locally and poll requests drain only entries whose payload selected
  worker matches the session worker identity.

Acceptance:

- Dispatch queue key/address is adapter mailbox based, not
  `deliveryBucketId` based.
- Mailbox consumer evidence is mailbox-level and does not contain
  `selectedWorkerId`, `deliveryBucketId`, endpoint lease id, session handle, or
  connection id as queue ownership fields.
- Redis ready refs, inflight refs, and consumer availability keys are keyed by
  `adapterMailboxKey` only.
- Selected-worker correctness comes only from the adapter dispatch payload plus
  adapter-local session/pull-buffer demux.
- Endpoint lease publisher no longer projects handoff-private selected-worker
  consumer evidence.
- Transport immediate offer/claim failures build `DispatchOutcome` from
  `DispatchOutcomeContext`, not by decoding adapter payload.
- Adapter final-hop failures build `DispatchOutcome` after adapter-owned
  payload decode, using the same delivery outcome facts.
- Push and polling adapters prove workers sharing one mailbox cannot
  cross-consume selected-worker items.
- The same slice updates `transport/AGENTS.md`,
  `transport/TRANSPORT_BOUNDARY_BASELINE.md`, `doc/PROOF_REGISTRY.md`,
  transport architecture guards, and Redis keyspace guards that currently
  protect bucket-derived queue or per-worker consumer evidence.

## RTE-4 Residue, Guards, And Proof Alignment

Status: implemented and verified for dispatch mailbox slice; keep active for
later routing-envelope/result/external-adapter guard alignment.

Scope:

- Audit `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  `doc/PROOF_REGISTRY.md`, transport architecture guards, and Redis keyspace
  guards after RTE-3 lands.
- Tighten any proof text that still describes bucket-derived queue or
  per-worker consumer evidence as target direction.
- Remove or mark superseded stale active roadmaps that still describe
  `deliveryBucketId -> deliveryQueueKey` as target direction.

Acceptance:

- Owner docs no longer protect `deliveryBucketId` as the dispatch physical
  queue owner after the implementation is present.
- Guard tests fail if starter/producer dispatch performs endpoint/session
  lookup or if handoff reintroduces `deliveryBucketId + selectedWorkerId` /
  `adapterMailboxKey + selectedWorkerId` consumer keys.
- Verification commands are corrected to real tests before completion proof;
  mandatory new tests are created rather than hidden behind
  `-Dsurefire.failIfNoSpecifiedTests=false`.

## RTE-5 Result Routing Alignment

Status: implemented and archived at
`../doc/archive/transport/2026-06-22_TRANSPORT_RESULT_ROUTING_ENVELOPE_CONVERGENCE_ROADMAP.md`.

Scope:

- Result routing is owned by
  `../doc/archive/transport/2026-06-22_TRANSPORT_RESULT_ROUTING_ENVELOPE_CONVERGENCE_ROADMAP.md`.
- Result ingress is aligned to
  `RoutingEnvelope(target = result-ingress:<resultCorrelationRef>)`.
- Keep task-result payload parsing in `TaskResultCallbackCodec` /
  starter-owned result bridge.
- Keep success/failure/result code inside the task-result payload, not on
  `RoutingEnvelope`.
- Keep `TransportResultIngressEnvelope` deleted after production result callers
  have moved to `RoutingEnvelope`.

Acceptance:

- Result inbox proof uses `RoutingEnvelope` when this phase lands.
- Result target owner is `result-ingress`, not `engine`, unless a future
  engine-owned result payload schema is approved separately.
- No adapter module parses task-result payload for retry/finality decisions.
- Result inbox ack/retry semantics still use `TransportResultIngressOutcome`
  or its successor, not boolean success/failure fields inside routing.

## RTE-6 External Adapter Process Follow-Up

Scope:

- Define remote adapter mailbox protocol only after embedded dispatch uses
  adapter mailbox envelopes.
- Add adapter process lifecycle, heartbeat, deployment id, auth, permission,
  and packaging decisions in a separate roadmap.

Acceptance:

- This roadmap does not block on independent process implementation.
- The future remote adapter design can reuse `RoutingEnvelope` and
  `adapterMailboxKey` without changing dispatch target semantics.

## Guard Targets

- `RoutingEnvelope` must not contain `source`, `ingressCode`, task ids,
  message ids, adapter ids, route keys, endpoint addresses, connection ids, or
  session handles as top-level fields.
- Adapter modules must not parse engine-targeted payloads except in temporary
  tests or adapter-owned normalization code.
- Transport queue/handoff code must route by `RoutingTarget`, not by
  diagnostics.
- Producer-side dispatch code must not perform routeKey/connection/session
  lookup or worker-to-mailbox lookup.
- Delivery integration may emit adapter-targeted routing envelopes only after
  resolving `selectedWorkerId -> adapterMailboxKey` through the
  worker-runtime-owned delivery target view. It must not call transport mailbox
  resolver / endpoint lease lookup APIs.
- Handoff consumer evidence must not use `deliveryBucketId + selectedWorkerId`
  or `adapterMailboxKey + selectedWorkerId` as the consumer queue key after
  RTE-3. Mailbox consumer availability is keyed by `adapterMailboxKey`.
- Worker-runtime evidence guards should forbid raw endpoint/session facts from
  becoming assignment fields while requiring a selected-worker delivery target
  read surface when adapter mailbox dispatch is enabled.
- When RTE-5 lands, result bridge code must remain near
  `TaskResultCallbackCodec` / `RuntimeTaskResultIngestChannel`, not inside
  concrete adapter modules.

## Verification Candidates

Verification must distinguish existing smoke coverage from proof classes that
this roadmap must add or update. Completion commands must not mention a test
class until that class exists, and completion proof must not rely on
`-Dsurefire.failIfNoSpecifiedTests=false`.

Mandatory new or updated proof classes before the relevant slice can complete:

- RTE-1: add `RoutingEnvelopeTest` or an equivalent `transport_api` contract
  test proving `RoutingEnvelope` / `RoutingTarget` field allowlists and the
  absence of source, adapter id, route, connection, session, task id, and
  message id top-level fields.
- RTE-2: add `WorkerDeliveryTargetViewTest` or equivalent
  `xa-mass-worker-runtime` proof for fresh, stale, reconnect-stable, and
  migration-generation worker-to-mailbox evidence. This proof must verify that
  engine/base assignment does not grow an adapter mailbox field. Distributed
  runtime projection proof is owned by
  `WORKER_RUNTIME_SELECTED_WORKER_MAILBOX_EVIDENCE_CONVERGENCE_ROADMAP.md`.
- RTE-2/RTE-3: update `TransportAdapterContributionTest` /
  `TransportRuntimeRegistryTest` or add an equivalent proof that
  `adapterMailboxKey` is an explicit embedded adapter contribution/binding
  fact, not a protocol label or route/connection/session-derived value.
- RTE-3: update `InMemoryTransportDeliveryCommandHandoffTest` and
  `RedisTransportDeliveryCommandHandoffTest` or add equivalent mailbox handoff
  contract tests proving mailbox-key queueing, mailbox-level consumer lease,
  no per-worker consumer key, and `DispatchOutcomeContext`-based immediate
  failure outcomes.
- RTE-3: add a polling mailbox demux proof or update current polling adapter
  tests (`PollingDeliveryExecutorTest`, `PollingDeliveryPullChannelTest`,
  `PollingSessionEvidenceDriverTest`) so one mailbox can hold multiple
  selected workers without cross-consumption.

Existing focused tests that currently cover nearby seams and should be kept
green while the roadmap is implemented:

- `InMemoryWorkerPresenceRuntimeTest`
- `SimpleTaskDispatchBinderTest`
- `WorkerRuntimeSelectionIntegrationTest`
- `MassApplicationDistributedTransportTest`
- `TransportConvergenceArchitectureGuardTest`
- `TransportAssignedDeliverySubmitterTest`
- `AdapterMailboxConsumerLoopTest`
- `InMemoryTransportDeliveryCommandHandoffTest`
- `RedisTransportDeliveryCommandHandoffTest`
- `WebSocketTaskDispatchChannelTest`
- `SocketTaskDispatchChannelTest`
- `PollingDeliveryExecutorTest`
- `PollingDeliveryPullChannelTest`
- `PollingSessionEvidenceDriverTest`

Baseline compile smoke before large contract edits:

```bash
./mvnw -q -pl xa-mass-worker-runtime,xa-mass-base,xa-mass-engine,sdk/xa-mass-embedded-sdk,transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter -am -DskipTests test-compile
```

Existing focused proof can be run module-by-module after the compile smoke, or
RTE-0 must replace this section with exact runnable reactor commands once the
new proof classes are created. Avoid a single `-am -Dtest=...` command that
fails in upstream modules with no matching tests.

Later result alignment proof is intentionally separate from the dispatch
mailbox mainline. RTE-5 must create or update result-focused tests before using
this as completion proof:

```bash
./mvnw -q -pl transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile
```

Cross-module compile proof:

```bash
./mvnw -q -pl xa-mass-testing,integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am -DskipTests compile
```

## Completion Criteria

Dispatch mainline is unblocked when:

- Adapter mailbox routing is the assigned-dispatch mainline.
- Engine/base assignment remains mailbox-free; delivery integration resolves
  the selected worker's opaque `adapterMailboxKey` through worker-runtime
  delivery target view without decoding endpoint or session internals.
- `deliveryBucketId` is no longer the dispatch queue/mainline routing address.
- Transport handoff owns mailbox-level consumer availability only; there is no
  handoff-private per-worker consumer key family.
- Handoff failure paths can produce `DispatchOutcome` from
  `DispatchOutcomeContext` without parsing adapter payload.
- `DeliveryCommand` is either deleted or reduced to a transport-owned
  assignment intent. It is not the adapter mailbox queue carrier and does not
  carry adapter mailbox, endpoint, route, connection, or session facts.
- No production dispatch caller routes by routeKey, connection id, session
  handle, endpointAddress, or adapter Java object identity.
- Engine retry/reassign/compensation and task result finality remain
  engine-owned.

Full roadmap completion additionally requires:

- Worker-runtime delivery target evidence is available as shared runtime truth
  or an explicit deployment-injected view for split engine-producer and
  transport-consumer roles; the in-memory embedded projection alone is not
  enough to mark this roadmap complete.
- Dispatch and result ingress both use `RoutingEnvelope` at the transport
  queue/process-boundary layer.
- `TransportResultIngressEnvelope` is deleted or kept out of production
  transport APIs.
- The independent adapter process roadmap can start from the routing envelope
  and adapter mailbox protocol instead of redesigning dispatch/result carriers.
