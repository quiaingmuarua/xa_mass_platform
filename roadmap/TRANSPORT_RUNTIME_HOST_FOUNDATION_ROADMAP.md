# Transport Runtime Host Foundation Roadmap

Status: proposed implementation roadmap.

Parent blueprint: `TRANSPORT_RUNTIME_HOST_AND_QUEUE_BRIDGE_BLUEPRINT.md`.

## Summary

This roadmap is the first executable child roadmap for the transport runtime
host blueprint. Its purpose is not to prove that a test shell can run. Its
purpose is to build a transport-runtime-owned closed-loop foundation that can
later replace the hand-written transport assembly in `embedded-sdk`.

Target:

- understand and document what `embedded-sdk` currently assembles for
  transport runtime;
- introduce a transport-runtime host/foundation shape that owns queue consumer
  loops, adapter contribution start/stop, result ingress resources, delivery
  failure resources, and cleanup;
- prove both memory and Redis queue carrier forms in `transport_runtime`;
- prove the host foundation can start real adapter contributions, not only fake
  command executors;
- keep SDK and engine production wiring unchanged until this foundation is
  proven.

The core implementation lives in `transport/transport_runtime`. Real adapter
proofs live in the owning adapter modules because `transport_runtime` must not
depend on concrete WebSocket/Socket/Polling adapters. The roadmap must preserve
enough SDK assembly context for the next migration roadmap to be mechanical.

## Current Code Observations

- `MassApplication` currently resolves `TransportDispatchHandoff`, creates
  `TransportAdapterBootstrapContext`, wires `EmbeddedAdapterHostSet`, starts
  and stops adapter hosts, wires result inbox pumps, wires delivery failure
  inbox pumps, and creates engine-side dispatch submitters.
- `TransportRuntimeComposition` currently snapshots transport config and
  resolves memory/Redis factories for dispatch handoff, result inbox, delivery
  failure inbox, endpoint lease, adapter bootstraps, and polling buffer.
- `TransportDispatchHandoff` currently combines producer `offer(...)` and
  consumer `poll(...)` views. Memory mode needs one shared in-process queue
  instance; Redis mode needs role endpoints that share one namespace/key
  contract.
- `AdapterMailboxConsumerLoop`, `EmbeddedAdapterContributionHost`, and
  `EmbeddedAdapterHostSet` already contain pieces of a transport-owned host,
  but SDK still composes them directly.
- Concrete adapters already contribute real runtime parts through
  `TransportAdapterBootstrap`: mailbox consumers, `ManagedTransportAdapter`
  resources, and `TransportServer` resources. Current examples include
  WebSocket, Socket, and Polling adapter bootstraps.
- `TransportAssignedDeliverySubmitter` already synchronously observes queue
  offer outcomes and forwards retryable offer failures to a delivery failure
  handler.
- `TransportDeliveryFailureEvidenceSink`,
  `RedisTransportDeliveryFailureChannel`, and
  `TransportDeliveryFailureInboxPump` already provide failure evidence pieces.
- `BufferedTransportResultIngressChannel`,
  `RedisTransportResultIngressChannel`, and
  `TransportResultIngressInboxPump` already provide result ingress pieces.

## Boundary Decision

This roadmap builds the host foundation inside `transport_runtime` first.

`embedded-sdk` is a reference caller and later migration target, not the first
production edit surface. The roadmap must produce a mapping from current SDK
assembly responsibilities to target transport-runtime host responsibilities,
then prove the target shape in transport-runtime module tests and adapter-owned
integration tests.

Target shape for this roadmap:

```text
producer role
  -> queue offer endpoint
  -> queue carrier(memory instance or Redis namespace)
  -> consumer poll endpoint
  -> transport-runtime host
  -> adapter mailbox consumer loop
  -> real adapter command executor
  -> final-hop DispatchOutcome
  -> failure evidence sink when retryable

adapter result ingress
  -> result ingress endpoint
  -> result carrier(memory buffer or Redis inbox)
  -> result drain/pump boundary
```

Queue offer failure is synchronous. Final-hop failure evidence is asynchronous.
Accepted work that later produces no failure evidence or result is still an
engine attempt-timeout concern.

