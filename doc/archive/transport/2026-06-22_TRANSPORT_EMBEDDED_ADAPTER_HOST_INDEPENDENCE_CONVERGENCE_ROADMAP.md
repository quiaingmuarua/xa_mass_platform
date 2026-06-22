# Transport Embedded Adapter Host Independence Convergence Roadmap

Status: complete; archived on 2026-06-22 after mailbox availability,
mailbox-scoped command-drain, concrete adapter capability narrowing, guards,
owner docs, and residue scan landed.

## Summary

This roadmap is the adapter-independence umbrella roadmap for embedded Java
adapters. The goal is not external or cross-language adapter processes yet.
The goal is to make the current embedded adapter path behave like an in-process
adapter host/mount boundary, not an adapter lifecycle supervisor:

```text
MassApplication
  -> assembles EmbeddedAdapterHostSet
  -> starts/stops host set as application resources

Delivery placement / worker-runtime evidence
  -> owns selectedWorkerId -> adapterMailboxKey evidence
  -> owns mailbox placement strategy

Embedded adapter host
  -> mounts adapter contributions
  -> starts/stops host-managed protocol resources with the app
  -> owns adapter mailbox command drain wiring
  -> invokes adapter final-hop executor
  -> wires adapter result ingress normalization
  -> does not supervise adapter health, restart, migration, or placement

Concrete adapter
  -> owns protocol IO
  -> owns local selected-worker session/pull lookup
  -> owns frame/request parsing and writing
  -> does not own scheduling, retry, task-result schema, mailbox placement,
     or mailbox handoff policy
```

The previous lifecycle roadmap
`../doc/archive/transport/2026-06-22_TRANSPORT_EMBEDDED_ADAPTER_RUNTIME_LIFECYCLE_CONVERGENCE_ROADMAP.md`
completed the first slice: mailbox availability claim/refresh/release moved
out of `MassApplication` into embedded host-support classes. EAI-1 then moved
command drain and final-hop execution into mailbox-scoped host mounts. These
are current host-independence proofs, but they must not harden into product
adapter lifecycle policy. This archived roadmap records the completed embedded
adapter host independence convergence while keeping lifecycle, restart,
migration, and mailbox placement policy out of the host.

This is intentionally smaller than an external-adapter roadmap. It does not
define IPC, auth, deployment registration, packaging, or cross-language SDKs.
It establishes the embedded host owner shape those later designs can reuse.

## Completion Evidence

- `TransportDeliveryCommandHandoff` exposes mailbox-scoped command polling;
  production global command pump/listener classes were removed.
- `EmbeddedAdapterHostSet`, `EmbeddedAdapterContributionHost`,
  `AdapterMailboxMount`, and `MailboxConsumerAvailabilityPublisher` own
  embedded host mounting, command drain, and narrow availability proof.
- `TransportAdapterBootstrapContext` exposes host-owned mailbox-key
  resolution, session evidence publisher, and mailbox-scoped pull buffer
  capabilities instead of broad endpoint lease / worker presence / delivery
  service owner getters.
- Concrete polling, WebSocket, and socket bootstraps consume those narrow
  capabilities and do not mint mailbox keys themselves.
- `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`, and
  `doc/PROOF_REGISTRY.md` describe the embedded adapter host/mount current
  owner shape.

## Current Facts

- `EmbeddedAdapterHostSet`, `EmbeddedAdapterContributionHost`,
  `AdapterMailboxMount`, and `MailboxConsumerAvailabilityPublisher` now own
  contribution mounting, mailbox-scoped command drain, and narrow mailbox
  consumer availability claim/refresh/release in the current code. These are
  embedded host-support roles, not adapter supervisor policy.
- `TransportAdapterBootstrapContext` no longer exposes
  `AdapterMailboxConsumerRegistry`, endpoint lease store, worker presence
  ingress, or generic polling delivery service getters to concrete adapter
  bootstraps.
