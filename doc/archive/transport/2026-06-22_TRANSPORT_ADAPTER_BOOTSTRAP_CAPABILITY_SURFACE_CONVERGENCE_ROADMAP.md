# Transport Adapter Bootstrap Capability Surface Convergence Roadmap

Status: complete; archived 2026-06-22.

## Summary

Narrow the embedded adapter bootstrap surface so concrete adapters receive
role capabilities, not transport-runtime owner objects.

The immediate goal is internal Java adapter host independence, not external
adapter registration. Built-in adapters and business-specific Java adapters
should be mountable as narrow adapter-host participants without seeing
transport-runtime internals. Future external or cross-language adapters are only
a pressure test, not the current product target:

```text
adapter bootstrap input = narrow host capabilities
adapter contribution output = explicit adapter-owned resources
transport runtime internals = not directly exposed to concrete adapters
```

This roadmap follows the adapter-owned mailbox consumption convergence. That
work moved mailbox consumption to adapter-owned consumers. This roadmap
prevents the next regression: concrete adapter bootstraps casually receiving
transport runtime stores, registries, and channels as if they were adapter
APIs.

## Current Facts

- `TransportAdapterBootstrap` lives in `transport_runtime` and contributes a
  `TransportAdapterContribution`.
- `TransportAdapterContribution` is now append-only output: bindings,
  adapter-owned mailbox consumers, managed adapters, servers, raw channels,
  and diagnostics.
- `TransportBinding` no longer owns `AdapterCommandExecutor`.
- Adapter-owned mailbox consumers are contributed by WebSocket, Socket, and
  Polling bootstraps through `TransportAdapterBootstrapContext`.
- `TransportAdapterBootstrapContext` still holds broad runtime owner objects:
  `TransportResultIngressChannel`, `WorkerPresenceIngress`,
  `TransportEndpointLeaseStore`, `AdapterMailboxConsumerRegistry`, runtime
  executor, mailbox client, and failure evidence sink.
- The context does not expose every broad object through a getter, but it still
  owns them and uses them to manufacture helper objects for adapters.
- Concrete adapter bootstraps currently consume:
  - `context.adapterMailboxKey(adapterId)`
  - `context.sessionEvidencePublisher(adapterId, adapterMailboxKey)`
  - `context.adapterMailboxConsumer(adapterMailboxKey, consumerId, executor)`
  - `context.getResultIngressChannel()`
  - `context.getRuntimeTaskExecutor()`
- `TransportAdapterBootstrapContext.adapterMailboxKey(adapterId)` currently
  returns `adapterId.trim()`. That is current implementation residue, not the
  target mailbox ownership rule.
- `TransportAdapterContribution.validateAgainst(...)` currently verifies
  contributed binding `adapterId` and `transportHint`, but it does not verify
  that contributed mailbox consumers use a host-assigned mailbox key for that
  adapter.
- `transport/AGENTS.md` already states the target pressure test: facts that
  could cross a process boundary must be typed queue, evidence, outcome, or
  result-ingress data, while Java object wiring belongs to embedded adapter
  support. This roadmap uses that rule to keep internal Java adapter seams
  narrow; it does not make external adapters the current implementation goal.

## Owner Review

`transport_runtime` currently has two roles that should not be confused:

```text
transport runtime owner role:
  dispatch handoff implementation
  result ingress implementation
  endpoint evidence store implementation
  failure evidence channel
  runtime registry and embedded host assembly

embedded adapter support role:
  Java bootstrap SPI
  host-provided adapter capabilities
  contribution model for local Java adapters
  helper constructors for mailbox consumer, evidence publisher, and result sink
```

Concrete adapters should depend on the embedded adapter support role, not on
transport runtime internals.

Independently mounted internal Java adapters should not depend on the full
`transport_runtime` artifact as their default programming surface. Full
`transport_runtime` contains in-process assembly, stores, registries,
Redis/in-memory implementations, and embedded host machinery. If an adapter has
to learn that whole module to contribute protocol IO, the adapter boundary is
already too wide.

The target independently mountable Java adapter shape is a narrow adapter
SPI/support surface:

```text
adapter sees:
  AdapterBootstrapCapabilities
  AdapterContribution
  AdapterMailboxClient
  AdapterResultIngressSink
  AdapterSessionEvidenceSink
  AdapterRuntimeExecutor or host executor

adapter does not see:
  TransportEndpointLeaseStore
  WorkerPresenceIngress
  AdapterMailboxConsumerRegistry
  TransportDispatchHandoff implementation
  Redis/in-memory runtime stores
  engine/starter assembly
  worker-runtime delivery target evidence internals
```

