# Transport Adapter Command Executor Convergence Roadmap

Status: mainline complete and superseded for push final-hop/session details by
`TRANSPORT_PUSH_ADAPTER_FINAL_HOP_BOUNDARY_CONVERGENCE_ROADMAP.md`.
Retained only as executor-boundary historical context until archive. Do not
use lower endpoint-registry or verification references as current contracts.

Polling adapter internal role splitting after this mainline has landed and was
archived with the polling capability roadmap. Remaining WebSocket/socket
session-manager decomposition is tracked by
`TRANSPORT_PUSH_ADAPTER_SESSION_CAPABILITY_CONVERGENCE_ROADMAP.md`. This
roadmap keeps the executor boundary context; it no longer owns concrete adapter
internal capability decomposition.

## Summary

The outer transport dispatch boundary is now much cleaner than the concrete
adapter implementations. `DeliveryCommand` is the current assigned-delivery
input, `AdapterCommandExecutor` is the local final-hop execution seam, and
`TransportAdapterContribution` separates adapter bootstrap outputs from runtime
inputs. The remaining problem is lower in the adapter modules: WebSocket,
Socket, and Polling adapters still mix command execution, session indexing,
route/raw addressing, endpoint lease publication, consumer claim projection,
diagnostics, and bootstrap wiring in large objects.

This roadmap makes adapter command execution the next convergence target:

```text
DeliveryCommand -> adapter-local final-hop attempt -> DispatchOutcome
```

The adapter may own protocol implementation details, but assigned delivery must
not depend on route/raw endpoint addressing, `TransportPacket`, connection
metadata, or adapter-local route identity.

This roadmap is a prerequisite for the route-key-removal roadmap. Route-key
removal should become a smaller residue cleanup after concrete adapters no
longer expose route/raw/session concepts through the assigned-delivery path.

## Current Code Observations

- `AdapterDispatchRequest` is no longer present in main transport sources.
  `DeliveryCommand` is the current assigned-worker delivery intent with
  `deliveryBucketId`, `selectedWorkerId`, opaque payload, and correlation
  reference.
- `AdapterCommandExecutor.dispatch(List<DeliveryCommand>)` is the current local
  dispatch seam. It only owns command execution. `WorkerAdapter` no longer
  exists in the assigned-delivery production mainline.
- `TransportBinding` now owns adapter id, transport hint, protocol label,
  command executor, and optional pull channel explicitly. It does not derive
  adapter metadata from the executor.
- `TransportDeliveryCommandListener` resolves the current endpoint lease by
  `deliveryBucketId + selectedWorkerId`, reads the endpoint driver id, resolves
  the local `TransportBinding`, groups commands by canonical adapter binding,
  and calls the binding's `AdapterCommandExecutor.dispatch(...)`.
- `WebSocketTaskDispatchChannel` sends by `selectedWorkerId` by looking up the
  active worker session in `WebSocketSessionStore`, writing the WebSocket frame
  directly, and returning `DispatchOutcome`. It no longer depends on
  `WorkerEndpointRegistry`, raw/result dispatcher context state, a
  command-context wrapper, or adapter metadata methods.
- `WebSocketDispatcherContext` is now raw/result input-processing context only.
  It carries raw route side-channel access, frame codec, and result ingress, not
  the assigned-delivery selected-worker endpoint registry.
- `ServerSessionManager` has been removed from the WebSocket adapter.
  `WebSocketSessionStore` owns selected-worker session indexing and state;
  `WebSocketSessionController` owns server/session orchestration only and
  implements `WebSocketServerSessionHandle`. Endpoint lease / selected-worker
  consumer evidence writes are owned by `WebSocketSessionEvidenceDriver`;
  diagnostics are exposed through `WebSocketEndpointInspector`; raw/manual
  route send is isolated in `WebSocketRawWorkerRouteEndpointRegistry`.
- `WebSocketTransportAdapterBootstrap` contributes an explicit command binding,
  optional server, optional raw/manual channel, and diagnostics inspector through
  `TransportAdapterContribution`.
