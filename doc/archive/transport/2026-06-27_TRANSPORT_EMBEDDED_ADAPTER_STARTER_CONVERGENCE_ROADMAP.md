# Transport Embedded Adapter Starter Convergence Roadmap

Status: implemented.

Implementation summary:

- Added `transport/adapter-starter` as the bundled embedded adapter startup
  owner.
- Replaced bootstrap/contribution resource baskets with
  `EmbeddedAdapterRuntimeSpec`, `EmbeddedAdapterStarter`, and concrete adapter
  runtime factories.
- Embedded SDK now passes specs to adapter-starter and does not construct
  concrete adapter runtime factories.
- Dispatch/result queues are direct keyed primitives; dispatch offer no longer
  depends on adapter consumer availability evidence.
- Removed the legacy bootstrap/contribution and mailbox-consumer availability
  classes from the mainline.

## Summary

Current embedded transport startup still treats concrete adapters as objects
contributed into SDK composition:

```text
MassApplication
  -> TransportRuntimeComposition.resolveTransportAdapterBootstraps()
  -> TransportAdapterBootstrap.contribute(context)
  -> TransportAdapterContribution(binding + server + mailbox consumer + managed resources)
  -> EmbeddedAdapterHostSet.start()
```

This is cleaner than the old central dispatch path, but it is still not an
independent adapter runtime model. The embedded SDK knows too much about
concrete adapter bootstraps, contribution resources, mailbox consumers, and
adapter resource startup details.

Target direction:

```text
embedded-sdk
  -> adapter-starter
       -> WebSocketAdapterRuntime
       -> PollingAdapterRuntime
       -> SocketAdapterRuntime
```

The first executable target is `transport/adapter-starter`: it owns the default
embedded adapter runtime list and startup set. Concrete adapters become
runtime-owned units with `start/close` and no resource-basket contribution
model.

This is not a remote adapter registration roadmap. Remote adapter processes are
a pressure test for the boundary, not a current implementation requirement.

## Current Code Observations

- `transport/transport_runtime` defines `TransportAdapterBootstrap`,
  `TransportAdapterBootstrapContext`, `TransportAdapterContribution`,
  `EmbeddedAdapterContributionHost`, and `EmbeddedAdapterHostSet`.
- `transport/websocket-adapter`, `transport/polling-adapter`, and
  `transport/socket-adapter` implement concrete `*TransportAdapterBootstrap`
  classes that return contribution resource baskets.
- `sdk/xa-mass-embedded-sdk` currently owns the application composition that
  creates adapter bootstraps through `TransportRuntimeComposition`.
- `TransportRuntimeComposition` directly creates
  `PollingTransportAdapterBootstrap`, `WebSocketTransportAdapterBootstrap`, and
  `SocketTransportAdapterBootstrap`, and also stores supplemental
  `TransportAdapterBootstrap` instances.
- `MassApplication` creates `TransportAdapterBootstrapContext`, validates
  `TransportAdapterContribution`, registers contributed `TransportBinding`
  values, and starts `EmbeddedAdapterHostSet`.
- `MassApplication` also owns transport runtime internals such as dispatch
  handoff, result ingress queue pump, delivery failure inbox pump, endpoint
  lease store, and transport runtime executor.
- `WorkerRuntime` in `sdk/xa-mass-java-sdk` is a better lifecycle shape for an
  independently managed runtime: one object owns its protocol driver,
  maintenance loop, dispatch processing, result submit, `start`, `isRunning`,
  and `close`.

## Owner Review

Concrete adapter modules own protocol-specific runtime behavior:

- protocol server/client resources;
- local selected-worker session indexes;
- worker-channel frame parse/write edge;
- selected-worker final-hop send;
- adapter-local pull buffer when the protocol is polling;
- session evidence observation and local evidence refresh source.

`transport-runtime` owns shared transport primitives and embedded adapter SPI:

- keyed assigned-dispatch queue primitive;
- keyed result ingress queue primitive;
- endpoint/session evidence ports;
- `DispatchMessage`, `DispatchOutcome`, and common final-hop outcome helpers;
- narrow runtime interfaces that concrete adapter runtimes implement.

`transport/adapter-starter` owns default embedded adapter startup:

- bundled adapter runtime list;
- adapter runtime specs and enabled/disabled filtering;
- adapter-type factory registry, such as `websocket`, `polling`, and `socket`;
- adapter-id runtime registry for the created embedded runtime instances;
- adapter runtime construction from explicit dispatch/result queue keys;
- duplicate adapter id validation;
- adapter-id based start/close over its internal runtime registry.

`sdk/xa-mass-embedded-sdk` owns product-level application assembly. In this
roadmap it may continue to depend on transport-runtime; the target is only to
stop it from manually assembling concrete adapter bootstrap resource baskets.

## Boundary Decision

Create `transport/adapter-starter` as the owner of bundled embedded adapter
runtime startup. The module may depend on:

```text
transport-runtime
websocket-adapter
polling-adapter
socket-adapter
```

`transport-runtime` must not depend on concrete adapter modules.

Replace the contribution model with an embedded adapter runtime model. The
mainline should converge toward:

```java
public interface EmbeddedTransportAdapterRuntime extends AutoCloseable {
    TransportAdapterDescriptor descriptor();

    void start();

    boolean isRunning();

    @Override
    void close();
}
```

The starter should keep the runtime set internal rather than exposing it to
embedded SDK. The embedded SDK should receive only creation results and call
starter methods by adapter id:

```java
public final class EmbeddedAdapterStarter implements AutoCloseable {
    EmbeddedAdapterCreateResult create(List<EmbeddedAdapterRuntimeSpec> specs);

    void start(String adapterId);

    void close(String adapterId);

    boolean isRunning(String adapterId);

    @Override
    void close();
}

public record EmbeddedAdapterCreateResult(
        List<String> adapterIds) {
}
```

Internally, `adapter-starter` should own both adapter lookup maps:

```text
factoryByType:
  websocket -> WebSocketAdapterRuntimeFactory
  polling   -> PollingAdapterRuntimeFactory
  socket    -> SocketAdapterRuntimeFactory

runtimeByAdapterId:
  <adapterId> -> EmbeddedTransportAdapterRuntime
```

`factoryByType` is how the starter creates concrete adapter runtimes from
starter specs. `runtimeByAdapterId` is how the starter tracks created adapter
instances, validates duplicate adapter ids, and may later support narrow
per-adapter control.

First-slice public surface should stay minimal. It is enough for
`create(specs)` to return created adapter ids only; lifecycle calls should be
adapter-id based: `start(adapterId)`, `isRunning(adapterId)`, and
`close(adapterId)`. Registration resolver state and concrete runtime objects
are adapter-starter internal facts and must not be returned to embedded SDK as
snapshots. A convenience `startAll(created.adapterIds())` may exist, but it
must still delegate to adapter-id lifecycle calls. Do not add restart,
watchdog, recovery, migration, or health policy.

The embedded SDK call surface should be spec-only. It should pass
`EmbeddedAdapterRuntimeSpec` values and receive an `EmbeddedAdapterCreateResult`.
Do not expose `EmbeddedTransportAdapterRuntime` or an
`EmbeddedAdapterRuntimeSet` to embedded SDK. Do not introduce a caller-provided
`EmbeddedAdapterRuntimeHost` as the public embedded-SDK-to-adapter-starter
boundary.

`EmbeddedAdapterRuntimeSpec` should carry only the minimum adapter startup
intent:

```java
public record EmbeddedAdapterRuntimeSpec(
        String type,
        String adapterId,
        String dispatchQueueKey,
        String resultQueueKey,
        Map<String, String> options) {
}
```

`type` selects the adapter factory. `adapterId` is the concrete runtime
instance id used for `start(adapterId)`, `close(adapterId)`, and
`isRunning(adapterId)`. `dispatchQueueKey` is the assigned-dispatch queue the
adapter runtime consumes. `resultQueueKey` is the result ingress queue the
adapter runtime writes. `options` is optional scalar protocol/runtime
configuration such as WebSocket port/path/max connections or polling batch
size; it must not carry worker-routing correctness, worker lifecycle, concrete
runtime objects, session registries, evidence publishers, mailbox allocators,
queue objects, sinks, executors, or protocol resource instances.

`dispatchQueueKey` and `resultQueueKey` are execution queue references, not
worker-routing identities. They are part of the target adapter runtime contract
even though the current adapter code is still interface/contribution based.
The roadmap must therefore first replace the adapter-facing context/capability
interfaces with direct keyed queue primitives, then switch embedded SDK over.
Do not delete these keys from the spec just because current bootstraps cannot
use them yet.