This roadmap does not require a new Maven module in the first slice. It should
first make the Java package/API boundary narrow inside `transport_runtime`.
If that surface stabilizes, a later roadmap may extract it to a dedicated
artifact such as `transport-adapter-spi` or `transport-embedded-adapter-support`.

## Boundary Decision

`TransportAdapterBootstrapContext` should be replaced or narrowed into a
capability surface.

The capability surface owns construction of role-specific adapter helpers. It
must not let concrete adapters reach back into transport runtime owner objects.

Allowed capability categories:

```text
Mailbox capability:
  expose host-assigned adapterMailboxKey
  create adapter-owned mailbox consumer for a provided final-hop executor

Session evidence capability:
  create adapter-local session evidence publisher/sink

Result ingress capability:
  accept adapter-recognized result ingress envelopes or frames

Host resource capability:
  provide executor/timer resources where embedded adapter components need them

Optional diagnostics capability:
  publish bounded adapter-local diagnostics only as side-channel evidence
```

Forbidden concrete-adapter bootstrap inputs:

```text
TransportEndpointLeaseStore
WorkerPresenceIngress
AdapterMailboxConsumerRegistry
TransportDispatchHandoff
RedisTransportDispatchHandoff
InMemoryTransportDispatchHandoff
TransportDeliveryService
TransportDeliveryStore
generic route-owner / endpoint-owner stores
engine or worker-runtime lookup services
```

Mailbox ownership rule:

```text
adapter descriptor declares adapter identity
host resolves adapter bootstrap assignment
adapter bootstrap receives assigned mailbox key
adapter bootstrap must not call back into host to mint a key from adapterId,
protocol, or transport hint
```

The current `adapterMailboxKey(adapterId) -> adapterId.trim()` helper must
leave the target surface. Short-term defaults may still assign
`adapterMailboxKey = adapterId` in host assembly, but that default belongs to
the host assignment step, not to concrete adapter bootstrap code.

The assigned mailbox key may come from starter/host configuration or a future
placement strategy outside the concrete adapter. Concrete adapters consume the
assigned value; they do not own the long-term worker group, worker, endpoint, or
adapter-process placement rule.

## Target Shape

The exact class names can change during implementation, but the target shape
should be equivalent to:

```java
public interface AdapterBootstrapCapabilities {
    AdapterBootstrapAssignment assignment();

    AdapterMailboxCapabilities mailbox();

    AdapterSessionEvidenceCapabilities sessionEvidence();

    AdapterIngressCapabilities ingress();

    AdapterHostResources hostResources();
}

public record AdapterBootstrapAssignment(
        AdapterIdentity identity,
        String adapterMailboxKey
) {}

public interface AdapterMailboxCapabilities {
    String assignedMailboxKey();

    AdapterMailboxConsumer consumer(String consumerId,
                                    AdapterCommandExecutor executor);
}

public interface AdapterSessionEvidenceCapabilities {
    AdapterSessionEvidencePublisher publisher();
}

public interface AdapterIngressCapabilities {
    AdapterResultIngressSink resultIngress();
}

public interface AdapterHostResources {
    AdapterHostExecutor executor();
}
```

Important details:

- `AdapterResultIngressSink` should be a narrow adapter-facing ingress sink. It
  may delegate to `TransportResultIngressChannel` inside host support, but
  concrete adapters should not receive the raw channel as the target state.
- `AdapterHostExecutor` should be a narrow host resource view. It may delegate
  to `RuntimeTaskExecutor` inside host support, but concrete adapters should
  not treat it as transport lifecycle or runtime ownership.
- The first slice may keep `AdapterSessionEvidencePublisher` if it is already
  the narrow helper that hides `TransportEndpointLeaseStore` and
  `WorkerPresenceIngress`.
- The first slice should not create wrapper classes that only forward one
  method without reducing reachable owner surface.
- `AdapterIdentity` can be as small as `adapterId + transportHint + protocol`
  if a record helps stop passing raw strings around, but do not add lifecycle
  or health state to it.
- If first-slice implementation cannot immediately replace
  `TransportResultIngressChannel` or `RuntimeTaskExecutor`, the roadmap must
  record the retained raw type as explicit tolerated residue with an exit
  condition. Do not describe a raw type wrapper as the final target shape.

## Adapter Host Dependency Decision