- `TransportAdapterBootstrapContext` is now a runtime input bag only.
  `TransportAdapterBootstrap.contribute(...)` returns
  `TransportAdapterContribution`, an explicit append-only output model for
  bindings, managed adapters, servers, raw/manual channels, and diagnostics.
- `CompositeWorkerEndpointRegistry` is selected-worker endpoint registry
  aggregation only. Diagnostics aggregation is owned by
  `CompositeWorkerEndpointInspector`; raw/manual route channels are explicit
  adapter contributions.
- Socket mirrors the WebSocket split with `SocketCommandDispatchContext`,
  `SocketRawWorkerRouteEndpointRegistry`, `SocketEndpointInspector`, and the
  shared endpoint/presence publishers.
- Polling remains pull-based, but it now reuses
  `TransportEndpointLeasePublisher` for endpoint lease and selected-worker
  consumer evidence instead of owning separate endpoint lease record builders.

## Owner Review

- Engine and scheduling plane own worker selection. By the time transport sees a
  command, `selectedWorkerId` is a delivery constraint, not a scheduling input.
- Transport runtime owns delivery queueing, endpoint evidence lookup, local
  adapter selection, dispatch outcome/failure evidence, and pump/handoff
  consistency.
- A concrete adapter owns protocol server/client mechanics and local session
  handles. It may publish endpoint/session evidence, but it does not own worker
  lifecycle truth.
- Worker runtime owns worker lifecycle and eligibility interpretation. Adapter
  session evidence may be an ingress signal, not the lifecycle model itself.
- Raw/manual endpoint messaging, if retained, is an operator side-channel. It is
  not the assigned-delivery command path and must not share command executor
  abstractions.

## Boundary Decision

Adapter command execution is a first-class local executor boundary.

Target contract:

```text
DeliveryCommand batch
  -> AdapterCommandExecutor
  -> selected-worker local send attempt
  -> DispatchOutcome batch
```

Allowed command facts:

- `commandId`
- `deliveryBucketId`
- `selectedWorkerId`
- opaque payload
- correlation reference
- delivery observation timestamps

Forbidden assigned-delivery input facts:

- `routeKey`
- raw endpoint address
- `TransportPacket`
- channel/session handle
- connection id
- transport node id
- endpoint lease record
- adapter-local route identity

Adapter identity metadata may remain on the binding/descriptor side for local
adapter resolution, diagnostics, and future adapter strategy, but it must not be
part of command correctness or queue selection.

`AdapterCommandExecutor` is the target assigned-delivery command executor
contract. It owns only:

```java
List<DispatchOutcome> dispatch(List<DeliveryCommand> commands)
```

`WorkerAdapter` must not remain as the assigned-delivery mainline once ACE-1
lands, because it combines command execution with adapter metadata. The ACE-1
slice may rename `WorkerAdapter` in place or add `AdapterCommandExecutor` and
migrate callers, but it must leave only one production command-executor
contract. Do not keep a deprecated alias, adapter wrapper, or compatibility
dual track.

## Target Adapter Shape

Each concrete adapter should be decomposed into explicit local roles:

```text
Transport runtime
  -> TransportBinding
  -> AdapterCommandExecutor
  -> session store
  -> protocol final-hop send
```

Target roles:

- **adapter descriptor / binding metadata**: adapter id, transport hint,
  protocol label, optional capability metadata, and the local command executor.
  `TransportBinding` owns these facts explicitly; it must not derive them from
  the executor.
- **command executor**: accepts `DeliveryCommand` only, resolves the local
  selected-worker session, sends the protocol frame, and returns
  `DispatchOutcome`.
- **session store**: owns worker/session handle/channel indexing and replacement
  rules. It does not publish lifecycle events by itself.
- **endpoint lease publisher**: translates session connect/heartbeat/disconnect
  into endpoint lease evidence and delivery consumer evidence.