`options` is not a generic extension object. First-party bundled adapter
factories may parse scalar options. Object/function extensions such as custom
server factories must use a typed adapter runtime factory or starter factory
registration path; they must not be encoded into the string options map.

Transport-runtime should expose direct keyed queue primitives used by both the
producer side and adapter runtimes:

```java
interface TransportDispatchQueue {
    List<DispatchOutcome> offer(String dispatchQueueKey, List<DispatchMessage> items);

    List<DispatchMessage> poll(String dispatchQueueKey, int maxItems, long timeoutMillis)
            throws InterruptedException;
}

interface TransportResultIngressQueue {
    boolean offer(String resultQueueKey, ResultIngressEntry entry);

    ResultIngressEntry poll(String resultQueueKey, long timeoutMillis) throws InterruptedException;
}
```

Names may be adjusted during implementation, but the shape must stay direct:
the key is a method argument and no per-adapter resolver/ports layer is added
just to wrap the queue. Memory and Redis implementations must follow the same
keyed semantics. Embedded SDK passes key names only; it does not construct or
pass queue objects.

For this roadmap, result queue topology is deliberately narrow. Bundled
embedded adapters must use one shared/default `resultQueueKey`, and the engine
side result convergence pump continues to drain that one queue. Supporting
multiple result queue keys requires a separate result-pump registry decision
that names the engine-side drain owner, stop order, and failure semantics.

Adapter-starter may be constructed with the shared dispatch queue, result
queue, delivery-failure queue/sink, executor, and timing/capacity knobs needed
to run bundled adapter runtimes. These are infrastructure dependencies of the
starter, not public SDK startup intent and not per-adapter fake abstractions.
Concrete adapter runtimes operate on the shared keyed queues using their spec's
`dispatchQueueKey` and `resultQueueKey`.

Direct queue operation also removes dispatch offer dependency on adapter
consumer availability. A dispatch offer may fail synchronously for shutdown,
capacity, encoding/corruption, or queue backend errors, but it should not reject
assigned work merely because no adapter consumer is currently publishing
availability. Accepted work that is not consumed is recovered by engine-owned
attempt timeout, retry, and compensation.

Adapter-starter should assemble everything else internally from starter-owned
defaults, specs, and factories: adapter type selection, concrete adapter
factory lookup, adapter id validation, session evidence publisher
construction, queue consumer construction, protocol server construction, and
concrete adapter runtime construction.

Adapter-starter also owns any registration resolver state needed to support
worker registration or pull-worker transport resolution. These are internal
starter/runtime facts, not snapshots exposed to embedded SDK.

Adapter-starter must not synthesize hidden queue keys from adapter id. If a
caller wants a different key shape, multiple keys, or a key migration, it should
change the `EmbeddedAdapterRuntimeSpec` values supplied by engine/assembly.
Transport queues simply execute against the provided key.

Adapter runtimes must not receive engine, worker-runtime scheduling mutation
APIs, generic task lifecycle objects, raw stores, full SDK composition objects,
or caller-built protocol/session registries.

## Target Module Shape

| Module | Role | May depend on | Must not own |
| --- | --- | --- | --- |
| `transport/transport_runtime` | transport keyed queue primitives, embedded adapter SPI, common helpers | transport API, infra queues, public contract as needed | concrete adapter startup list, SDK application assembly, engine lifecycle |
| `transport/websocket-adapter` | WebSocket protocol runtime | transport-runtime | default adapter list, SDK composition, engine/worker-runtime scheduling |
| `transport/polling-adapter` | polling protocol runtime and pull buffer | transport-runtime | default adapter list, SDK composition, engine/worker-runtime scheduling |
| `transport/socket-adapter` | socket protocol runtime | transport-runtime | default adapter list, SDK composition, engine/worker-runtime scheduling |
| `transport/adapter-starter` | bundled embedded adapter runtime list and start/close set | transport-runtime + concrete adapter modules | underlying transport queues/stores, engine lifecycle, worker-runtime policy |
| `sdk/xa-mass-embedded-sdk` | product-level SDK/application facade | transport-runtime + adapter-starter during this roadmap | concrete adapter bootstrap list and contribution resource baskets |