Internal Java adapters that are mounted as independent adapter-host components
should not need full `transport_runtime` as their programming surface.
Future external Java adapters should not depend on full `transport_runtime` as a
stable public dependency either, but that is a pressure test, not this
roadmap's near-term product requirement.

Current embedded adapters may continue to depend on `transport_runtime` while
the code is in-repo, because their bootstrap and host assembly are currently
implemented there. But any surface intended for separately hosted internal Java
adapters, many-endpoint adapter deployments, business-specific protocol
adapters, or future out-of-process adapters must be narrow enough to extract.

Acceptable interim state:

```text
transport_runtime contains package-private/internal runtime implementations
transport_runtime exposes a small adapter-support package used by built-in adapters
guards prevent built-in adapters from importing runtime owner stores/registries
```

Possible future state, only after this surface stabilizes:

```text
transport_adapter_spi or transport_embedded_adapter_support
  owns adapter bootstrap SPI and capability interfaces

transport_runtime
  implements the host capabilities and queue/evidence/result mechanics

concrete adapters
  depend on adapter SPI/support plus protocol libraries
```

This roadmap records the direction but does not require module extraction in
the first implementation pass.

## Non-Goals

- Do not implement external adapter process registration.
- Do not turn third-party or cross-language adapter support into a current
  product feature.
- Do not add authentication, authorization, or remote adapter trust.
- Do not add adapter health, restart, takeover, migration, or lifecycle
  supervision.
- Do not move `TransportDispatchHandoff` queue ownership into adapters.
- Do not redesign `DispatchRoutingItem`, `DispatchRoutingBatch`, or
  `DispatchOutcome`.
- Do not change result-ingress payload semantics.
- Do not add statistics/list/count/inspect APIs to bootstrap mainline.
- Do not create broad `AdapterRuntimeContext` or `AdapterRuntime` objects that
  simply aggregate every capability again.
- Do not extract a new Maven module until the package-level surface is narrow
  and proven.

## Do Not Start With

Do not start by creating a new module. A new module with the current broad
`TransportAdapterBootstrapContext` would only freeze the wrong dependency
surface.

Do not start by renaming `TransportAdapterBootstrapContext` to
`AdapterBootstrapCapabilities` without deleting or hiding broad owner objects.

Do not start by making external adapter registration a product feature. First
make the embedded adapter bootstrap surface small enough that independently
hosted internal Java adapters have a sane support surface. External adapters can
only reuse that later if the platform maturity justifies it.

## ABC-0 - Inventory Bootstrap Capability Usage

Goal: classify what concrete adapters currently consume from
`TransportAdapterBootstrapContext` and what each consumed fact really belongs
to.

Scope:

- Inventory `TransportAdapterBootstrapContext` fields, constructor inputs, and
  public methods.
- Inventory WebSocket, Socket, and Polling bootstrap calls into the context.
- Inventory test-only uses such as `MassApplicationStopOrderTest`.
- Inventory near-term internal Java adapter scenarios that motivate the seam:
  multiple endpoint servers for large worker populations and business-specific
  protocol adapters.
- Classify each fact as:
  - host resource
  - mailbox capability
  - session evidence capability
  - result ingress capability
  - diagnostics/raw side-channel
  - transport runtime owner object
  - stale residue
- Identify whether any concrete adapter imports or stores:
  `TransportEndpointLeaseStore`, `WorkerPresenceIngress`,
  `AdapterMailboxConsumerRegistry`, `TransportDispatchHandoff`,
  Redis/in-memory handoff implementations, or generic delivery stores.

Acceptance:

- Inventory separates production adapter bootstrap usage from tests.
- Inventory proves which broad owner objects are only implementation details
  behind helper construction.
- Inventory names the minimal capability methods needed by WebSocket, Socket,
  and Polling bootstraps.
- Inventory confirms that ABC-1 is solving internal Java adapter independence,
  not public third-party adapter registration.
- Inventory decides whether `getResultIngressChannel()` remains acceptable as
  a first-slice result ingress capability or must be narrowed immediately.

## ABC-1 - Replace Broad Context With Role Capabilities

Goal: make the concrete adapter bootstrap input narrow without changing
adapter protocol behavior.

Scope:

- Replace or narrow `TransportAdapterBootstrapContext` public surface into
  role-specific capabilities.
- Replace `adapterMailboxKey(adapterId)` with host assignment semantics:
  concrete adapter bootstraps receive `assignedMailboxKey()` or equivalent, and
  do not pass adapter identity back to the host to mint a mailbox key.