- **presence ingress publisher**: emits session evidence to worker runtime when
  the chosen contract allows it.
- **diagnostics/inspector**: bounded read-only snapshots. It must not drive
  dispatch, lifecycle, or queue selection.
- **raw/manual side-channel**: either deleted, reworked as selected-worker
  worker-command final hop, or retained as an explicit operator-only raw endpoint
  channel outside assigned delivery.
- **adapter contribution**: explicit bootstrap output containing zero or more
  bindings, servers, managed adapters, raw/manual channels, and diagnostics
  contributions. Runtime inputs and adapter outputs should not share a mutable
  single-slot context.

The same concrete class should not implement all of these roles. Splitting must
create owner boundaries, not forwarding wrappers.

## Relationship To Existing Roadmaps

- `TRANSPORT_ROUTE_KEY_REMOVAL_CONVERGENCE_ROADMAP.md` should depend on this
  roadmap. Removing routeKey before adapter role separation risks hiding the
  same route/address concept under a new name.
- `TRANSPORT_NODE_ID_REMOVAL_CONVERGENCE_ROADMAP.md` remains a separate internal
  id roadmap. This roadmap may reduce adapter/node id exposure, but it should
  not absorb node-id removal as a completion requirement.
- `TRANSPORT_BUCKET_WORKER_DELIVERY_QUEUE_KEY_CONVERGENCE_ROADMAP.md` owns queue
  addressing. This roadmap must not reintroduce adapter id, route key, or
  selected-worker sub-lane concepts into queue selection.
- `TRANSPORT_RESULT_INGRESS_CONVERGENCE_ROADMAP.md` owns result ingest. Adapter
  command executor cleanup should keep result ingress wiring intact but should
  not redefine result envelopes.

## Non-Goals

- Do not change worker selection, retry, reassignment, compensation, or task
  attempt timeout semantics.
- Do not change delivery bucket or queue-key derivation.
- Do not redesign result ingest.
- Do not remove routeKey globally in this roadmap; remove only route/raw usage
  that directly blocks adapter command executor separation.
- Do not introduce remote dynamic adapter registration. Current adapter runtime
  registration is local JVM composition.
- Do not create pass-through wrappers that leave the all-in-one adapter manager
  as the real owner.
- Do not retain `WorkerAdapter` as a compatibility facade after all in-repo
  command-executor callers have moved.

## Do Not Start With

- Do not begin by renaming `routeKey` or `ServerSessionManager`. Rename only
  after the owner split is real.
- Do not add a new facade that simply forwards all old
  `WorkerEndpointRegistry`, `RawWorkerRouteEndpointRegistry`, and
  `WorkerEndpointInspector` calls to the same object.
- Do not delete raw/manual send before classifying whether each caller is
  assigned delivery, worker command, diagnostic, or operator side-channel.
- Do not make adapter id part of command correctness to compensate for removing
  routeKey.

## ACE-0 Inventory And Guard Baseline

Goal: classify the current adapter roles before changing behavior.

Scope:

- Inventory WebSocket, Socket, and Polling adapter classes that currently own:
  command execution, session indexing, endpoint lease evidence, worker presence
  ingress, delivery consumer claim, raw/manual send, diagnostics, server
  bootstrap, and protocol frame encoding.
- Record all production callers of `WorkerEndpointRegistry`,
  `RawWorkerRouteEndpointRegistry`, `WorkerEndpointInspector`,
  `RawWorkerMessageChannel`, `TransportAdapterBootstrapContext`, and
  `TransportBinding`.
- Classify every raw/manual send caller as assigned delivery, worker command,
  operator raw endpoint side-channel, test fixture, or stale residue. This
  classification is a prerequisite for ACE-2/ACE-3, not a late cleanup item.
- Inventory `WebSocketDispatcherContext`, socket dispatcher context, and
  `CompositeWorkerEndpointRegistry` role mixing alongside concrete session
  managers.
