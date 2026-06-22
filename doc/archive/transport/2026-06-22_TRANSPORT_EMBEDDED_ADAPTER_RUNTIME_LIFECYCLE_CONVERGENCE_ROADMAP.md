# Transport Embedded Adapter Runtime Lifecycle Convergence Roadmap

Status: complete; archived after 2026-06-22 implementation.

## Summary

The mailbox dispatch mainline is now clear enough to expose the next source of
transport complexity: embedded Java adapters are not modeled as explicit runtime
units. `MassApplication` currently collects adapter contribution outputs,
builds the runtime registry, claims adapter mailbox consumer leases, refreshes
those leases, starts servers/adapters, and releases leases during shutdown.
Concrete adapters still own protocol I/O and local session lookup, but the
embedded adapter runtime lifecycle is split across starter assembly,
contribution objects, registry construction, handoff lease claims, and adapter
implementation classes.

This roadmap converges embedded adapters into explicit local adapter runtime
units:

```text
Embedded adapter runtime
  = adapter binding metadata
  + built-in default registration
  + adapter mailbox consumer lifecycle
  + protocol-owned server/poll/send/result receive lifecycle
```

The goal is not to implement external or cross-language adapters yet. The goal
is to make embedded Java adapter ownership narrow enough that a future external
adapter protocol can be designed without inheriting Java object wiring,
starter-owned lease refresh loops, or registry side effects.

## Before Convergence Facts

- `TransportBinding` already requires an explicit `adapterMailboxKey` and
  keeps adapter id, transport hint, protocol label, command executor, optional
  pull channel, and optional pull-session evidence driver as binding facts.
- `TransportAdapterContribution` is the explicit bootstrap output for
  bindings, managed adapters, servers, raw/manual channels, and diagnostics.
  It is currently a flat list output and does not encode which server or
  managed resource belongs to which binding.
- `TransportRuntimeRegistry` indexes bindings by `adapterId` for registration
  and by `adapterMailboxKey` for assigned delivery.
- `TransportDeliveryCommandListener` resolves local adapter bindings by
  `adapterMailboxKey` before invoking the embedded Java `AdapterCommandExecutor`.
- `RedisTransportDeliveryCommandHandoff` and in-memory handoff already expose
  mailbox-level `AdapterMailboxConsumerRegistry` operations.
- `MassApplication` owned the adapter mailbox consumer claim, refresh, and
  release loops directly.
- Built-in adapter bootstraps currently choose embedded mailbox defaults from
  adapter config, usually `adapterMailboxKey = adapterId`.
- Polling, WebSocket, and socket adapters have different protocol lifecycle
  shapes, but all should be embedded adapter runtime units from the assembly
  perspective.
- `TransportAdapterBootstrapContext` exposed `AdapterMailboxConsumerRegistry`;
  built-in adapters did not need it, and keeping it on the context left mailbox
  lifecycle ownership open to concrete adapter bootstraps.

## Owner Review

Embedded adapter runtime lifecycle belongs to SDK/starter embedded transport
assembly plus `transport_runtime` embedded-support code.

Transport core owns typed queues, mailbox handoff, endpoint/session evidence,
result-ingress envelopes, and delivery outcomes. It must not own concrete Java
server objects, session managers, frame codecs, callback implementations, or
registration defaults.

Concrete adapters own protocol I/O, local worker session lookup, final-hop
send/poll behavior, and result-frame normalization. They may expose those
capabilities to embedded support through contribution/binding facts, but they
must not claim global worker scheduling truth, task-result schema ownership, or
mailbox handoff policy.

`MassApplication` should assemble embedded adapter runtimes. It should not
personally own per-mailbox lease records, refresh-loop state, or runtime-unit
shutdown details.

## Boundary Decision

Use a new embedded-support owner for local adapter runtime lifecycle:

```text
EmbeddedAdapterRuntime
  contribution-owned shared resources
  adapter mailbox lease units
  contribution-owned managed resources
  start/stop hooks
```

This is not a transport-neutral remote-adapter contract. It is embedded Java
runtime support. If a future external adapter needs a corresponding contract,
that contract must be based on typed mailbox lease/evidence/outcome messages,
not Java object lifecycle callbacks.

## Target Shape

Target high-level flow:

