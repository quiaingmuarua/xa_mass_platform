# Embedded SDK Transport Semantic Boundary Convergence Roadmap

Status: completed implementation record (2026-06-29).

Related records:

- `roadmap/TRANSPORT_EMBEDDED_ADAPTER_SPEC_ASSEMBLY_CONVERGENCE_ROADMAP.md`
- `roadmap/TRANSPORT_EMBEDDED_ADAPTER_SPEC_ASSEMBLY_INVENTORY.md`
- `roadmap/EMBEDDED_RUNTIME_SDK_BOUNDARY_CONVERGENCE_ROADMAP.md`
- `transport/AGENTS.md`
- `transport/TRANSPORT_BOUNDARY_BASELINE.md`
- `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`

## Summary

The goal is semantic cleanup, not dependency deletion for its own sake.

`sdk/xa-mass-embedded-sdk` should not directly understand transport-runtime
internals or concrete adapter implementation modules. The embedded SDK should
express runtime intent:

```text
enable these built-in adapters
use this runtime role
use these backend declarations
use these public/embedded extension hooks
```

Adapter startup, transport queues, result ingress, endpoint lease stores,
adapter runtime specs, concrete adapter config translation, and concrete
adapter factories should be assembled by the transport embedded assembly owner,
currently `transport/adapter-starter` or a narrow successor.

Target dependency rule:

```text
embedded-sdk main may depend on:
  xa-mass-transport-api
  xa-mass-transport-adapter-starter

embedded-sdk main must not directly depend on or import:
  xa-mass-transport-runtime
  xa-mass-transport-polling
  xa-mass-transport-socket
  xa-mass-transport-websocket
```

This roadmap does not require those four artifacts to disappear from the
runtime classpath. Built-in Java adapters may still arrive transitively through
`transport/adapter-starter`. The boundary being enforced is: embedded SDK does
not directly consume their implementation types.

## Initial Code Observations

These observations describe the pre-implementation baseline that motivated the
roadmap. They are intentionally retained as before-state context, not as current
implementation truth.

- `sdk/xa-mass-embedded-sdk/pom.xml` directly depends on:
  - `xa-mass-transport-polling`
  - `xa-mass-transport-runtime`
  - `xa-mass-transport-socket`
  - `xa-mass-transport-websocket`
  - `xa-mass-transport-adapter-starter`
  - `xa-mass-transport-api`
- `transport/adapter-starter/pom.xml` already depends on
  `transport-runtime`, `polling`, `socket`, and `websocket` because it owns the
  fixed built-in adapter registry.
- `MassApplication` directly imports transport-runtime objects such as
  `InMemoryTransportResultIngressQueue`, `RedisTransportResultIngressChannel`,
  `TransportResultIngressQueue`, `TransportDispatchQueue`,
  `TransportAssignedDeliverySubmitter`, `EmbeddedAdapterRuntimeEnvironment`,
  `EmbeddedAdapterRuntimeSpec`, `TransportRegistrationResolver`,
  `TransportBinding`, `ResolvedPullWorkerTransport`,
  `InMemoryTransportEndpointLeaseStore`, and
  `CurrentSessionDisconnectSink`.
- `MassApplicationBuilder` directly imports concrete transport modules:
  `RedisTransportNamespaces`, `RedisTransportResultIngressChannel`,
  `RedisTransportDispatchHandoff`, `RedisTransportEndpointLeaseStore`,
  `PollingPendingDeliveryBuffer`, `RedisPollingPendingDeliveryBuffer`,
  `SocketAdapterConfig`, `WebSocketAdapterConfig`, and
  `WebSocketServerFactoryContext`.
- `TransportConfig` and `TransportRuntimeComposition` directly expose
  transport runtime/concrete adapter types such as
  `PollingPendingDeliveryBuffer`, `TransportDispatchQueue`,
  `RedisTransportResultIngressChannel`, `SocketAdapterConfig`,
  `WebSocketAdapterConfig`, and `WebSocketServerFactoryContext`.
- `EmbeddedAdapterSpecAssembler` currently lives in embedded SDK starter/config
  internals after the adapter spec assembly roadmap, but this roadmap
  supersedes that owner decision if adapter-starter-owned declarations become
  the cross-module contract.