- Verify `AdapterDispatchRequest` remains absent from production code.
- Add or update architecture guards for the current command contract:
  assigned-delivery adapter execution accepts `DeliveryCommand`, not route/raw
  endpoint packets.

Acceptance:

- A focused inventory exists or this roadmap gains an accurate table covering
  WebSocket, Socket, and Polling adapter roles.
- Production source has no `AdapterDispatchRequest`.
- Guard coverage distinguishes command execution from raw/manual endpoint send.
- Raw/manual v1 disposition is recorded before WebSocket/Socket splitting:
  delete, selected-worker worker-command final hop, true operator side-channel,
  test fixture, or stale residue.
- The inventory identifies which current roles are owned by command executor,
  session store, endpoint lease publisher, presence ingress publisher,
  diagnostics, raw/manual side-channel, bootstrap inputs, and bootstrap outputs.
- No behavior change is required in this slice.

## ACE-1 Adapter Command Executor And Contribution Contract

Goal: lock the local adapter command executor as the only assigned-dispatch
entry point and make adapter bootstrap output explicit.

Scope:

- Introduce `AdapterCommandExecutor` or rename `WorkerAdapter` in place so that
  the command executor contract contains only
  `dispatch(List<DeliveryCommand>)`.
- Remove `WorkerAdapter` from the assigned-delivery production mainline in the
  same slice. `TransportBinding`, `TransportRuntimeRegistry`,
  `TransportDeliveryCommandListener`, WebSocket, Socket, Polling, and tests must
  use the single command-executor contract.
- Make `TransportBinding` the explicit owner of adapter id, transport hint,
  protocol label, command executor, and optional pull channel. Descriptor
  metadata must match the contributed binding. Metadata must not be read back
  from the executor.
- Ensure `TransportDeliveryCommandListener` only calls the command executor with
  `List<DeliveryCommand>`.
- Keep endpoint lease lookup in transport runtime before adapter dispatch.
  Adapter command executors should not receive endpoint lease records as command
  input.
- Introduce an explicit `TransportAdapterContribution` output model, or rename
  the existing context so that runtime inputs and adapter outputs are separate:

  ```text
  TransportAdapterRuntimeInputs
    -> TransportAdapterBootstrap.contribute(...)
    -> TransportAdapterContribution
  ```

  The contribution must support explicit lists for transport bindings, managed
  adapters, servers, raw/manual channels, and diagnostics contributions. It must
  define duplicate, zero, one, and multiple contribution behavior.
- Record the runtime aggregation residue explicitly. Splitting selected-worker
  endpoint registry, raw route side-channel, and diagnostics inspector
  composites belongs to ACE-5 unless required for the command-executor contract.
- Apply the ACE-0 raw/manual v1 decision to bootstrap output. Current v1 keeps
  `RawWorkerMessageChannel` only as an explicit side-channel contribution
  outside assigned delivery; it is not part of the command executor.
- Add tests that fail if descriptor adapter id / transport hint drifts from the
  contributed binding.

Acceptance:

- Assigned delivery has a single adapter execution call shape:
  `List<DeliveryCommand> -> List<DispatchOutcome>`.
- Production assigned-delivery code no longer imports or depends on
  `WorkerAdapter`; if the name survives, it has been narrowed to the
  `AdapterCommandExecutor` contract and no longer owns adapter metadata.
- `TransportBinding` stores adapter metadata explicitly and does not derive
  adapter id or transport hint from the executor.
- Command executor input does not contain route/raw/session/lease/packet facts.
- Bootstrap contribution output is explicit and append-only. It cannot silently
  overwrite multiple bindings, servers, raw/manual channels, managed adapters,
  or diagnostics contributions.
- Descriptor metadata is validated against every contributed binding.
- `CompositeWorkerEndpointRegistry` remains explicitly recorded as ACE-5
  residue if it still owns selected endpoint, raw route, and diagnostics
  aggregation in one object after this slice.
- Raw/manual channel retention is explicitly outside assigned delivery. Deletion
  or selected-worker rewrite remains an ACE-5 decision with caller inventory and
  tests.