- Allow the assigned mailbox key to come from starter/host configuration or a
  placement strategy outside the concrete adapter. If the first implementation
  uses `adapterMailboxKey = adapterId`, that must be an explicit host default,
  not adapter-owned placement logic.
- Keep broad runtime owner objects private to the host implementation.
- Remove concrete adapter access to raw endpoint lease store,
  worker-presence ingress, mailbox consumer registry, handoff internals, and
  generic delivery services.
- Keep `AdapterSessionEvidencePublisher` construction behind a session evidence
  capability.
- Keep adapter-owned mailbox consumer construction behind a mailbox capability.
- Keep result ingress access behind an `AdapterResultIngressSink` or explicitly
  recorded first-slice residue with an exit condition.
- Keep host executor access behind an `AdapterHostExecutor` or explicitly
  recorded first-slice residue with an exit condition.
- Update WebSocket, Socket, and Polling bootstraps to consume only those role
  capabilities.
- Keep `TransportAdapterContribution` as explicit adapter output.
- Update `TransportAdapterContribution.validateAgainst(...)` or host assembly
  validation so contributed `AdapterMailboxConsumer` entries are rejected when
  their mailbox key is not the host-assigned mailbox key for the adapter
  contribution.

Acceptance:

- Concrete adapter bootstrap code cannot access
  `TransportEndpointLeaseStore`, `WorkerPresenceIngress`,
  `AdapterMailboxConsumerRegistry`, or `TransportDispatchHandoff`.
- Concrete adapter bootstrap code consumes a capability object with narrow
  role methods, not a general runtime context.
- Concrete adapter bootstrap code does not compute mailbox keys from
  `adapterId`, protocol, transport hint, or descriptor fields.
- Concrete adapter bootstrap code treats mailbox placement as already assigned;
  adapter code does not own the long-term strategy for worker group, worker,
  endpoint, or adapter-process placement.
- Contribution validation rejects mailbox consumers whose
  `adapterMailboxKey()` is not owned by the host assignment for that adapter.
- Result ingress and host executor raw runtime types are either replaced with
  narrow adapter-facing types or explicitly listed as tolerated residue with a
  named exit condition.
- Built-in adapter behavior is unchanged: WebSocket/socket push final-hop,
  polling mailbox-to-pull-buffer flow, result ingress, session evidence, raw
  side-channel, and diagnostics continue to work.
- Transport embedded support owns capability provider implementation;
  `MassApplication` / starter assembly wires that provider with runtime
  dependencies.
- No new lifecycle/health/restart/migration state is introduced.

## ABC-2 - Guard Adapter SPI Against Runtime Owner Leakage

Goal: make the boundary hard to regress.

Scope:

- Update `TransportConvergenceArchitectureGuardTest` or equivalent guard to
  reject concrete adapter main-source imports of:
  - `TransportEndpointLeaseStore`
  - `WorkerPresenceIngress`
  - `AdapterMailboxConsumerRegistry`
  - `TransportDispatchHandoff`
  - Redis/in-memory transport handoff implementations
  - generic delivery service/store types
- Add package-level guards so concrete adapter main sources cannot directly
  import transport-runtime owner packages such as dispatch handoff
  implementations, lease stores, registries, or runtime assembly packages.
  Adapter main sources may depend only on adapter support/SPI packages plus
  their protocol-local packages.
- Guard that `TransportAdapterBootstrapContext` or its successor does not
  expose raw getters for broad owner objects.
- Guard that adapter bootstraps do not mint mailbox keys from adapter id,
  protocol, descriptor, or transport hint directly; mailbox assignment stays
  host-owned.
- Guard that contribution validation rejects mailbox consumers for non-assigned
  mailbox keys.
- Guard that no external SDK, public worker API, or server worker API imports
  embedded adapter bootstrap/support classes.