## Non-Goals

- Do not migrate `MassApplication` production wiring in this roadmap.
- Do not change engine dispatch contracts.
- Do not change concrete WebSocket/Socket/Polling protocol behavior. Adapter
  production wiring may change only when required to consume the new host
  foundation contract.
- Do not remove endpoint lease here; classify it as assembly residue or
  separate prerequisite.
- Do not split `QueueOfferResult` unless implementation proves
  `DispatchOutcome` is blocking the foundation. If split, keep it limited to
  queue offer semantics.
- Do not introduce adapter restart/reconcile/worker lifecycle monitors.
- Do not create pass-through facade classes that only rename existing calls.

## Milestones

### TRHF-0 - Embedded Assembly Inventory

Goal: make the current SDK assembly understandable before replacing it.

Scope:

- Add an inventory section to this roadmap or a sibling inventory file covering
  current `MassApplication` and `TransportRuntimeComposition` responsibilities.
- Classify each responsibility as one of:
  - engine dispatch bridge;
  - queue carrier factory/config;
  - transport host lifecycle;
  - adapter bootstrap context;
  - adapter host start/stop;
  - result ingress bridge;
  - delivery failure bridge;
  - endpoint lease residue;
  - SDK public configuration surface.
- For each item, name the target owner: stay in SDK bridge, move to
  transport-runtime host, move to a queue endpoint factory, or remove later.

Acceptance:

- A reader who does not know embedded-sdk can understand what SDK currently
  does for transport and which existing code path should be copied, moved, or
  deleted later.
- No production code changes are required in this milestone.

### TRHF-1 - Host Foundation Shape

Goal: introduce or clarify the transport-runtime host foundation that will own
consumer loops and cleanup.

Scope:

- Use existing `AdapterMailboxConsumerLoop`, `EmbeddedAdapterContributionHost`,
  and `EmbeddedAdapterHostSet` where they already express the target.
- Add a host/foundation object only if it removes SDK hand assembly or gives a
  real lifecycle boundary.
- The host foundation should accept role-specific queue endpoints and adapter
  contributions, start mailbox consumers, stop them cleanly, and surface
  runtime-local diagnostics only as aggregate state.
- Keep producer offer endpoint separate from consumer poll endpoint at the
  contract level, even if memory mode uses one object underneath.

Acceptance:

- The host foundation can be constructed in a transport-runtime test without
  engine, SDK, worker-runtime, or concrete adapters.
- Stop is deterministic and cleans up futures/threads/resources.
- The implementation does not add adapter restart or cross-module lifecycle
  policy.

### TRHF-2 - Memory Closed Loop

Goal: prove the full same-JVM memory form.

Scope:

- Use `InMemoryTransportDispatchHandoff` as the queue carrier.
- Use `TransportAssignedDeliverySubmitter` or the producer endpoint to offer
  assigned dispatch work.
- Start the host foundation with a fake adapter command executor for the
  transport-runtime-only proof.
- Prove:
  - queue offer success is synchronous;
  - queue offer backpressure/unavailable remains synchronously visible;
  - consumer loop polls the same memory carrier;
  - fake final-hop receives the correct `selectedWorkerId`;
  - retryable final-hop failure emits delivery failure evidence;
  - result ingress can be accepted through the memory result carrier boundary;
  - stop cleans up without process shutdown.

Acceptance:

- One module-local integration test proves dispatch, failure evidence, and
  result ingress boundaries in the memory form.
- The proof fails if producer and consumer accidentally use different queue
  instances.

### TRHF-3 - Redis Closed Loop

Goal: prove the split-host-compatible Redis form.

Scope:

- Use `RedisTransportDispatchHandoff` as independent producer and consumer
  endpoints sharing one namespace/key contract.
- Use `RedisTransportResultIngressChannel` and
  `RedisTransportDeliveryFailureChannel` to prove result and failure carrier
  behavior.
- Reuse the same fake adapter executor shape from the memory proof.
- Redis tests may use existing repo convention for local Redis availability,
  but when Redis is available they must prove a real cross-instance round trip.