- SDK worker session code still imports `PullSessionEvidenceDriver` from
  `transport-runtime` because embedded pull worker session evidence is not yet
  represented by an adapter-starter or transport-api contract.
- `TaskDispatchRoutingSubmitter` is a starter-owned translator from engine
  assignment (`TaskDispatchContext` / `TaskDispatchBinding`) to transport
  assigned-delivery messages, but it currently imports transport-runtime
  delivery types such as `TransportAssignedDeliverySubmitter`,
  `DispatchMessage`, and `AdapterMailboxDispatchBatch`.
- `TaskResultIngressQueueDrain` is a starter-owned drain from transport result
  ingress into engine result convergence, but it currently imports the
  transport-runtime result queue contract directly.
- Several test fixtures import these modules directly. This roadmap targets
  `src/main/java` first; test dependency cleanup can follow after the main
  boundary is stable.

## Owner Review

`embedded-sdk` owns SDK facade, builder ergonomics, public in-process
operations, and application-level runtime orchestration.

`embedded-sdk` does not own transport queue implementations, endpoint lease
store implementations, adapter runtime specs, adapter runtime environments,
dispatch queue submitters, result ingress queue implementations, concrete
adapter config records, or concrete protocol server factory context records.

Adapter declaration contracts consumed by `adapter-starter` are
adapter-starter-owned contracts. SDK builder/config code may populate those
contracts, but `adapter-starter` must not consume SDK-private
`TransportConfig`, builder option objects, or `com.xa.mass.starter.config.*`
types.

`transport/adapter-starter` owns embedded transport assembly:

- built-in adapter declaration normalization;
- fixed adapter runtime factory registry;
- adapter declaration contracts and adapter spec construction;
- object sidecar validation;
- backend port construction from declaration values;
- pre-runtime registration resolver;
- runtime create/start/close;
- pull-worker transport resolution;
- assigned delivery sink creation when the engine needs a transport dispatch
  listener;
- result ingress source ownership for the SDK-owned engine result drain.

`transport-runtime` owns internal queue/result/lease/embedded support types.
They may be used behind `adapter-starter`, but should not leak into
`embedded-sdk` main source.

Concrete adapter modules own protocol defaults and option parsing. Their config
types are implementation details unless deliberately promoted to
`transport-api` or `adapter-starter` as declaration contracts.

## Completion Snapshot

Implemented shape:

- `sdk/xa-mass-embedded-sdk` main source depends directly on
  `xa-mass-transport-api` and `xa-mass-transport-adapter-starter`, not on
  `transport-runtime`, `polling-adapter`, `socket-adapter`, or
  `websocket-adapter`.
- adapter-starter owns cross-module adapter declarations, backend declarations,
  runtime-spec translation, fixed built-in adapter registry, transport queue /
  result / endpoint-lease construction, adapter runtime create/start/close,
  binding lookup, registration resolution, and pull-worker transport resolution.
- SDK starter keeps engine-facing ownership: `TaskDispatchRoutingSubmitter`
  translates engine assignment into `AssignedDeliveryMessage`, and
  `TaskResultIngressQueueDrain` drains `ResultIngressSource` into engine result
  convergence.
- SDK worker pull sessions consume adapter-starter `PullSessionEvidencePort`,
  not transport-runtime `PullSessionEvidenceDriver`.
- Public SDK transport options now express declarations and backend choices;
  concrete adapter config, custom WebSocket server factory context, endpoint
  lease store factories, polling buffer factories, dispatch queue objects, and
  result queue objects are no longer exposed through embedded SDK main source.

## Convergence Strategy

This roadmap intentionally prioritizes stopping cross-module semantic leakage
before perfecting the internal adapter-starter shape.

It is acceptable for `transport/adapter-starter` to become temporarily thicker
while it absorbs embedded transport assembly responsibilities from
`embedded-sdk`. That is an isolation move, not a declaration that the new
internal shape is final.

The rule is:

```text
messy inside one owning module is tolerable during convergence;
messy across module boundaries is not.
```

Temporary adapter-starter growth is allowed only when all of these remain true:

- embedded SDK callers see declarations and stable startup operations, not
  transport runtime objects or concrete adapter implementation classes;