- Concrete adapter bootstraps now consume narrow runtime capabilities:
  `adapterMailboxKey(adapterId)` for host-owned mailbox-key resolution,
  `sessionEvidencePublisher(adapterId, adapterMailboxKey)` for session
  evidence projection, and `pullDeliveryBuffer(adapterMailboxKey)` for polling
  pull-buffer admission/demux. Result ingress and runtime task executor remain
  explicit bootstrap context capabilities because protocol IO owns result-frame
  normalization and local async protocol work.
- `MassApplication` assembles `EmbeddedAdapterHostSet` for `EMBEDDED` and
  `TRANSPORT_CONSUMER` roles; it no longer constructs a production global
  `TransportDeliveryCommandListener` or `TransportDeliveryCommandHandoffPump`.
- `TransportDeliveryCommandHandoff.poll(String adapterMailboxKey, long
  timeoutMillis)` is mailbox-scoped. The production handoff contract no longer
  exposes unscoped `poll(long)`.
- `AdapterMailboxMount` resolves no bindings at dispatch time. It is built from
  a concrete `TransportBinding`, drains only that binding's mailbox, invokes
  `AdapterCommandExecutor`, emits retryable delivery-failure evidence, and
  completes the handoff batch after outcome handling.
- `TransportBinding` is explicit about `adapterMailboxKey`, command executor,
  optional pull channel, and optional pull-session evidence driver.
- Polling adapter is role-separated: command executor enqueues through a
  mailbox-scoped pull buffer, pull channel drains by `selectedWorkerId`, and
  evidence driver projects session evidence through the session evidence
  publisher.
- WebSocket and socket adapters are still heavier. They contain protocol IO,
  session indexes, evidence projection, raw/manual side-channel behavior,
  diagnostics, and selected-worker final-hop send in adjacent classes.
- Raw/manual route send and endpoint diagnostics remain adapter-scoped
  side-channels. They are not assigned-delivery mainline.
- Result ingress has converged to
  `RoutingEnvelope(target=result-ingress:<resultCorrelationRef>)`; adapter code
  should normalize worker result frames into routing envelopes without owning
  task-result retry/finality semantics.

## Owner Review

Embedded adapter host ownership belongs to `transport_runtime`
embedded-support code consumed by SDK/starter assembly. It is not a public SDK
adapter API, not a transport-neutral remote-adapter contract, and not an
adapter supervisor.

`adapterMailboxKey` is not adapter-owned identity. It is a stable delivery
placement target consumed by transport and adapter hosts. The owner of
`selectedWorkerId -> adapterMailboxKey` is delivery placement / worker-runtime
evidence. Current embedded host assembly owns mailbox-key resolution through
`TransportAdapterBootstrapContext`; concrete adapters consume the resolved key
and must not mint mailbox keys or decide which worker belongs to which mailbox.

`MassApplication` owns assembly only:

- resolve transport config
- collect adapter contributions
- build the embedded adapter host set
- start/stop the set
- close global engine/result/handoff resources in the correct order

`MassApplication` must not own:

- mailbox placement or mailbox-key minting strategy
- per-mailbox consumer availability details
- command-drain loops
- adapter binding resolution per batch
- selected-worker final-hop dispatch
- adapter session stores
- raw/manual route resolution

Embedded adapter host owns:

- contribution-level resource mounting and application start/stop wiring
- per-mailbox command drain loop wiring
- invocation of the embedded Java final-hop executor
- delivery outcome/failure emission for the batches it drains
- mailbox consumer availability publication only when the handoff
  implementation requires it; this is not worker lifecycle, adapter lifecycle,
  or mailbox placement policy

Concrete adapters own:

- protocol server/client IO
- frame/request parsing and writing
- local worker session or pull-buffer lookup by selected worker
- endpoint/presence evidence observation
- adapter-local diagnostics and raw/manual side-channels