Acceptance:

- Redis proof uses separate producer/consumer objects, not one shared Java
  instance.
- Queue offer outcomes are synchronous.
- Consumer host drains dispatch items from Redis and emits final-hop failure
  evidence through the Redis failure channel.
- Result ingress can round-trip through the Redis result channel without
  transport parsing task payloads.

### TRHF-4 - Real Adapter Host Proof

Goal: prove the host foundation works with real adapter contributions.

Scope:

- Add adapter-owned integration tests that use real
  `TransportAdapterBootstrap` implementations and the host foundation.
- Cover at least:
  - Polling adapter, because it proves pending pull-buffer delivery through the
    adapter contribution path;
  - one push adapter, preferably WebSocket first, because it proves a real
    `TransportServer` / session registry / final-hop send path.
- Socket can follow in the same roadmap if the WebSocket shape generalizes
  cleanly; otherwise record it as a parity follow-up.
- The tests should wire memory queue first. Redis adapter proof is optional in
  this roadmap if TRHF-3 already proves Redis carrier behavior at the
  transport-runtime level.

Acceptance:

- Real adapter contribution start/stop is driven by the host foundation, not by
  hand-created consumer loops in the test.
- A real adapter receives assigned dispatch from the queue and returns the
  expected `DispatchOutcome`.
- No engine, TaskWorkRuntime, worker-runtime candidate/admission, or SDK
  `MassApplication` is required for the adapter proof.
- Adapter tests prove cleanup of adapter server/managed resources/consumer loop
  through the host foundation.

### TRHF-5 - SDK Migration Map

Goal: make the next SDK migration roadmap mechanical.

Scope:

- Update this roadmap after TRHF-1/2/3/4 with a precise mapping:
  - what `MassApplication` should stop constructing;
  - what transport-runtime host factory/resource it should call instead;
  - what remains in SDK as engine bridge wiring;
  - what remains as public config convenience;
  - what becomes endpoint lease cleanup or a separate roadmap.
- Do not perform the SDK migration in this roadmap unless explicitly approved
  after the transport-runtime proofs land.

Acceptance:

- The next roadmap can say "replace SDK assembly with the proven host
  foundation" without rediscovering current assembly responsibilities.

## Verification

Compile:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime -am -DskipTests compile
```

Focused transport-runtime proof:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime test "-Dtest=TransportRuntimeHostFoundationTest,InMemoryTransportDispatchHandoffTest,RedisTransportDispatchHandoffTest,RedisTransportResultIngressChannelTest,RedisTransportDeliveryFailureChannelTest,AdapterMailboxConsumerLoopTest,TransportAssignedDeliverySubmitterTest,TransportConvergenceArchitectureGuardTest" "-DtrimStackTrace=true"
```

Real adapter proof:

```powershell
.\mvnw.cmd -q -pl transport/transport_runtime,transport/polling-adapter,transport/websocket-adapter -am test "-Dtest=TransportRuntimeHostFoundationTest,PollingTransportAdapterBootstrapTest,PollingDeliveryExecutorTest,WebSocketTransportAdapterBootstrapTest" "-DtrimStackTrace=true"
```

Scope guard:

```powershell
git diff --name-only -- sdk xa-mass-engine xa-mass-worker-runtime xa-mass-server
```

Expected before an approved SDK migration slice: no production file changes
outside `transport/transport_runtime`, concrete adapter modules needed for real
adapter host proof, and this roadmap/inventory.

## Completion Criteria

This roadmap is complete when:

- current embedded-sdk transport assembly is inventoried and mapped to target
  owners;
- transport-runtime host foundation can start, consume, emit failure evidence,
  accept result ingress, and stop in memory form;
- Redis form proves separate producer/consumer endpoint behavior using shared
  namespace/key contract;
- at least polling plus one push adapter prove real adapter contribution
  start/stop and final-hop delivery through the host foundation;
- queue offer and final-hop failure evidence remain distinct;
- a follow-up SDK migration roadmap has enough information to remove
  hand-written assembly from `MassApplication` without rediscovering the
  transport path.