```text
TransportAdapterBootstrap
  -> TransportAdapterContribution
  -> EmbeddedAdapterContributionRuntime(s)
  -> EmbeddedAdapterRuntimeSet

EmbeddedAdapterContributionRuntime.start()
  -> starts contribution-owned managed adapters/servers as needed
  -> starts adapter mailbox lease units for command-delivery bindings
  -> each lease unit claims its adapter mailbox consumer lease
  -> each lease unit starts its lease refresh loop

EmbeddedAdapterContributionRuntime.stop()
  -> stops new protocol ingress and contribution-owned server/adapter resources
  -> stops mailbox lease refresh loop
  -> releases adapter mailbox consumer lease
  -> returns control so handoff/runtime resources can be closed afterward
```

The contribution runtime owns shared lifecycle around one adapter bootstrap
output. Adapter mailbox lease units own only mailbox consumer lease lifecycle for
individual command-delivery bindings. They do not own dispatch queues,
selected-worker session maps, worker scheduling, task result decode, or protocol
session maps.

## Non-Goals

- Do not design external adapter IPC in this roadmap.
- Do not move `AdapterCommandExecutor` back into `transport_api`.
- Do not make `adapterId` a scheduling or worker-facing contract.
- Do not rename every adapter id or mailbox field as a broad cleanup.
- Do not add stats/list/count APIs to prove lifecycle.
- Do not change worker-runtime delivery target evidence semantics.
- Do not change task result payload schema or engine result convergence.
- Do not collapse WebSocket/socket/polling protocol options into one public
  adapter configuration object.

## Do Not Start With

Do not start by adding a generic `AdapterRuntime` public API or external
adapter protocol. The first slice should inventory and extract the embedded
Java lifecycle owner already implicit in current starter assembly.

Do not start by renaming `adapterId`. The immediate problem is lifecycle and
mailbox ownership, not vocabulary churn.

## EARL-0 Inventory And Classification

Goal: classify current embedded adapter lifecycle facts and separate lifecycle
truth from diagnostics and protocol implementation details.

Scope:

- `MassApplication` transport initialization and stop paths.
- `TransportAdapterBootstrap`, `TransportAdapterContribution`,
  `TransportBinding`, `TransportRuntimeRegistry`.
- `AdapterMailboxConsumerRegistry`, `AdapterMailboxConsumerLease`,
  `TransportDeliveryCommandHandoff`.
- Built-in adapter bootstraps:
  - polling
  - WebSocket
  - socket
- Managed transport adapters, transport servers, raw/manual channels, endpoint
  inspectors, pull channels, and session evidence drivers.

Acceptance:

- Inventory identifies which current symbols are lifecycle owners, contribution
  facts, protocol owners, diagnostics, or side channels.
- Inventory explicitly separates assigned delivery from raw/manual messaging
  and endpoint inspection.
- Inventory identifies every current caller of mailbox claim, refresh, release,
  and stop ordering.
- Inventory records whether each built-in adapter has command delivery,
  pull delivery, result ingress, server lifecycle, managed adapter lifecycle,
  raw/manual side-channel, and diagnostics.
- Inventory decides whether each contribution requires one grouped runtime or
  can be represented as one runtime with multiple adapter mailbox lease units.
- Inventory records current `TransportAdapterBootstrapContext`
  `AdapterMailboxConsumerRegistry` exposure and classifies it as residue unless
  a concrete production caller proves otherwise.
- Inventory lists missing mandatory bootstrap/runtime tests that must be
  created before completion proof. Current candidate names include
  `PollingTransportAdapterBootstrapTest`,
  `WebSocketTransportAdapterBootstrapTest`, and
  `SocketTransportAdapterBootstrapTest`.

## EARL-1 Embedded Adapter Runtime Unit

Goal: introduce an embedded-support runtime unit that owns lifecycle for one
adapter contribution, with narrower adapter mailbox lease units for each
command-delivery binding.

Target shape:

```java
final class EmbeddedAdapterContributionRuntime {
    List<TransportBinding> bindings();
    void start();
    void stop();
}

final class AdapterMailboxLeaseRuntime {
    TransportBinding binding();
    void start();
    void stop();
}
```

The exact class name may change, but the owner must be explicit and
package-local to embedded transport assembly unless a real external caller
requires otherwise.

Scope:

- Add an embedded runtime unit under `transport_runtime` embedded-support or
  SDK/starter internal package, depending on dependency direction after
  inventory.
- Move mailbox consumer claim/release responsibility out of `MassApplication`
  into adapter mailbox lease runtime units or a narrow runtime-owned lease
  controller.