## Do Not Start With

Do not make `transport-runtime` depend on `websocket-adapter`,
`polling-adapter`, or `socket-adapter`. That creates a module cycle and makes
transport-runtime own the concrete adapter list.

Do not leave `TransportAdapterBootstrap` and `EmbeddedTransportAdapterRuntime`
as two supported live tracks. During the replacement slice, temporary local
compilation scaffolding is acceptable, but slice acceptance requires the old
bootstrap/contribution mainline to be removed.

Do not delete bootstrap/contribution types before adapter-starter API, spec
parsing, keyed dispatch/result queues, and concrete adapter runtime factories
exist. The new mechanism should be built first, then embedded SDK should be
switched in one compiling slice that removes the old path.

Do not introduce `dispatchQueueKey` / `resultQueueKey` and then hide them behind
a resolver or per-adapter ports object. The point of these keys is to make queue
operation explicit.

Do not add restart, watchdog, failover, migration, adapter health lifecycle, or
remote adapter registration. Start/close is enough for this roadmap.

## Non-Goals

1. No change to task lifecycle, scheduling, worker selection, result
   convergence, worker-runtime eligibility policy, or public HTTP contracts.
2. No remote adapter process registration in this roadmap.
3. No adapter restart/watchdog/failover/migration lifecycle owner.
4. No compatibility alias for removed bootstrap/contribution APIs.
5. No broad generic adapter framework beyond the minimal embedded runtime SPI.
6. No change to transport delivery correctness: dispatch uses
   `dispatchQueueKey + selectedWorkerId`; concrete adapters still demux by
   selected worker locally.
7. No move of worker command, worker state report, or capability self-report
   semantics into transport.

## EAS-0 Inventory And Slice Lock

Goal: classify all current bootstrap/contribution callers before replacing the
contract.

Scope:

- Inventory main/test references to:
  `TransportAdapterBootstrap`, `TransportAdapterBootstrapContext`,
  `TransportAdapterContribution`, `EmbeddedAdapterHostSet`,
  `EmbeddedAdapterContributionHost`, and concrete `*TransportAdapterBootstrap`.
- Separate production usage from test fixture usage.
- Inventory `TransportConfig` and `TransportRuntimeComposition` fields that
  expose supplemental or primary adapter bootstrap customization.
- Decide the first replacement for custom/supplemental embedded adapter
  injection: runtime factory, runtime spec, or explicit starter extension.
- Decide custom object extension handling explicitly: scalar first-party
  options may remain in `EmbeddedAdapterRuntimeSpec.options`; custom server
  factories or other object/function extensions must use typed adapter runtime
  factories or starter factory registration, not `Map<String, String>`.
- Confirm which `TransportRuntimeComposition` adapter-bootstrap responsibilities
  move to `adapter-starter` during EAS-1/EAS-2.

Acceptance:

- The inventory identifies the exact call sites that must move in EAS-1.
- Production and test-only fixtures are separated.
- The roadmap has a selected replacement for custom/supplemental adapter
  injection before implementation starts.
- The roadmap records that `EmbeddedAdapterRuntimeSpec` carries
  `dispatchQueueKey` and `resultQueueKey`, and that adapter runtime support for
  direct keyed queue operation must be created before embedded SDK migration.
- No code behavior changes are required in this slice.

Verification candidates:

```powershell
rg -n "TransportAdapterBootstrap|TransportAdapterBootstrapContext|TransportAdapterContribution|EmbeddedAdapterHostSet|EmbeddedAdapterContributionHost|TransportAdapterDescriptor" transport sdk --glob "*.java" --glob "!**/target/**"
rg -n "setPrimaryTransportAdapterBootstrap|addSupplementalTransportAdapterBootstrap|resolveTransportAdapterBootstraps" sdk/xa-mass-embedded-sdk/src/main/java sdk/xa-mass-embedded-sdk/src/test/java --glob "*.java"
```

## EAS-1 Add Adapter Starter API And Internal Registries

Goal: introduce the new adapter-starter surface and internal maps without
touching the current embedded SDK bootstrap/contribution mainline.

Scope:

- Add embedded adapter runtime SPI to `transport-runtime`.
- Introduce `transport/adapter-starter`.
- Add `EmbeddedAdapterRuntimeSpec(type, adapterId, dispatchQueueKey,
  resultQueueKey, options)` as the only embedded-SDK-to-adapter-starter startup
  intent DTO.
- Add direct keyed dispatch/result queue primitives in `transport-runtime`.
  They replace adapter-facing dispatch/result capability interfaces on the
  embedded adapter runtime path.
- Let adapter-starter construction receive the shared dispatch queue, result
  queue, delivery failure sink/queue, executor, and timing/capacity knobs it
  needs to start adapter runtimes. Do not wrap these as per-adapter
  runtime ports.
- Pin result ingress topology to one shared/default `resultQueueKey` for all
  bundled embedded adapters. Do not introduce a multi-result-queue pump
  registry in this roadmap.
- Remove dispatch offer gating on adapter consumer availability from the new
  queue primitive path. Consumer availability may remain only as diagnostics or
  later evidence; it must not be required for queue offer correctness.
- Add `EmbeddedAdapterCreateResult(adapterIds)` and
  `EmbeddedAdapterStarter.create(specs)`.
- Add `factoryByType` and `runtimeByAdapterId` registries inside
  `adapter-starter`.
- Add direct queue-based test adapter runtimes that poll
  `dispatchQueueKey` and offer results to `resultQueueKey` without using the
  old contribution model.
- Make `EmbeddedAdapterStarter` the start/close owner for bundled embedded
  adapter runtimes through its internal `adapterId -> runtime` registry.
- Add deterministic `startAll(adapterIds)` or equivalent startup helper that
  closes already-started adapter runtimes in reverse order if a later runtime
  fails to start. This is resource hygiene, not health lifecycle.
- Use stub/test adapter runtime factories in this slice if concrete
  WebSocket/Polling/Socket runtimes have not migrated yet.
- Do not update `MassApplication` in this slice.
- Do not delete `TransportAdapterBootstrap`, `TransportAdapterBootstrapContext`,
  `TransportAdapterContribution`, `EmbeddedAdapterContributionHost`, or
  `EmbeddedAdapterHostSet` in this slice.

Acceptance:

- `transport-runtime` has no production dependency on concrete adapter modules.
- `transport/adapter-starter` depends on concrete adapter modules and is the
  only bundled default adapter-list owner.
- `adapter-starter` owns `type -> factory` and `adapterId -> runtime`
  registries; embedded SDK does not maintain either map.
- `create(specs)` accepts specs only and returns only adapter ids.
- `EmbeddedAdapterRuntimeSpec` is the only public startup intent DTO between
  embedded SDK and adapter-starter.
- `EmbeddedAdapterRuntimeSpec` includes `dispatchQueueKey` and
  `resultQueueKey`; starter tests prove adapter runtimes use those keys as
  direct queue operation parameters.
- Adapter-starter construction has shared queue/executor/failure dependencies;
  `create(specs)`, spec fields, and spec `options` do not carry queue objects,
  executors, sinks, stores, pumps, or per-adapter ports.
- Tests prove memory queue behavior for at least two different
  `dispatchQueueKey` values, so dispatch key routing is not only a stored
  string.
- Tests prove all bundled adapter specs use the same shared/default
  `resultQueueKey`, and an unsupported result key fails fast unless a
  multi-result-queue pump topology is introduced in a later roadmap.
- Tests prove offering to a dispatch queue does not require an active adapter
  consumer availability record.
- Adapter-starter tests prove duplicate adapter id rejection, unknown type
  rejection, dispatch/result keyed queue operation, adapter-id start/close, and
  startup rollback on partial failure.
- Current embedded SDK bootstrap/contribution mainline still compiles unchanged.