Transport core owns queue/handoff primitives, dispatch outcomes, result
routing envelopes, and endpoint lease store contracts. It must not own
concrete protocol sessions, channel objects, frame codecs, worker scheduling,
task-result payload schema, retry, reassign, or compensation.

## Boundary Decision

Adapter independence is complete only when assigned delivery flows through a
mailbox-mounted adapter host unit:

```text
adapterMailboxKey
  -> mailbox-scoped handoff drain
  -> embedded adapter host mount
  -> AdapterCommandExecutor.dispatch(List<DeliveryCommand>)
  -> DispatchOutcome / delivery failure evidence
```

The adapter host mount may still be in-process Java. The important boundary is
ownership: a mailbox consumer drains its own mailbox and performs its own
final-hop attempt. A global starter-owned pump that polls every mailbox and
then resolves adapter bindings is an intermediate implementation, not the final
adapter-independence mainline. This does not imply an adapter health supervisor,
restart manager, or migration state machine.

## Lifecycle Discipline

Transport and adapters are transmission owners, not lifecycle owners by
default. The embedded host may start resources when the application starts and
close them when the application closes. It must not infer or control broader
adapter lifecycle states such as unhealthy, draining, migrating, failed over, or
recovered.

Mailbox consumer availability is a queue-safety fact only. It can protect
handoff claim/ack behavior, but it must not become worker lifecycle truth,
adapter lifecycle truth, or placement policy. If future external adapters need
health monitoring, migration, process takeover, or reconciliation, that work
needs a separate owner and roadmap. It should be implemented as a side-channel
observer/control plane, not as logic mixed into command drain, final-hop send,
or concrete adapter session stores.

No transport hot path should gain stats/list/count/watch loops as proof of
correctness. Temporary proof instrumentation is allowed only inside tests or
explicit debug tools and must be removed or moved to a diagnostics owner before
completion.

## Target Shape

Target host shape:

```text
EmbeddedAdapterHostSet
  EmbeddedAdapterContributionHost
    contribution-owned servers / managed resources
    AdapterMailboxMount(binding, adapterMailboxKey)
      optional MailboxConsumerAvailabilityPublisher
      AdapterMailboxCommandDrainLoop
      AdapterCommandExecutor
      TransportDeliveryFailureHandler
```

Target start order:

```text
mount/start host-managed protocol resources
start mailbox command drain loop so it is ready to poll
publish mailbox consumer availability when required by handoff
```

Target stop order:

```text
mark mailbox mount closing so it cannot claim new batches
release mailbox consumer availability when owned by embedded host support
finish or cancel in-flight command drain without claiming more
stop host-managed protocol resources / managed adapters
stop/close handoff and result resources outside adapter host
```

Target assigned delivery flow:

```text
WorkerDeliveryTargetView(selectedWorkerId)
  -> adapterMailboxKey
TransportAssignedDeliverySubmitter
  -> handoff.offer(adapterMailboxKey, commands)
AdapterMailboxMount(adapterMailboxKey)
  -> handoff.poll(adapterMailboxKey, timeout)
  -> AdapterCommandExecutor.dispatch(commands)
  -> handoff.complete(batch, outcomes)
```

Target adapter-local send rule:

```text
selectedWorkerId -> local session / pull-buffer lookup -> protocol send or queue
```

No adapter command path may require `routeKey`, `endpointAddress`,
`connectionId`, `sessionHandle`, `deliveryBucketId`, or Java channel object as
a dispatch input.

## Non-Goals

- Do not implement external adapter IPC in this roadmap.
- Do not introduce a public `AdapterRuntime` SDK API.
- Do not introduce adapter supervisor, restart, failover, migration, or
  health-management policy in this roadmap.
- Do not add built-in transport listeners, watchdogs, reconcilers, or state
  transition controllers for adapter health.
- Do not make concrete adapters own mailbox-key minting or worker-to-mailbox
  placement.