- the newly moved code has a named adapter-starter owner and a documented
  cleanup target in the inventory or later roadmap slice;
- adapter-starter does not expose the absorbed runtime internals back through
  public SDK builder methods;
- the move reduces cross-module leakage immediately, even if the internal
  adapter-starter structure still needs follow-up simplification.

Do not interpret this as permission to create a new permanent dumping ground.
If review shows a transfer would make adapter-starter own unrelated lifecycle
truth, scheduling policy, worker-runtime state, or task-result convergence, stop
and split the decision before implementing that slice.

## Boundary Decision

Introduce an embedded transport assembly surface under `transport/adapter-starter`
that lets `embedded-sdk` pass stable declarations instead of implementation
objects.

The target call shape is:

```text
MassSdk / MassApplicationBuilder
  -> adapter-starter-owned EmbeddedTransportDeclaration values
  -> adapter-starter EmbeddedTransportAssembly
  -> transport-runtime queues/result/lease/specs
  -> concrete adapter runtimes
```

`embedded-sdk` may keep SDK-private builder/config state for ergonomics, but
the cross-module object handed to `adapter-starter` must be owned by
`adapter-starter`. That prevents `adapter-starter -> embedded-sdk` dependency
pressure and keeps SDK-private configuration out of transport assembly.

Not:

```text
MassSdk / MassApplicationBuilder / MassApplication
  -> concrete adapter config classes
  -> Redis transport queue classes
  -> transport-runtime embedded environment/specs
  -> adapter-starter
```

After completion, `sdk/xa-mass-embedded-sdk/src/main/java` should compile
without direct imports from:

```text
com.xa.mass.transport.runtime
com.xa.mass.transport.polling
com.xa.mass.transport.socket
com.xa.mass.transport.websocket
```

It may import:

```text
com.xa.mass.transport
com.xa.mass.transport.channel
com.xa.mass.transport.lease
com.xa.mass.transport.starter
```

Only if those packages are stable transport API or adapter-starter contracts.
If a needed type currently lives under `transport-runtime`, first decide
whether it belongs in `transport-api`, `adapter-starter`, or should be hidden
behind an adapter-starter method.

## Relationship To Existing Roadmaps

This roadmap is separate from
`EMBEDDED_RUNTIME_SDK_BOUNDARY_CONVERGENCE_ROADMAP.md`.

That roadmap is about splitting server/runtime/SDK artifacts so
`xa-mass-server` does not depend on the heavy external SDK artifact.

This roadmap is narrower and should land first if possible: it makes the
current embedded SDK artifact stop directly understanding transport internals.
It does not require creating `sdk/xa-mass-embedded-runtime`.

## Do Not Start With

Do not start by deleting the four POM dependencies.

That would either fail compilation immediately or create a false cleanup where
the classes still arrive transitively through `adapter-starter`. First move
source-level semantics to the correct owner, then delete the direct
dependencies as proof.

Do not move concrete adapter config classes into `transport-api` unchanged just
to satisfy the import guard. If a declaration crosses into adapter-starter,
make it an adapter-starter-owned declaration contract. If a value is
SDK-facing only, keep it SDK-private and translate it before crossing the
module boundary. If it is protocol implementation detail, keep it in the
concrete adapter and translate inside the adapter factory.

Do not expose transport-runtime queue/store/executor objects through SDK
builder options. Backend knobs should be declaration values such as Redis URI,
namespace, capacity, timeout, enabled flag, adapter id, port, path, and max
connections.

## Non-Goals

1. No task lifecycle, scheduling, assignment, result convergence, worker
   runtime, endpoint lease, or adapter protocol behavior change.
2. No requirement that the embedded SDK runtime classpath cannot contain
   transport runtime or adapter artifacts transitively.
3. No new dynamic adapter discovery, `ServiceLoader`, classpath scanning, or
   remote adapter registration.
4. No broad extraction of `sdk/xa-mass-embedded-runtime`; that belongs to the
   broader embedded runtime SDK boundary roadmap.
5. No public compatibility shim for superseded internal SDK builder hooks.
6. No test-source cleanup before main-source semantics are stable.

## EST-0 Inventory And Classification