Verification candidates:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime,transport/adapter-starter -am -DskipTests compile
.\mvnw.cmd -q -pl transport/adapter-starter -am test "-Dtest=EmbeddedAdapterStarterTest" "-DtrimStackTrace=true"
```

`EmbeddedAdapterStarterTest` does not exist today; this slice must create it.

## EAS-2 Migrate Concrete Adapters And Embedded SDK

Goal: switch the production embedded startup path from bootstrap contribution
resources to adapter-starter runtimes, then delete the old contribution model in
the same compiling slice.

Scope:

- Replace `WebSocketTransportAdapterBootstrap` with `WebSocketAdapterRuntime`
  plus a narrow factory or constructor owned by the WebSocket module.
- Replace `PollingTransportAdapterBootstrap` with `PollingAdapterRuntime`.
- Replace `SocketTransportAdapterBootstrap` with `SocketAdapterRuntime`.
- Make concrete adapter runtime factories available to `adapter-starter`.
- Move bundled WebSocket, polling, and socket default selection from
  `TransportRuntimeComposition` into `adapter-starter`.
- Keep concrete adapter defaults and protocol-specific assembly inside
  `adapter-starter` or the concrete adapter module. Dispatch/result execution
  uses the shared keyed queues with `dispatchQueueKey` / `resultQueueKey`, not
  an embedded SDK object parameter surface and not per-adapter resolver/ports
  wrappers.
- Replace primary/supplemental bootstrap setters with runtime spec or
  adapter-runtime factory inputs.
- Keep custom embedded adapter extension narrow: caller may supply a runtime
  factory or spec, not a contribution resource basket.
- Update `MassApplication` to pass specs, consume only
  `EmbeddedAdapterCreateResult.adapterIds()`, and call adapter-starter lifecycle
  methods by adapter id instead of `TransportAdapterContribution` and
  `EmbeddedAdapterHostSet`.
- Move or retire `WorkerTransportRuntimeFactory` and
  `DefaultWorkerTransportRuntimeFactory` as a separate registry assembly path.
  Worker registration resolution, pull-worker transport resolution, binding,
  mailbox, and adapter hint lookup must be owned internally by adapter-starter,
  not by embedded SDK assembling `TransportRuntimeRegistry` from binding
  snapshots.
- Delete `TransportAdapterBootstrap`, `TransportAdapterBootstrapContext`,
  `TransportAdapterContribution`, `EmbeddedAdapterContributionHost`, and
  `EmbeddedAdapterHostSet`.
- Delete old embedded adapter capability shells that only existed to feed the
  contribution path, including `AdapterMailboxCapabilities`,
  `AdapterIngressCapabilities`, and `AdapterMailboxClient`, unless a current
  code path proves a non-contribution owner for the symbol.
- Update architecture guards and adapter tests so they protect the runtime
  model instead of the contribution model.

Acceptance:

- `TransportRuntimeComposition` no longer creates concrete adapter bootstrap or
  runtime objects directly.
- `TransportConfig` no longer exposes `TransportAdapterBootstrap` setters or
  getters.
- Embedded SDK calls `create(specs)` with only `EmbeddedAdapterRuntimeSpec`
  values. Specs and spec options do not carry `EmbeddedAdapterRuntimeHost`,
  `AdapterSessionEvidencePublisher`, mailbox-key allocators, concrete session
  registries, queue clients, runtime executors, queue objects, or concrete
  adapter runtime objects.
- Starter construction may still receive shared queue, executor, evidence, and
  failure dependencies from SDK/starter assembly. This is construction wiring,
  not embedded SDK startup intent and not per-adapter runtime input.
- Embedded SDK passes `dispatchQueueKey` and `resultQueueKey` only as spec
  strings. It does not construct or pass the corresponding queue objects,
  stores, sinks, or pumps.
- Custom embedded adapter injection, if retained, creates
  `EmbeddedTransportAdapterRuntime` through the new runtime factory shape.
- Main code has no `TransportAdapterBootstrap`,
  `TransportAdapterBootstrapContext`, `TransportAdapterContribution`,
  `EmbeddedAdapterContributionHost`, or `EmbeddedAdapterHostSet`.
- Main code has no contribution-era adapter-facing capability shells for
  assigned dispatch/result ingress. In particular, `AdapterMailboxCapabilities`,
  `AdapterIngressCapabilities`, and `AdapterMailboxClient` are removed or
  explicitly reclassified with a current non-contribution owner.
- There is exactly one embedded adapter startup path:
  `embedded SDK specs -> adapter-starter create -> adapter-id start/close`.
- Adapter registration resolver state remains internal to adapter-starter;
  embedded SDK does not receive `TransportBinding` snapshots, adapter mailbox
  snapshots, or create `TransportRuntimeRegistry` from adapter bindings.
- `WorkerTransportRuntimeFactory` no longer acts as a second startup/registry
  assembly seam in embedded SDK. If any factory remains, it is internal to
  adapter-starter or a concrete adapter runtime factory.
- WebSocket, polling, and socket adapter runtimes each own their local
  protocol/session/final-hop resources and implement `start/close`.
- `MassApplication` no longer iterates `TransportAdapterBootstrap` or validates
  `TransportAdapterContribution`.
- Existing bundled adapter defaults still produce polling + enabled WebSocket +
  enabled socket runtimes as before.

Verification candidates:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime,transport/adapter-starter,transport/websocket-adapter,transport/polling-adapter,transport/socket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests compile
.\mvnw.cmd -q -pl transport/transport_runtime,transport/adapter-starter,transport/websocket-adapter,transport/polling-adapter,transport/socket-adapter -am test "-Dtest=TransportConvergenceArchitectureGuardTest,EmbeddedAdapterStarterTest,WebSocketAdapterRuntimeTest,PollingAdapterRuntimeTest,SocketAdapterRuntimeTest,AdapterCommandExecutorsTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -am test "-Dtest=MassApplicationStopOrderTest,MassApplicationDistributedTransportTest,TransportConfigTest,MassSdkTest" "-DtrimStackTrace=true"
rg -n "setPrimaryTransportAdapterBootstrap|addSupplementalTransportAdapterBootstrap|resolveTransportAdapterBootstraps|TransportAdapterBootstrap|TransportAdapterBootstrapContext|TransportAdapterContribution|EmbeddedAdapterHostSet|EmbeddedAdapterContributionHost|AdapterMailboxCapabilities|AdapterIngressCapabilities|AdapterMailboxClient|WorkerTransportRuntimeFactory" sdk transport --glob "*.java" --glob "!**/target/**"
```