- Do not treat mailbox availability as a lease/heartbeat lifecycle when a
  fixed mailbox binding is enough for the current embedded mode. If the handoff
  needs finite consumer availability evidence, keep it narrow,
  transport-owned, and named as availability rather than adapter lifecycle.
- Do not redesign engine assignment, worker matching, retry, compensation, or
  task result finality.
- Do not move task-result payload decoding into transport runtime or concrete
  adapters.
- Do not add stats/list/count APIs to prove host independence.
- Do not preserve old global pump/listener paths as compatibility aliases after
  callers move.
- Do not rename every adapter/session class as a substitute for owner changes.
- Do not collapse polling, WebSocket, and socket protocol configuration into
  one public options object.

## Do Not Start With

Do not start by adding another wrapper around `TransportDeliveryCommandListener`.
The owner change is that mailbox host mounts drain and dispatch their own
mailbox.

Do not start by splitting every WebSocket/Socket class. First make assigned
delivery host/mount ownership clear; then trim session/raw/diagnostics residue
against that owner boundary.

Do not start by building an external adapter process. The embedded Java host
should first stop depending on starter-owned command drains and Java object
routing as the mainline.

## EAI-0 Current-State Inventory And Proof Alignment

Goal: classify the remaining adapter-independence gaps after EARL.

Scope:

- `MassApplication` command handoff pump construction and shutdown path.
- `TransportDeliveryCommandHandoff`, in-memory and Redis implementations.
- `TransportDeliveryCommandHandoffPump`.
- `TransportDeliveryCommandListener`.
- `EmbeddedAdapterHostSet`, `EmbeddedAdapterContributionHost`,
  `MailboxConsumerAvailabilityPublisher` or renamed host/mount successors.
- `TransportBinding` and `TransportAdapterContribution`.
- `TransportAdapterBootstrapContext` broad runtime inputs and the concrete
  adapter bootstraps that consume them.
- Polling, WebSocket, and socket adapter command executors, session stores,
  evidence drivers, raw/manual channels, endpoint inspectors, and result
  ingress normalization paths.

Acceptance:

- Inventory identifies all production callers of
  `TransportDeliveryCommandHandoff.poll(...)`.
- Inventory separates assigned delivery from raw/manual side-channel and
  endpoint diagnostics.
- Inventory records whether command-drain ownership is global starter-owned,
  contribution-owned, or mailbox-mount-owned.
- Inventory identifies tests that currently prove command delivery but still
  tolerate global pump/listener ownership.
- Inventory confirms no stats/list/count API is required for the mainline.
- Inventory records the vocabulary decision for current runtime/lease class
  names. Target wording is host/mount/availability unless a class truly owns a
  separate runtime boundary.

## EAI-1 Mailbox-Scoped Drain And AdapterMailboxMount Owner Pivot

Goal: move command-drain and final-hop execution ownership from the
starter/global listener into embedded adapter host mounts while changing the
handoff consumer contract in the same compile-safe convergence slice.

This is one owner pivot with three compile-safe checkpoints. The intermediate
checkpoints are not stable architecture and must not be left as long-lived
dual tracks.

Scope:

### EAI-1A Scoped Poll Contract And Vocabulary

- Add a mailbox-scoped consumer operation such as:

```java
DeliveryCommandBatch poll(String adapterMailboxKey, long timeoutMillis);
```

- The unscoped `poll(long)` entry may exist only as a temporary same-slice
  migration helper. It must not remain on the production handoff contract after
  this slice completes.
- Keep `offer(AdapterMailboxDeliveryOffer)` as the producer-facing API.
- Rename or retarget current `runtime/lease` vocabulary that only represents
  embedded host support or mailbox consumer availability:
  - `EmbeddedAdapterHostSet` -> host-set naming, or document the exact reason
    if the name remains during this slice
  - `EmbeddedAdapterContributionHost` -> contribution-host naming, or
    document the exact reason if the name remains during this slice
  - `MailboxConsumerAvailabilityPublisher` -> mailbox availability publisher/controller
    naming, because it must not become adapter lifecycle truth