Goal: classify every embedded SDK main-source direct transport implementation
usage before moving code.

Scope:

- Inventory imports from `sdk/xa-mass-embedded-sdk/src/main/java` to:
  - `com.xa.mass.transport.runtime`
  - `com.xa.mass.transport.polling`
  - `com.xa.mass.transport.socket`
  - `com.xa.mass.transport.websocket`
- Classify each usage as one of:
  - SDK declaration
  - backend runtime construction
  - dispatch/result bridge
  - pull-worker session bridge
  - concrete adapter implementation config
  - starter-owned task-to-transport translator
  - starter-owned result-to-engine drain
  - test-only residue
  - public API leak
- Inventory `sdk/xa-mass-embedded-sdk/pom.xml` direct dependencies and mark
  whether each dependency is required by main source, test source, or transitive
  adapter-starter behavior.
- Inventory currently active guard/doc facts that protect the old shape,
  especially the `TransportConvergenceArchitectureGuardTest` assertions around
  `EmbeddedAdapterSpecAssembler`, `EmbeddedAdapterRuntimeSpec`, and
  `adapter-starter` not importing SDK starter config.

Acceptance:

- A sibling inventory exists, e.g.
  `EMBEDDED_SDK_TRANSPORT_SEMANTIC_BOUNDARY_INVENTORY.md`.
- Inventory separates main and test imports.
- Inventory identifies which types should move to SDK-private builder state,
  which should move to adapter-starter contract, and which should be hidden behind
  adapter-starter methods.
- Inventory states which facts from
  `TRANSPORT_EMBEDDED_ADAPTER_SPEC_ASSEMBLY_CONVERGENCE_ROADMAP.md` are
  superseded by this roadmap and which remain valid.
- No production code changes are required in this slice except the inventory.

## EST-1 Adapter-Starter-Owned Adapter Declarations

Goal: stop SDK config/builder from using concrete adapter module config types.

Scope:

- Introduce adapter-starter-owned declaration records for:
  - WebSocket adapter declaration
  - Socket adapter declaration
  - optional WebSocket custom server factory hook, if still justified
- Retarget `TransportConfig`, `TransportRuntimeComposition`, and
  `MassApplicationBuilder` away from:
  - `WebSocketAdapterConfig`
  - `SocketAdapterConfig`
  - `WebSocketServerFactoryContext`
- Move concrete adapter option translation into adapter-starter/factory code.
- Move or retire `EmbeddedAdapterSpecAssembler` if its old SDK-owned spec
  construction role is superseded; do not leave SDK-owned declarations as the
  cross-module contract.
- Preserve current builder ergonomics such as `webSocketAdapter(...)`,
  `socketAdapter(...)`, `server(...)`, `enabled(...)`, `adapterId(...)`,
  `maxConnections(...)`.
- Update the architecture guard in the same slice so it no longer protects the
  old fact that SDK starter/config owns `EmbeddedAdapterRuntimeSpec`
  construction.

Acceptance:

- `sdk/xa-mass-embedded-sdk/src/main/java` no longer imports
  `com.xa.mass.transport.socket.*` or
  `com.xa.mass.transport.websocket.*`.
- `adapter-starter` still does not import `com.xa.mass.starter.config.*`;
  declaration objects crossing into adapter-starter are owned by
  `adapter-starter`, not SDK-private config.
- Concrete adapter modules still own protocol defaults and option parsing.
- Custom WebSocket server factory, if retained, is expressed through a stable
  adapter-starter or transport-api contract, not a concrete WebSocket module
  context type.
- `MassSdk.TransportOptions` no longer exposes concrete WebSocket/socket
  implementation module types.
- `TransportConfigTest` and `MassSdkTest` cover the same enabled/disabled,
  supplemental adapter, sidecar, and custom server behavior.
- Guard/docs that previously described SDK-owned spec construction are updated
  in this slice, not deferred to EST-5.

## EST-2 Backend Runtime Declaration Instead Of Direct Runtime Objects

Goal: stop SDK builder/config from constructing transport-runtime queue,
result, endpoint lease, and polling buffer implementation objects.

Scope:

- Replace `TransportConfig` suppliers that expose implementation objects:
  - `Supplier<PollingPendingDeliveryBuffer>`
  - `Supplier<TransportDispatchQueue>`
  - `Supplier<RedisTransportResultIngressChannel>`
  - `Supplier<TransportEndpointLeaseStore>` if the concrete store type is not
    a stable API contract
- Replace matching public `MassSdk.TransportOptions` methods that expose
  implementation objects:
  - `pollingPendingDeliveryBufferFactory(...)`
  - `endpointLeaseStoreFactory(...)` unless it is deliberately retained as an
    advanced transport-api contract
  - any method that accepts or returns transport-runtime/concrete adapter
    implementation types
- Introduce declaration values for backend selection:
  - memory/default backend
  - Redis URI
  - namespace prefixes
  - queue capacity
  - endpoint lease millis
  - polling pending capacity
- Let adapter-starter or a narrow embedded transport assembly helper construct:
  - dispatch queue
  - result ingress queue
  - endpoint lease store
  - polling pending delivery buffer
- Retain user-facing builder methods like `redisDistributedChannels(...)` as
  declaration setters, not object factories.

Acceptance:

- `MassApplicationBuilder` no longer imports:
  - `RedisTransportDispatchHandoff`
  - `RedisTransportResultIngressChannel`
  - `RedisTransportEndpointLeaseStore`
  - `RedisTransportNamespaces`
  - `RedisPollingPendingDeliveryBuffer`
  - `PollingPendingDeliveryBuffer`
- Redis distributed channel behavior remains covered by existing
  `MassSdkTest`/`MassApplicationDistributedTransportTest` representative proof.
- SDK builder methods no longer return or accept transport-runtime or
  concrete polling implementation objects.
- Public `MassSdk.TransportOptions` no longer exposes
  `PollingPendingDeliveryBuffer`, transport-runtime queue/result types, or
  concrete adapter implementation context types.
- If `TransportEndpointLeaseStore` remains exposed, the roadmap must record why
  it is a stable transport-api extension contract rather than an implementation
  leak; otherwise remove it from public SDK options in this slice.

## EST-3 Move Embedded Transport Runtime Assembly Behind Adapter-Starter

Goal: make `MassApplication` stop assembling transport-runtime internals
directly.

Scope:

- Add a narrow adapter-starter surface, for example:

```java
EmbeddedTransportAssembly assembly = EmbeddedTransportAssemblies.create(
        EmbeddedTransportDeclaration declaration,
        EmbeddedTransportAssemblyPorts ports
);
```

The exact class names are not fixed by this roadmap, but the owner rule is:
embedded SDK passes declarations and engine/starter ports; adapter-starter
creates or hides transport-runtime internals.

- Move or hide from `MassApplication`:
  - `TransportDispatchQueue`
  - `TransportAssignedDeliverySubmitter`
  - `TransportResultIngressQueue`
  - `InMemoryTransportResultIngressQueue`
  - `RedisTransportResultIngressChannel`
  - `EmbeddedAdapterRuntimeEnvironment`
  - `EmbeddedAdapterRuntimeSpec`
  - `TransportRegistrationResolver`
  - `InMemoryTransportEndpointLeaseStore`
  - `CurrentSessionDisconnectSink` if it remains transport-runtime-owned
- Define narrow assembly ports for worker-runtime/engine bridge callbacks that
  currently sit in `MassApplication`:
  - worker reachability lookup from transport endpoint evidence
  - current-session negative disconnect notification
- These ports are callback seams owned by SDK/starter assembly. Adapter-starter
  may publish transport evidence through them, but it must not import
  `EngineConfig`, `WorkerDispatchBlockRuntime`,
  `WorkerDispatchRecoveryRuntime`, or worker-runtime mutation ports.
- Provide adapter-starter methods for:
  - start/stop embedded adapters
  - expose an assigned-delivery sink or queue port for already-translated
    transport dispatch messages
  - expose a result-ingress source or queue port for SDK/starter-owned result
    draining
  - resolve pull-worker transport
  - resolve registration adapter id before runtime creation
  - expose queue diagnostics without returning runtime queue objects