`EmbeddedAdapterStarterTest`, `WebSocketAdapterRuntimeTest`,
`PollingAdapterRuntimeTest`, and `SocketAdapterRuntimeTest` do not exist today;
this slice must create the focused runtime tests it names.

## EAS-3 Docs, Guards, And Residue Removal

Goal: freeze the new owner boundary after the replacement is implemented.

Scope:

- Update `transport/AGENTS.md` and `transport/TRANSPORT_BOUNDARY_BASELINE.md`
  to describe embedded adapter runtimes and adapter-starter ownership.
- Update `sdk/xa-mass-embedded-sdk/README.md` if its adapter startup assembly
  description changes.
- Update `doc/PROOF_REGISTRY.md` with adapter runtime starter tests and guards.
- Add or update architecture guards:
  - `transport-runtime` must not depend on concrete adapter modules;
  - concrete adapters must not import SDK starter classes;
  - old bootstrap/contribution symbols must not reappear.
- Archive or update any active roadmap that still treats
  `TransportAdapterContribution` as current mainline.

Acceptance:

- Active owner docs describe the implemented runtime model, not the old
  contribution model.
- Residue scan has no old bootstrap/contribution mainline hits.
- Proof registry points at the current adapter runtime and SDK assembly proof.
- This roadmap can be marked complete only after EAS-1 through EAS-3
  acceptance are all satisfied.

Verification candidates:

```powershell
rg -n "TransportAdapterBootstrap|TransportAdapterBootstrapContext|TransportAdapterContribution|EmbeddedAdapterHostSet|EmbeddedAdapterContributionHost|AdapterMailboxCapabilities|AdapterIngressCapabilities|AdapterMailboxClient" transport sdk doc roadmap --glob "*.java" --glob "*.md" --glob "!**/target/**" --glob "!doc/archive/**"
.\mvnw.cmd -q -pl transport/transport_runtime,transport/adapter-starter,sdk/xa-mass-embedded-sdk -am test "-Dtest=TransportConvergenceArchitectureGuardTest,EmbeddedAdapterStarterTest,MassApplicationDistributedTransportTest" "-DtrimStackTrace=true"
git diff --check
```

## Roadmap Completion Criteria

This roadmap is complete only when:

- bundled embedded adapter startup is owned by `transport/adapter-starter`;
- concrete adapters are runtime owners with `start/close`;
- the old bootstrap/contribution resource-basket model is removed;
- docs and guards describe the implemented owner boundary;
- focused transport/adapter/SDK startup and stop-order tests pass.