- Preserve minimal claim/ack consistency:
  - claimed ready refs enter inflight before materialization
  - `complete(batch, outcomes)` acks local handoff state
  - mount or executor failure must not ack
- Update in-memory and Redis handoff implementations to claim only the
  requested mailbox.

### EAI-1B AdapterMailboxMount Drain

- Add an internal mailbox command mount under `transport_runtime`
  embedded-support code.
- Each command-delivery `TransportBinding` gets one mailbox mount that owns:
  - optional mailbox consumer availability publisher, if required by the
    handoff implementation
  - mailbox-scoped command drain loop
  - the binding's `AdapterCommandExecutor`
  - retryable delivery failure emission for materialized batches and executor
    outcomes it produces
- `EMBEDDED` and `TRANSPORT_CONSUMER` are adapter-host command-drain roles.
  `ENGINE_PRODUCER` does not start adapter drains.
- `EmbeddedAdapterContributionHost` or renamed host successor starts mailbox
  command mounts after contribution protocol resources are ready and before
  mailbox consumer availability is published.
- Stop sequencing uses mailbox mount close semantics:
  - mark the mount closing so it cannot claim new batches
  - stop or cancel the mailbox drain loop
  - withdraw/release mailbox consumer availability
  - stop host-managed protocol resources and managed adapters
- `MassApplication` stops constructing `TransportDeliveryCommandListener` and
  `TransportDeliveryCommandHandoffPump` for production adapter-host roles.

### EAI-1C Remove Production Dual Track

- Delete or narrow `TransportDeliveryCommandListener` and
  `TransportDeliveryCommandHandoffPump` after all production callers move.
- Delete the production unscoped `poll(long)` entry after all production callers
  move to mailbox mounts.
- Add guards in this same checkpoint so starter-owned global pump/listener and
  production unscoped handoff polling cannot return.
- Do not introduce worker-level consumer indexes or bucket-derived queue keys.
- Do not add adapter restart, failover, migration, or health-supervision logic
  to the mailbox mount.

Materialization decision:

- Handoff reference corruption, bad refs, or missing command payload before
  materialization is handoff-owned diagnostic/drop.
- The mailbox mount must not fabricate `DispatchOutcome` or engine
  compensation from an incomplete `DeliveryCommandReference`.
- If future recovery needs engine compensation for materialization corruption,
  introduce a wider `PollOutcome` or reference contract in a separate owner
  decision with full command facts.

Acceptance:

- EAI-1A leaves the repo compiling with scoped poll implemented and old
  unscoped poll marked as temporary migration surface only.
- EAI-1B leaves the repo compiling with mailbox mounts draining command
  batches for `EMBEDDED` and `TRANSPORT_CONSUMER`, while old global pump/listener
  may exist only as a still-wired migration path until EAI-1C.
- EAI-1C is the only acceptable completion point for this slice. It removes the
  production dual track and adds the regression guards.
- Production `TransportDeliveryCommandHandoff` no longer exposes unscoped
  `poll(long)`.
- Production source has no unscoped handoff polling caller.
- `MassApplication` has no field/import/reference to
  `TransportDeliveryCommandHandoffPump` or `TransportDeliveryCommandListener`
  for adapter-host command drain.
- A mailbox mount polling `mailbox-a` cannot claim commands from `mailbox-b`.
- Redis and in-memory handoff both expose mailbox-scoped claim semantics.
- Existing producer offer behavior is unchanged.
- `DeliveryCommandBatch` carries one `adapterMailboxKey` and command items
  only.
- The only production command-drain owner is the embedded adapter mailbox
  mount.
- Missing binding, missing command executor, or blank mailbox key are
  construction/startup invariants. A mailbox mount cannot be created without
  a valid `TransportBinding`; mailbox dispatch must not re-resolve bindings
  through `TransportRuntimeRegistry`.