- Keep contribution-owned shared resources grouped at contribution runtime
  level unless inventory proves a different grouping is necessary.
- Preserve existing `TransportAdapterContribution` output shape only if a
  contribution-level runtime can safely own its flat shared resources; otherwise
  add explicit grouping rather than assigning all resources to one binding.
- Preserve built-in adapter behavior.

Acceptance:

- `MassApplication` no longer stores per-mailbox consumer lease lists or owns
  the refresh loop.
- Mailbox consumer lease claim/release is tied to embedded adapter runtime
  start/stop.
- Adapter mailbox lease units claim only bindings that can consume assigned
  delivery.
- Contribution runtime construction does not require concrete
  WebSocket/socket/polling session classes.
- Shared servers/managed adapters are not duplicated per binding.
- No public SDK or worker-facing API exposes `adapterMailboxKey`.

## EARL-2 Mailbox Lease Lifecycle Controller

Goal: make mailbox consumer lease behavior finite, refreshable, and owned by
the embedded adapter runtime lifecycle rather than ad hoc starter state.

Scope:

- Define a narrow internal controller for:
  - initial claim
  - periodic refresh
  - release
  - shutdown cancellation
- Move mailbox consumer registry access out of `TransportAdapterBootstrapContext`
  unless inventory proves a concrete adapter-owned runtime unit needs it.
- Use `TransportConfig.adapterMailboxConsumerLeaseMillis` as current policy
  input.
- Keep mailbox lease records transport-runtime typed:
  `AdapterMailboxConsumerLease(adapterMailboxKey, consumerId, generation,
  leaseDeadlineEpochMillis)`.

Acceptance:

- Lease refresh interval is derived from lease duration in one owner.
- Release uses the last claimed lease facts and does not leak through
  `MassApplication`.
- Refresh failure logs bounded diagnostics without changing worker lifecycle or
  scheduling state.
- Embedded in-memory and Redis handoffs observe the same claim/release shape.
- Tests prove lease refresh and release for `adapterId != adapterMailboxKey`.
- Concrete adapter bootstrap packages do not import
  `AdapterMailboxConsumerRegistry` or call `claimMailboxConsumer`.

## EARL-3 Built-In Adapter Default Registration

Goal: make built-in adapter registration defaults explicit without turning
`adapterId` into physical delivery truth.

Scope:

- Polling, WebSocket, and socket bootstraps must explicitly set
  `adapterMailboxKey`.
- Default embedded mailbox may continue to equal `adapterId`, but only as a
  bootstrap/config default, not as an implicit fallback in `TransportBinding`.
- Registration resolver keeps adapter-id/transport-hint behavior for worker
  registration.
- Assigned delivery continues to use `adapterMailboxKey`.

Acceptance:

- `TransportBinding` remains strict: missing `adapterMailboxKey` fails.
- Built-in adapter tests cover explicit mailbox defaults.
- Missing bootstrap/default-registration tests are created or replaced with
  existing real tests before completion proof; candidate names in this roadmap
  must not pass only because `failIfNoSpecifiedTests=false` hides absence.
- At least one focused test proves `adapterId != adapterMailboxKey` still works
  through registration, worker presence delivery target evidence, mailbox
  handoff, and final-hop dispatch.
- Documentation says `adapterId` is embedded registration metadata and
  `adapterMailboxKey` is the physical mailbox target.

## EARL-4 Runtime Set And Stop Ordering

Goal: make embedded adapter start/stop ordering explicit and compatible with
result ingress and delivery drain rules.

Scope:

- Introduce a collection owner such as `EmbeddedAdapterRuntimeSet` or equivalent
  internal orchestration.
- Define stop order relative to:
  - result ingress buffer/inbox
  - delivery handoff pump
  - transport servers
  - managed transport adapters
  - mailbox lease release
  - endpoint/session evidence shutdown
- Align with `GRACEFUL_SHUTDOWN_LIFECYCLE_ROADMAP.md` without implementing the
  whole shutdown roadmap here.

Acceptance:

- `MassApplication` delegates adapter runtime stop to the runtime set.
- Stop order is explicit:
  1. stop accepting new adapter/protocol ingress
  2. stop contribution-owned servers and managed adapters
  3. stop mailbox lease refresh loops
  4. release mailbox consumer leases
  5. allow transport handoff/runtime resources to close