- `TransportDeliveryCommandListenerTest` covers adapter-unavailable and
  command-executor failure outcomes through the command executor seam.

## ACE-2 WebSocket Adapter Internal Split

Goal: split WebSocket adapter internals without changing the external worker
wire behavior.

Scope:

- Extract a WebSocket session store that owns:
  `selectedWorkerId/sessionHandle/channel/context` indexing, replacement, active
  count, and shutdown close semantics.
- Extract a WebSocket command executor that owns:
  `DeliveryCommand -> encode worker frame -> send to selected worker`.
- Keep `WebSocketDispatcherContext` as raw/result input-processing context only.
  The command executor should receive adapter id, the narrow selected-worker
  endpoint registry, and delivery service directly; do not reintroduce a
  WebSocket command-context wrapper.
- Extract an endpoint lease / consumer evidence publisher that owns:
  session connect, heartbeat, disconnect, replacement, and shutdown evidence
  publication.
- Keep worker presence ingress publication explicit and separate from endpoint
  lease publication.
- Move raw route send behind a separate optional raw/manual channel, or mark it
  for deletion if no valid operator side-channel remains.
- Keep diagnostics as read-only snapshots over the session store.
- Update `WebSocketTransportAdapterBootstrap` to contribute explicit adapter
  binding, optional server, optional raw/manual channel, and optional
  diagnostics through `TransportAdapterContribution` instead of passing one
  session manager into every role.

Acceptance:

- The WebSocket assigned-delivery path does not call
  `sendToAdapterRoute(...)`.
- The WebSocket command executor depends on selected-worker session lookup, not
  raw route lookup.
- WebSocket assigned-delivery code does not depend on a context that also owns
  raw route registry or result ingress.
- No WebSocket mainline class implements `WorkerEndpointRegistry`,
  `RawWorkerRouteEndpointRegistry`, and `WorkerEndpointInspector` together.
- Session connect/heartbeat/disconnect still publish endpoint lease evidence
  and delivery consumer evidence.
- Existing WebSocket dispatch, server handshake, replacement, shutdown, and
  frame-codec tests pass or are updated to the new role names.

## ACE-3 Socket Adapter Internal Split

Goal: apply the same role split to the Socket adapter.

Scope:

- Mirror the WebSocket split: socket session store, socket command executor,
  socket command context, endpoint lease publisher, presence publisher,
  diagnostics, and optional raw side-channel.
- Ensure Socket command execution sends by selected worker and does not require
  route/raw endpoint addressing.
- Keep protocol frame compatibility unless a separate worker-wire roadmap
  explicitly changes it.

Acceptance:

- Socket assigned delivery accepts only `DeliveryCommand`.
- Socket command execution does not use route/raw endpoint lookup.
- Socket assigned-delivery code does not depend on a context or session manager
  that also owns raw route registry or diagnostics.
- Endpoint lease and consumer evidence publication remains covered by tests.
- Socket server and frame-codec tests pass after role split.

## ACE-4 Polling Adapter Alignment

Status: mainline landed.

Goal: align the pull-based adapter with the same owner vocabulary.

Scope:

- Keep polling final-hop semantics pull-based, but classify roles consistently:
  pull buffer/store, endpoint lease publisher, delivery consumer evidence,
  presence ingress, diagnostics, and public worker pull API projection.
- Ensure polling command acceptance remains bucket-queue plus entry-level
  `selectedWorkerId` demux, not adapter/route/node lookup.
- Ensure polling adapter internals do not reintroduce protocol label as command
  correctness or queue identity.

Acceptance:

- Polling adapter command storage remains selected-worker safe.
- Polling worker pull cannot cross-consume commands for another selected worker.
- Polling adapter tests prove endpoint evidence and pull projection without
  relying on route/raw endpoint facts.

## ACE-5 Raw/Manual And Diagnostics Residue Cleanup

Status: mainline diagnostics split landed; retained raw/manual channel remains an
explicit operator side-channel pending route-key-removal disposition.