- Failure emission happens exactly once for executor unavailable/rejected and
  adapter final-hop retryable outcomes from materialized batches.
- Handoff materialization corruption is logged/diagnosed by handoff and does
  not produce fake engine compensation.
- Mailbox consumer evidence is named and documented as availability proof, not
  adapter lifecycle, worker lifecycle, health, restart, failover, migration, or
  placement policy.
- Host/support class names do not preserve `Runtime` / `Lease` vocabulary for
  owner concepts that are only host mounting or mailbox availability.
- Stop order prevents new command claims and withdraws mailbox availability
  before protocol resources close.
- Tests prove command drain starts/stops with the embedded adapter host set for
  both `EMBEDDED` and `TRANSPORT_CONSUMER` roles.
- Tests or guards prove mailbox-key strategy is not implemented inside
  concrete adapters or mailbox mounts.
- Focused handoff tests cover same-mailbox claim, wrong-mailbox isolation,
  complete/ack, unacked visibility reclaim, unavailable consumer, and shutdown.

## EAI-2 Contribution Host Shape And Protocol Resource Ownership

Goal: keep contribution host grouping explicit and keep concrete adapter
bootstrap inputs narrow without creating a broad public adapter lifecycle API.

Scope:

- Decide whether current flat `TransportAdapterContribution` is sufficient for
  contribution-level shared resources plus per-binding mailbox mounts.
- If flat contribution output remains, add tests that prove shared servers and
  managed adapters are started/stopped once per contribution, not once per
  binding.
- If grouping is insufficient, introduce an internal contribution grouping
  model that is output-only and not a mutable bootstrap context or lifecycle
  supervisor.
- Maintain narrow `TransportAdapterBootstrapContext` role-specific
  capabilities where concrete adapters need runtime input:
  - result ingress channel for protocol-level result-frame normalization, not
    task-result schema ownership
  - host-owned adapter mailbox key resolution, not concrete adapter mailbox
    minting
  - session evidence publisher, not raw endpoint lease store or worker
    presence ingress mutation surfaces
  - mailbox-scoped polling pull buffer, not generic `TransportDeliveryService`
  - runtime task executor only where protocol resources need local async work
- Keep raw/manual channels and endpoint inspectors as side-channel
  contribution facts; do not let them participate in assigned delivery.

Acceptance:

- Contribution-owned protocol resources are not duplicated per binding.
- Adapter mailbox mounts are created only for command-delivery bindings.
- `TransportAdapterContribution` remains output-only; it does not gain runtime
  inputs, registries, lifecycle callbacks, or mutable setter hooks.
- No concrete adapter implementation receives `AdapterMailboxConsumerRegistry`
  or handoff internals directly.
- No concrete adapter bootstrap receives broad transport owner surfaces when a
  narrower capability exists. `TransportAdapterBootstrapContext` must not
  expose `getEndpointLeaseStore()`, `getWorkerPresenceIngress()`, or
  `getDeliveryService()` style getters.
- Concrete adapter bootstraps resolve mailbox keys through
  `TransportAdapterBootstrapContext`, not by assigning `adapterId` to
  `adapterMailboxKey` themselves.

## EAI-3 Concrete Adapter Mainline Cleanup

Goal: align polling, WebSocket, and socket internals to the same owner shape
without erasing protocol differences.

Scope:

- Polling:
  - keep command executor as queue admission owner
  - keep pull channel as selected-worker demux owner
  - keep evidence driver as session evidence projection owner
  - ensure none of these owns mailbox placement, availability publication, or
    command drain loop
- WebSocket:
  - session store owns worker-id to channel lookup
  - command executor sends by selected worker id only
  - server/session controller owns protocol session open/close only
  - raw/manual route send remains a separate side-channel
  - endpoint diagnostics stay side-channel and cannot drive delivery