- Mailbox leases are released after new adapter ingress is stopped and before
  transport handoff resources are closed.
- Result ingress is not decoded or drained by adapter runtime lifecycle code.
- Existing stop-order tests are updated or supplemented for mailbox lifecycle.

## EARL-5 Guardrails And Residue Cleanup

Goal: prevent the previous spread of embedded adapter lifecycle owner state from
returning.

Scope:

- Architecture guard against `MassApplication` directly constructing
  `AdapterMailboxConsumerLease`.
- Guard that `TransportBinding` has no implicit mailbox fallback to adapter id.
- Guard that `TransportAdapterContribution` remains output-only and does not
  become a mutable context.
- Guard that `TransportAdapterBootstrapContext` does not expose
  `AdapterMailboxConsumerRegistry` to concrete adapter bootstraps.
- Guard that embedded runtime lifecycle classes do not import concrete
  WebSocket/socket/polling session implementation classes.
- Guard that concrete adapters do not claim mailbox consumers directly unless
  they are implementing an explicitly adapter-owned runtime unit.

Acceptance:

- Production source does not contain ad hoc mailbox refresh loops outside the
  embedded runtime lifecycle owner.
- Production source does not expose mailbox consumer lifecycle through public
  SDK worker APIs.
- Active docs and proof registry identify embedded adapter lifecycle as
  embedded-support ownership, not transport-neutral API ownership.
- Old roadmap or baseline wording that says `MassApplication` owns mailbox
  refresh is removed or updated.

## Roadmap Completion Criteria

- Embedded adapter runtimes are explicit lifecycle units.
- Shared contribution resources are owned by contribution-level runtimes, not
  accidentally duplicated per binding.
- Mailbox consumer claim/refresh/release is no longer handwritten in
  `MassApplication`.
- Concrete adapter bootstraps no longer receive mailbox consumer registry access
  through `TransportAdapterBootstrapContext`.
- Built-in adapter registration defaults are explicit and tested.
- Assigned delivery remains:

  ```text
  selectedWorkerId -> worker-runtime delivery target evidence -> adapterMailboxKey
  adapterMailboxKey -> transport handoff queue
  selectedWorkerId -> adapter-local worker session
  ```

- No adapter runtime lifecycle class owns worker scheduling, task-result schema,
  engine retry/finality, or stats/list/count views.
- Guards fail if mailbox lifecycle, adapter contribution, or adapter metadata
  ownership regresses.
- Current facts are moved into `transport/AGENTS.md` and
  `transport/TRANSPORT_BOUNDARY_BASELINE.md`; this roadmap is archived only
  after a residue scan.

## Verified Proof

```bash
./mvnw -q -pl transport/transport_runtime test "-Dtest=AdapterMailboxLeaseRuntimeTest,EmbeddedAdapterRuntimeSetTest,TransportConvergenceArchitectureGuardTest,TransportAdapterContributionTest,TransportRuntimeRegistryTest,TransportRegistrationResolverTest"
./mvnw -q -pl transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter -am test "-Dtest=PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,PollingSessionEvidenceDriverTest,WebSocketTaskDispatchChannelTest,WebSocketSessionControllerTest,SocketTaskDispatchChannelTest,SocketSessionManagerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test "-Dtest=MassApplicationDistributedTransportTest,MassApplicationStopOrderTest,MassSdkTest" "-Dsurefire.failIfNoSpecifiedTests=false"
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile
./mvnw -q -pl xa-mass-testing -am -DskipTests compile
```

Proof notes:

- `AdapterMailboxLeaseRuntimeTest` and `EmbeddedAdapterRuntimeSetTest` are
  mandatory lifecycle proof tests and are listed without
  `failIfNoSpecifiedTests=false`.
- Adapter module tests remain focused protocol regression coverage; their test
  list may use `failIfNoSpecifiedTests=false` only because no new mandatory
  bootstrap test class is claimed by this archived roadmap.

## Completion Notes

- The embedded runtime unit lives in `transport_runtime` embedded-support code
  so SDK/starter assembly can depend on it without reversing transport
  dependencies.
- The implemented grouping is one contribution runtime plus per-binding mailbox
  lease runtimes. Shared servers and managed adapters are owned once at
  contribution level.
- Current embedded consumer id remains `embedded:<adapterMailboxKey>`. A future
  external-adapter or failover roadmap may replace that with a stable runtime
  instance id if it changes Redis ownership semantics.