Goal: finish the raw/manual and diagnostics decisions made in ACE-0/ACE-1 and
remove residue from assigned delivery.

Scope:

- Delete stale raw route send after ACE-0/ACE-1 classification proves it has no
  production owner.
- If worker command send remains, ensure it is selected-worker final hop, not
  route/raw endpoint addressing.
- If operator raw endpoint send remains, keep it adapter-local and explicitly
  outside `MassApplication` assigned-delivery mainline.
- Move diagnostics to single-purpose read-only contribution/composite paths.
- Remove tests that preserve old raw route or all-in-one registry vocabulary as
  hidden compatibility contracts.

Acceptance:

- Assigned delivery never calls raw/manual send.
- Any retained raw/manual channel has an owner, caller list, and tests.
- Diagnostics do not drive lifecycle, queue selection, or command routing.
- No production path depends on `CompositeWorkerEndpointRegistry` as a combined
  selected endpoint, raw route, and inspector owner.

## ACE-6 Residue, Docs, And RouteKey Unblock

Goal: make routeKey removal executable as a cleanup roadmap instead of an
adapter-internal rewrite.

Scope:

- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md` with the new adapter role
  split once implemented.
- Update `doc/PROOF_REGISTRY.md` guards/proofs for adapter command executor
  boundaries.
- Mark routeKey removal roadmap as dependent on ACE completion for adapter
  internals.
- Add residue scans for:
  `AdapterDispatchRequest`, assigned-delivery `sendToAdapterRoute`,
  command-path `routeKey`, command-path `TransportPacket`, and all-in-one
  adapter manager implementations.

Acceptance:

- Adapter command path is free of route/raw endpoint facts.
- Remaining routeKey usage is either worker wire compatibility, endpoint lease
  diagnostic metadata, raw/manual side-channel, or explicitly queued for the
  route-key-removal roadmap.
- Route-key-removal roadmap can start from inventory cleanup rather than
  untangling adapter execution ownership.

## Suggested Implementation Order

1. ACE-0 inventory and guards.
2. ACE-1 command-executor and contribution contract.
3. ACE-2 WebSocket split.
4. ACE-3 Socket split.
5. ACE-4 Polling alignment.
6. ACE-5 raw/manual and diagnostics residue cleanup.
7. ACE-6 docs, proof registry, and routeKey roadmap unblock.

ACE-2 and ACE-3 may share helper patterns, but they should land as separate
compile-safe slices. Polling should not be used to justify WebSocket/Socket
raw route residue, because polling has a different final-hop model.

## Verification Candidates

Compile-level proof:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile
```