- Socket:
  - converge toward the same selected-worker final-hop rule as WebSocket
  - keep route-only send as raw/manual side-channel only
  - separate evidence projection from assigned final-hop send where practical

Acceptance:

- Push adapter command executors do not accept adapter id, route key,
  endpoint address, connection id, session handle, or delivery bucket as send
  parameters.
- Assigned delivery uses only `selectedWorkerId` plus opaque payload.
- Raw/manual route send cannot be called from assigned delivery command path.
- Endpoint inspector classes are read-only diagnostics and are not used by
  command execution.
- WebSocket/socket tests prove worker-id-only final-hop delivery and no
  route-only fallback for assigned task dispatch.

## EAI-4 Adapter Result Ingress Binding

Goal: keep worker-to-platform ingress transparent and business-free while
making adapter host ownership explicit.

Scope:

- Adapter protocol code may recognize protocol-level result frames or pull
  result submissions.
- Adapter code normalizes worker result data into
  `RoutingEnvelope(target=result-ingress:<resultCorrelationRef>)`.
- Transport runtime buffers/relays routing envelopes.
- Starter-owned result bridge decodes task-result payload and applies engine
  result convergence.
- Adapter host support may own the subscription/wiring to result ingress channel,
  but it must not parse `success`, `resultCode`, retryability, or finality.

Acceptance:

- Concrete adapters do not decide retry/finality from result payload.
- `TaskResultCallbackCodec` or its starter-owned successor remains the task
  result payload decoder.
- Result ingress wiring is part of adapter host contribution assembly, not a
  hidden global callback on session managers.
- Existing result routing proof remains green.

## EAI-5 Guards, Docs, And Residue Removal

Goal: prevent shallow completion and old global pump ownership from returning.

Scope:

- Update `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  and `doc/PROOF_REGISTRY.md` after EAI-1/EAI-3 land.
- Add architecture guards forbidding:
  - `MassApplication` references to `TransportDeliveryCommandHandoffPump`
  - `MassApplication` references to `TransportDeliveryCommandListener`
  - production calls to unscoped `TransportDeliveryCommandHandoff.poll(long)`
  - production exposure of unscoped `poll(long)` on the handoff contract after
    mailbox-scoped drain lands
  - concrete adapters importing handoff internals or mailbox registries
  - concrete adapter bootstraps receiving broad transport owner surfaces after
    narrow capabilities exist
  - command executors accepting route/endpoint/session ids as dispatch input
  - raw/manual route channels in assigned delivery path
  - command-drain or final-hop classes owning adapter health state machines,
    monitor loops, or stats/list/count APIs
  - host/support classes using `Runtime` or `Lease` vocabulary for concepts
    that are only host mounting or mailbox availability
- Delete old global pump/listener code once production callers are gone, or
  mark it test-only if a focused test needs a local helper.
- Archive this roadmap only after residue scan proves active docs and guards
  describe mailbox-mount command ownership as current truth.

Acceptance:

- Active docs say embedded adapter host owns mailbox mount command
  drain/final-hop execution, while mailbox placement and worker delivery target
  evidence stay outside concrete adapters.
- No active roadmap describes EARL as adapter-independence completion.
- Guard tests fail if command-drain ownership moves back into starter assembly.
- Completion proof does not rely on missing tests hidden by
  `-Dsurefire.failIfNoSpecifiedTests=false`.

## Suggested Implementation Order

1. EAI-0: inventory and proof alignment.
2. EAI-1A: add mailbox-scoped handoff claim/poll contract and settle
   host/mount/availability vocabulary.
3. EAI-1B: move command drain/final-hop execution into mailbox mounts for
   `EMBEDDED` and `TRANSPORT_CONSUMER`.
4. EAI-1C: delete production unscoped poll/global pump/listener and add guards.
5. EAI-2: tighten contribution host grouping if the EAI-1 implementation
   exposes ambiguity.
6. EAI-3: clean WebSocket/socket/polling adapter internals against the new
   owner shape.
7. EAI-4: align adapter result ingress wiring if still globally coupled.
8. EAI-5: delete residue, update docs/guards, and archive.

## Verification Candidates

Baseline compile before the first implementation slice:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile
```