- Keep task/engine bridge ownership in SDK/starter:
  - `TaskDispatchRoutingSubmitter` or its successor remains the owner that
    reads `TaskDispatchContext` / `TaskDispatchBinding`, resolves selected
    worker delivery evidence, encodes task payload/correlation, and calls a
    stable assigned-delivery sink.
  - `TaskResultIngressQueueDrain` or its successor remains the owner that
    binds a stable result-ingress source to engine result convergence.
  - Adapter-starter must not parse `TaskDispatchContext`,
    `TaskDispatchBinding`, task payload/correlation, or engine result handler
    semantics.

Acceptance:

- `MassApplication` no longer imports `com.xa.mass.transport.runtime.*`.
- `MassApplication` still owns application lifecycle ordering, but not
  transport runtime internal object graph construction.
- `MassApplication` does not construct endpoint lease stores or
  `CurrentSessionDisconnectSink` directly; it supplies narrow callbacks or
  consumers to adapter-starter assembly.
- Adapter-starter does not import `EngineConfig` or worker-runtime mutation
  types. Any engine/worker-runtime reaction remains in SDK/starter assembly or
  a neutral callback contract.
- `TaskDispatchRoutingSubmitter` no longer imports transport-runtime delivery
  implementation types; it depends on a stable assigned-delivery sink/message
  contract from `transport-api` or `adapter-starter`.
- `TaskResultIngressQueueDrain` no longer imports `TransportResultIngressQueue`
  from `transport-runtime`; it depends on a stable result-ingress source
  contract from `transport-api` or `adapter-starter`.
- Adapter-starter does not create `TaskDispatchBatchListener` and does not bind
  result ingress directly to engine result ingest.
- Runtime role behavior remains unchanged for:
  - embedded
  - engine-producer
  - transport-consumer
- `MassApplicationDistributedTransportTest` remains the representative split
  runtime proof.

## EST-4 Pull Worker Session Bridge Cleanup

Goal: remove remaining SDK worker-session imports of transport-runtime embedded
support types.

Scope:

- Replace direct SDK usage of `PullSessionEvidenceDriver` with a stable
  adapter-starter or transport-api pull transport contract.
- Adapter-starter may resolve pull transport ports, but it must not construct
  `EmbeddedPullWorkerSession` or import SDK worker-session classes.
- SDK continues to construct `EmbeddedPullWorkerSession` from stable pull
  transport ports plus SDK-owned worker heartbeat/runtime collaborators.
- Keep worker pull/session behavior unchanged.
- Do not move transport endpoint lease implementation objects into SDK API.

Acceptance:

- `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker` no longer
  imports `com.xa.mass.transport.runtime.*`.
- `MassApplication.openEmbeddedPullWorkerSession(...)` does not depend on
  `ResolvedPullWorkerTransport` or runtime-owned `PullSessionEvidenceDriver`;
  it depends on stable adapter-starter/transport-api pull transport ports.
- `transport/adapter-starter` does not import `com.xa.mass.sdk.worker`.
- `EmbeddedPullWorkerSessionTest` and polling worker representative tests still
  prove explicit online/heartbeat/offline and pull result submission behavior.

## EST-5 Dependency Removal And Guards

Goal: enforce the semantic boundary after source imports are moved.

Scope:

- Remove direct dependencies from `sdk/xa-mass-embedded-sdk/pom.xml`:
  - `xa-mass-transport-runtime`
  - `xa-mass-transport-polling`
  - `xa-mass-transport-socket`
  - `xa-mass-transport-websocket`
- Keep direct dependencies on:
  - `xa-mass-transport-api`
  - `xa-mass-transport-adapter-starter`
- Add Maven/source guards:
  - embedded-sdk POM must not direct-depend on the four forbidden artifacts.
  - embedded-sdk main source must not import forbidden implementation packages.
  - test source may be cleaned later or explicitly allowlisted while main
    source guard is enforced.
- Update:
  - `sdk/README.md`
  - `transport/AGENTS.md`
  - `transport/TRANSPORT_BOUNDARY_BASELINE.md`
  - `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`
  - `doc/PROOF_REGISTRY.md`

Do not wait until EST-5 to update guards or docs that would block an earlier
slice. EST-5 is for final residue protection after the contract has moved.
EST-1/EST-2/EST-3 must update the specific guards and owner docs they make
stale in the same slice.

Acceptance:

- `mvn -pl sdk/xa-mass-embedded-sdk -am -DskipTests compile` passes.
- Dependency tree shows no direct embedded-sdk dependency on the four forbidden
  artifacts. Transitive dependencies through `adapter-starter` are allowed and
  documented.
- Architecture guard fails if embedded-sdk main source imports forbidden
  packages or if the four direct dependencies reappear.

## Suggested Implementation Order

1. EST-0 inventory current imports, POM reasons, and public API leaks.
2. EST-1 replace concrete adapter config usage with adapter-starter-owned
   declaration contracts plus SDK-private builder state.
3. EST-2 replace backend object factories with declaration values.
4. EST-3 move `MassApplication` transport primitive construction/access behind
   adapter-starter while keeping task dispatch translation and result-ingest
   binding in SDK/starter.
5. EST-4 hide pull-session evidence driver usage.
6. EST-5 remove direct dependencies and add guards/docs/proof registry.

This order avoids a false cleanup: dependency deletion comes after source
semantics are moved.

## Verification Candidates

Compile:

```powershell
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/adapter-starter,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests compile
```

Focused tests:

```powershell
.\mvnw.cmd -q -pl transport/adapter-starter -am -DskipTests install
.\mvnw.cmd -q -pl transport/adapter-starter test "-Dtest=EmbeddedAdapterStarterTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/transport_runtime test "-Dtest=TransportConvergenceArchitectureGuardTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=TransportConfigTest,MassApplicationDistributedTransportTest,MassSdkTest,EmbeddedPullWorkerSessionTest,TaskDispatchRoutingSubmitterTest,TaskResultIngressQueueDrainTest" "-DtrimStackTrace=true"
```

Completion proof must not use `-Dsurefire.failIfNoSpecifiedTests=false`; if a
named test is removed or renamed during implementation, update this proof
command in the same slice. Do not combine `-am` with these focused `-Dtest`
commands unless the build is configured to avoid applying the same test pattern
to upstream modules; otherwise Maven can fail on upstream modules that do not
contain the focused test class.

Residue scans:

```powershell
rg -n "com\\.xa\\.mass\\.transport\\.(runtime|polling|socket|websocket)" sdk/xa-mass-embedded-sdk/src/main/java --glob "*.java" --glob "!**/target/**"
rg -n "xa-mass-transport-(runtime|polling|socket|websocket)" sdk/xa-mass-embedded-sdk/pom.xml
.\mvnw.cmd -pl sdk/xa-mass-embedded-sdk dependency:tree "-Dincludes=com.xa.mass:xa-mass-transport-runtime,com.xa.mass:xa-mass-transport-polling,com.xa.mass:xa-mass-transport-socket,com.xa.mass:xa-mass-transport-websocket,com.xa.mass:xa-mass-transport-adapter-starter"
```

Expected after completion:

- First scan: zero main-source hits.
- Second scan: zero direct POM hits for the four forbidden dependencies.
- Dependency tree may still show the four artifacts transitively under
  `xa-mass-transport-adapter-starter`; that is acceptable unless a later
  packaging roadmap changes the runtime distribution model.

## Roadmap Completion Criteria

- Embedded SDK main source no longer imports the four forbidden transport
  implementation package families.
- Embedded SDK POM no longer directly depends on the four forbidden artifacts.
- Adapter-starter owns embedded transport assembly semantics that currently
  leak through `MassApplication`, `TransportConfig`, and
  `MassApplicationBuilder`.
- Adapter-starter owns the cross-module embedded transport declaration
  contracts and does not import SDK-private starter config or SDK worker
  session classes.
- SDK/starter still owns the task dispatch translator and engine result-ingest
  binding; adapter-starter owns only the transport primitive ports they call.
- Public SDK builder methods express declarations, not transport-runtime or
  concrete adapter implementation objects.
- Any temporary adapter-starter internal expansion introduced by this roadmap
  is inventoried with an owner and follow-up cleanup target; completion cannot
  be claimed by simply moving the same ambiguous public surface into
  adapter-starter.
- Current runtime behavior is preserved by focused SDK/starter and transport
  tests.
- Owner docs, SDK boundary guard, and proof registry reflect the new boundary.