- Update `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  and `doc/PROOF_REGISTRY.md`.

Acceptance:

- Architecture guard fails if broad runtime owner objects re-enter concrete
  adapter bootstrap code.
- Architecture guard fails if concrete adapter bootstrap code calls a
  mailbox-key minting API with adapter identity.
- Contribution tests fail if a mailbox consumer can be contributed for a
  mailbox not assigned to that adapter.
- Active owner docs say full `transport_runtime` is not the target programming
  surface for independently hosted internal Java adapters, and future external
  adapters remain only a pressure test until a separate product decision exists.
- Active owner docs distinguish transport runtime implementation from embedded
  adapter support SPI.
- Proof registry names the capability-surface proof and its negative guards.

## ABC-3 - Optional Module Boundary Decision

Goal: decide whether the stabilized adapter support surface should remain in
`transport_runtime` packages or move to a dedicated artifact.

This phase starts only after ABC-1 and ABC-2 have landed and the surface is
stable.

Decision inputs:

- Does any real internal Java adapter host or separately developed adapter need
  to compile against the SPI outside the built-in adapter modules?
- Is there a concrete external Java adapter caller, or is external adapter
  support still only a pressure test?
- Can the SPI be expressed without Redis/in-memory runtime implementation
  dependencies?
- Are the contribution and capability interfaces stable enough to avoid
  churn?
- Does extraction reduce dependencies, or only add module ceremony?

Possible outcomes:

1. Keep the narrow SPI in `transport_runtime` as embedded/internal adapter
   support for now.
2. Extract a new module such as `transport/transport_adapter_spi`.
3. Extract only API-neutral pieces and leave embedded Java helper constructors
   in `transport_runtime`.

Acceptance:

- The decision is recorded with dependency graph, caller set, and proof cost.
- If a module is extracted, concrete adapters depend on the narrow module and
  not on full `transport_runtime` unless they also need embedded host support.
- If no module is extracted, docs explicitly classify the surface as internal
  adapter support and not a public external adapter API.

## Verification Candidates

Inventory and compile:

```powershell
rg -n "TransportAdapterBootstrapContext|TransportAdapterBootstrap|TransportAdapterContribution|TransportEndpointLeaseStore|WorkerPresenceIngress|AdapterMailboxConsumerRegistry|TransportDispatchHandoff|getResultIngressChannel|sessionEvidencePublisher|adapterMailboxKey\\(" transport sdk xa-mass-server roadmap doc --glob "!**/target/**" --glob "!doc/archive/**"
.\mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests test-compile
```

Focused proof:

```powershell
.\mvnw -q -pl transport/transport_runtime -am test "-Dtest=TransportConvergenceArchitectureGuardTest,TransportAdapterContributionTest,TransportRegistrationResolverTest,EmbeddedAdapterHostSetTest,AdapterMailboxConsumerLoopTest" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw -q -pl transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am test "-Dtest=WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,MassApplicationDistributedTransportTest,MassApplicationStopOrderTest,MassSdkTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Residue scan:

```powershell
rg -n "getEndpointLeaseStore|getWorkerPresenceIngress|getMailboxConsumerRegistry|getDispatchHandoff|getDeliveryService|getDeliveryStore|TransportEndpointLeaseStore|WorkerPresenceIngress|AdapterMailboxConsumerRegistry|TransportDispatchHandoff|adapterMailboxKey\\(|mailboxKeyFor\\(" transport/websocket-adapter/src/main transport/socket-adapter/src/main transport/polling-adapter/src/main --glob "!**/target/**"
rg -n "TransportAdapterBootstrapContext|AdapterBootstrapCapabilities|AdapterMailboxCapabilities|AdapterResultIngressSink|AdapterHostExecutor" sdk/xa-mass-embedded-sdk-api sdk/xa-mass-java-sdk xa-mass-server/src/main --glob "!**/target/**"
```

Allowed residue after ABC-1:

- Adapter session tests may instantiate endpoint lease stores directly to test
  session evidence behavior.
- `transport_runtime` host implementation and starter assembly may own runtime
  stores and registries.
- Architecture guards may contain forbidden-name strings.

Guard split:

```text
concrete adapter main-source denylist:
  no runtime owner stores, registries, handoff internals, or mailbox-key minting

starter / embedded host allowlist:
  may construct capability providers from runtime dependencies

public SDK / server API denylist:
  no embedded adapter bootstrap/support imports or runtime owner objects
```

## Completion Criteria

- Concrete adapter bootstraps consume only narrow role capabilities.
- Adapter mailbox key is host-assigned before bootstrap; concrete adapter
  bootstrap does not mint it from adapter identity.
- Broad transport runtime owner objects are private to host implementation or
  tests, not concrete adapter bootstrap inputs.
- Contribution validation prevents an adapter from contributing mailbox
  consumers for mailboxes it was not assigned.
- Adapter contribution output remains explicit and append-only.
- Built-in WebSocket, Socket, and Polling behavior remains unchanged.
- Active docs state that the near-term target is internal Java adapter host
  independence, and that future external Java adapters should not depend on full
  `transport_runtime` if that later product decision is made.
- Guards prevent broad runtime owner objects from re-entering concrete adapter
  bootstrap code.
- No lifecycle, health, restart, migration, or remote registration owner is
  introduced by this convergence.