Expected focused host/handoff proof after EAI-1C:

```bash
./mvnw -q -pl transport/transport_runtime test "-Dtest=MailboxConsumerAvailabilityPublisherTest,EmbeddedAdapterHostSetTest,AdapterMailboxMountTest,TransportConvergenceArchitectureGuardTest,InMemoryTransportDeliveryCommandHandoffTest,RedisTransportDeliveryCommandHandoffTest,TransportAdapterContributionTest,TransportRuntimeRegistryTest,TransportRegistrationResolverTest"
```

`MailboxConsumerAvailabilityPublisherTest`,
`AdapterMailboxMountTest`, and `EmbeddedAdapterHostSetTest` are mandatory EAI-1
proof classes. Do not keep old runtime/lease-named tests as the final proof if
they preserve supervisor, lease, or lifecycle-owner vocabulary.

Expected embedded SDK assembly proof:

```bash
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test "-Dtest=MassApplicationDistributedTransportTest,MassApplicationStopOrderTest,MassSdkTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected adapter proof:

```bash
./mvnw -q -pl transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter -am test "-Dtest=PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,PollingSessionEvidenceDriverTest,WebSocketTaskDispatchChannelTest,WebSocketSessionControllerTest,SocketTaskDispatchChannelTest,SocketSessionManagerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Cross-module compile proof:

```bash
./mvnw -q -pl xa-mass-testing,integrations/xa-mass-scenario-launcher,integrations/xa-mass-worker-pack -am -DskipTests compile
```

Post-EAI-1 completion proof must use mailbox-mount command drain tests. The old
listener/pump classes and `TransportDeliveryCommandListenerTest` must not be
used as final completion proof.

## Completion Criteria

Adapter independence mainline is complete when:

- `MassApplication` assembles and starts/stops the embedded adapter host set
  only; it does not own command pump/listener construction.
- Each command-delivery binding has a mailbox mount that owns command drain,
  final-hop executor invocation, completion ack, and retryable delivery failure
  emission.
- Mailbox consumer availability evidence, if required by the handoff
  implementation, is narrow availability proof only. It is not documented or
  implemented as worker lifecycle, adapter lifecycle, restart, failover,
  migration, or mailbox placement policy.
- Handoff consumers poll by `adapterMailboxKey`; no mailbox mount can claim
  another mailbox's commands.
- `TransportDeliveryCommandHandoff` production API no longer exposes unscoped
  `poll(long)`, and production source contains no unscoped handoff polling
  caller.
- Concrete adapters own protocol IO and local selected-worker lookup only.
- Concrete adapters do not mint mailbox keys and do not own
  `selectedWorkerId -> adapterMailboxKey` placement.
- Concrete adapter bootstraps receive narrow capabilities rather than broad
  transport runtime owner surfaces once those capabilities exist.
- Concrete adapter bootstraps consume host-resolved `adapterMailboxKey`; they
  do not mint mailbox keys from adapter id or protocol values themselves.
- Assigned delivery command execution does not use route key, endpoint address,
  connection id, session handle, delivery bucket, adapter node, or Java channel
  object as dispatch input.
- Raw/manual route send and endpoint diagnostics remain side-channels and are
  not reachable from assigned task delivery.
- Result ingress remains routing-envelope based and task-result decoding stays
  above transport.
- Current owner docs and proof registry describe embedded adapter host/mount as
  the command-drain owner, not just the mailbox-availability owner.
- Old global pump/listener code is removed from production or no longer wired
  by production assembly.

Only after all criteria pass should this roadmap be marked complete and moved
to `doc/archive/transport/`.