Focused transport runtime proof:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime -am test -Dtest=TransportConvergenceArchitectureGuardTest,TransportDeliveryCommandListenerTest,TransportAssignedDeliverySubmitterTest,TransportRuntimeRegistryTest,TransportRegistrationResolverTest,CompositeWorkerEndpointRegistryTest,TransportAdapterContributionTest -Dsurefire.failIfNoSpecifiedTests=false
```

`TransportAdapterContributionTest` is an ACE-1 mandatory proof. If the chosen
implementation uses a different class name, ACE-1 must update this command and
add the equivalent direct contribution contract test in the same slice.
`-Dsurefire.failIfNoSpecifiedTests=false` is only to let upstream reactor
modules without matching focused tests pass; the mandatory test class must
exist and be listed here.

WebSocket proof:

```bash
./mvnw -q -pl transport/websocket-adapter -am test -Dtest=WebSocketTaskDispatchChannelTest,WebSocketInputProcessorTest,WebSocketOutputProcessorTest,DispatcherInboundHandlerTest,WebSocketSessionControllerTest,WebSocketFrameReadersTest -Dsurefire.failIfNoSpecifiedTests=false
```

Socket proof:

```bash
./mvnw -q -pl transport/socket-adapter -am test -Dtest=SocketTaskDispatchChannelTest,SocketSessionManagerTest,SocketTransportServerTest,SocketTransportFrameCodecTest -Dsurefire.failIfNoSpecifiedTests=false
```

Polling and SDK proof:

```bash
./mvnw -q -pl transport/polling-adapter,sdk/xa-mass-embedded-sdk -am test -Dtest=PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,PollingSessionEvidenceDriverTest,EmbeddedPullWorkerSessionTest,MassApplicationDistributedTransportTest -Dsurefire.failIfNoSpecifiedTests=false
```

Server/public worker smoke proof when worker wire or registration behavior is
touched:

```bash
./mvnw -q -pl xa-mass-server -am test -Dtest=ExternalWorkerRealtimeRegistrationIntegrationTest,ExternalWorkerPollingApiIntegrationTest
```

The exact focused test list may need correction during ACE-0 if class names have
changed. When reactor commands use `-Dsurefire.failIfNoSpecifiedTests=false`,
mandatory proof classes must still exist, be listed explicitly, and be covered
by architecture guards or direct source checks where useful.

## Guard Candidates

- Production code must not contain `AdapterDispatchRequest`.
- Production assigned-delivery code must not depend on a `WorkerAdapter`
  contract that combines `dispatch` with adapter metadata.
- `TransportBinding` must store adapter id, transport hint, protocol label, and
  command executor explicitly. It must not call `adapterId()` or
  `transportHint()` on the executor.
- Assigned-delivery adapter command paths must not call
  `sendToAdapterRoute(...)`.
- `DeliveryCommand` and adapter command executor inputs must not grow route,
  raw endpoint, session handle, endpoint lease record, or `TransportPacket`
  fields.
- WebSocket and Socket mainline session classes must not implement endpoint
  registry, raw route registry, and inspector roles together after ACE-2/ACE-3.
- Adapter descriptor metadata must match contributed `TransportBinding`
  metadata.
- Adapter bootstrap output must be represented by explicit contributions, not
  mutable single-slot context state.
- Runtime composites must not rely on `instanceof` discovery to mix selected
  endpoint registry, raw route registry, and diagnostics inspector roles.
- Concrete adapters must not directly construct endpoint lease records,
  selected-worker consumer claims, or worker-presence events; those projection
  rules belong to `TransportEndpointLeasePublisher` and
  `WorkerPresenceSessionPublisher`.

## Completion Criteria

This roadmap can be marked complete only when:

- WebSocket, Socket, and Polling adapters expose assigned delivery as
  `DeliveryCommand -> AdapterCommandExecutor -> final-hop attempt ->
  DispatchOutcome`.
- `WorkerAdapter` no longer exists in the assigned-delivery production
  mainline, or it has been narrowed to the `AdapterCommandExecutor` contract and
  no longer owns adapter metadata.
- Adapter bootstrap input and output are separated. Explicit
  `TransportAdapterContribution` output owns contributed bindings, servers,
  managed adapters, raw/manual channels, and diagnostics contributions.
- Session storage, endpoint lease publication, worker presence ingress,
  diagnostics, raw/manual send, and command execution are separate owners in
  concrete adapters.
- Assigned delivery no longer depends on route/raw endpoint addressing,
  `TransportPacket`, connection id, transport node id, or adapter-local session
  facts as command inputs.
- Raw/manual endpoint send is deleted or isolated with a documented owner and
  tests.
- Transport baseline and proof registry describe the adapter command executor
  boundary.
- Route-key-removal roadmap has been updated to treat adapter internals as
  unblocked or to list only remaining routeKey-specific residue.

## Assumptions

- This repo is still pre-release; in-repo compatibility aliases are not needed.
- Existing worker wire compatibility should be preserved unless a separate
  worker-wire roadmap explicitly changes it.
- Adapter id may remain binding metadata, but it is not command correctness,
  queue identity, or worker routing identity.
- The roadmap optimizes mechanism and owner clarity first. More advanced adapter
  strategy, multi-instance placement, and adapter health policy can be added
  later after the command executor boundary is stable.
