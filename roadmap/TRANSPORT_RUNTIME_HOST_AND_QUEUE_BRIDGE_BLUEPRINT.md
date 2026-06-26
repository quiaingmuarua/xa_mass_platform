# Transport Runtime Host And Queue Bridge Blueprint

Status: proposed umbrella roadmap.

This roadmap tracks the full transport-runtime host and queue-bridge
convergence goal. It is not a single implementation slice. The current
transport-runtime split touches runtime lifecycle ownership, queue carrier
semantics, embedded SDK assembly, adapter bootstrap, and proof surfaces, so the
goal should land through independently verifiable milestones rather than one
wide code pass.

## Summary

Target direction:

- `transport_runtime` becomes independently bootstrappable as a host that can
  run in the same JVM thread/process as the engine or as a separate
  transport-consumer runtime. Embedded SDK may start that host in embedded
  mode, but it should not own the host internals.
- The engine side and transport side communicate through assigned-delivery and
  result/failure queue carriers. Memory queue is for same-JVM independent
  thread/test mode. Redis queue is for split-process or split-host mode.
- Queue carriers are role endpoints, not SDK-owned shared services. The
  producer side owns an `offer(...)` endpoint and the consumer side owns a
  `poll(...)` endpoint. Same-JVM memory mode needs those endpoints backed by
  the same in-process queue instance; Redis mode needs both endpoints pointed
  at the same Redis namespace/key contract.
- Queue offer is synchronously observable. `offer(...)` must return
  accepted, backpressure, unavailable, invalid, or shutdown evidence before the
  producer treats work as admitted.
- Final-hop delivery is best-effort after queue offer acceptance. Known adapter
  dispatch failures must emit failure evidence; accepted work that later
  produces no worker consumption, failure evidence, or result remains an
  engine-owned attempt timeout, retry, reassign, or compensation concern.
- `embedded-sdk` stops being the transport runtime assembly owner. It may keep
  narrow bridge wiring from engine contracts to queue carriers and from result
  carriers back into engine result ingest, but adapter loops, host lifecycle,
  mailbox consumer wiring, and transport health checks belong to
  `transport_runtime`.
- Health and diagnostic checks stay runtime-local unless a separate owner
  contract proves why another module needs them.

The important design rule is that queue bridges are protocol/lifecycle seams,
not same-module pass-through wrappers. If a new type does not change who owns
the lifecycle, who can call it, or what process/thread boundary it protects, it
should not be added.

## Current Code Observations

- `transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDispatchHandoff.java`
  is already the producer/consumer handoff for assigned dispatch batches. Its
  current methods are `offer(AdapterMailboxDispatchBatch)`,
  `poll(adapterMailboxKey, maxItems, timeoutMillis)`, and `shutdown()`.
- The current handoff type combines producer and consumer views. This explains
  why same-JVM memory assembly currently needs one shared object, but the target
  boundary should expose role-specific producer/consumer endpoints instead of
  making SDK assembly hand-roll the queue object.
- `transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportAssignedDeliverySubmitter.java`
  already treats handoff offer failures as retryable `DispatchOutcome`
  evidence and delegates retryable failures to a transport failure handler.
- `transport/transport_runtime` already owns memory and Redis handoff
  implementations, result ingress carriers, Redis result inbox pumping, and
  Redis delivery failure inbox pumping.
- Adapter bootstrap and adapter-owned mailbox consumer capabilities are already
  centered in `transport_runtime` through `TransportAdapterBootstrapContext`,
  `TransportAdapterContribution`, and `EmbeddedAdapterHostSet`.
- `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java`
  currently still assembles transport runtime pieces directly: runtime role
  branching, handoff creation, endpoint lease store creation, adapter bootstrap
  context creation, result/failure inbox pumps, and dispatch listener creation.
- `sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/config/TransportRuntimeComposition.java`
  currently owns transport runtime configuration snapshotting and default
  memory/Redis factory selection, including runtime role, dispatch handoff,
  endpoint lease store, adapter bootstrap list, polling buffer, and Redis inbox
  factories.
- `doc/PROOF_REGISTRY.md` currently records that assigned dispatch messages are
  minimal delivery intents, physical handoff target is the
  `adapterMailboxKey`, transport delivery does not own retry/reassign/final
  recovery, and `MassApplication` should not hand-roll mailbox availability or
  command-drain loops.

## Owner Review

- Engine owns task attempt lifecycle, retry, timeout, reassign, compensation,
  and final task state. It produces assigned-delivery work only after scheduling
  has selected a concrete worker.
- Worker runtime owns worker lifecycle, admission, reachability evidence, and
  scheduling-facing availability. Transport session facts must not become
  worker eligibility truth.
- Transport runtime owns assigned-delivery queue offer semantics, queue
  consumption, adapter mailbox consumer loops, adapter-local session evidence
  production, final-hop adapter execution, result ingress carriers, failure
  evidence carriers, host lifecycle internals, and runtime-local diagnostics.
  It should not make a global endpoint lease store the worker reachability
  truth.
- Embedded SDK owns Java application integration and engine bridge wiring. It
  may start or stop the transport runtime host in embedded mode, but should not
  own transport runtime host lifecycle internals, adapter command drain loops,
  mailbox consumer availability, or split runtime role branching.
- Redis and memory queue implementations are infra choices for the same runtime
  protocol. They must not fork transport business logic.

## Boundary Decision

Create a transport-owned runtime host boundary.

Target shape:

```text
engine runtime
  -> sdk engine bridge
  -> assigned-delivery producer endpoint
  -> queue carrier (memory instance or Redis namespace)
  -> transport-runtime host consumer endpoint
  -> adapter mailbox consumer
  -> concrete adapter final hop
  -> worker

worker result
  -> adapter ingress
  -> result producer endpoint
  -> result queue carrier (memory instance or Redis namespace)
  -> result consumer endpoint
  -> sdk engine bridge
  -> engine result ingest
```

The bridge boundary is queue-carrier based, not callback-confirmation based.
Queue offer failure is a synchronous result of the producer-side
handoff call. Transport may synchronously return offer rejection, unavailable
queue, invalid input, backpressure, or shutdown evidence. After admission,
transport may asynchronously publish unavailable mailbox, missing endpoint,
final-hop failure, or diagnostic failure evidence. It does not need a
cross-module synchronous acknowledgement for worker receipt or execution of
accepted work.

`DispatchOutcome` can remain the assigned-delivery outcome evidence only if it
does not force engine confirmation semantics. Queue offer and final-hop
dispatch are different facts; if the model is split, use a narrow
`QueueOfferResult` or equivalent runtime-local type for `offer(...)`, while
`DispatchOutcome` remains final-hop delivery evidence. Keeping
`DispatchOutcome` for both should be treated as transitional residue, not a
target model.

## Why This Needs Milestones

This can be tracked as one umbrella roadmap, but one implementation pass would
mix at least four different owner changes:

1. Runtime host ownership: moving lifecycle, mailbox consumer loops, queue
   bridge resources, and cleanup handles out of `MassApplication` hand-written
   assembly into `transport_runtime`.
2. Queue bridge contract: making memory and Redis carriers provide the same
   async semantics without introducing transport-specific kernel facts.
3. Embedded SDK slimming: keeping only engine-to-transport and
   transport-to-engine bridge wiring in SDK assembly.
4. Proof and diagnostics: proving independent thread/process behavior without
   adding cross-module health/reconcile loops.

Those changes should be sequenced inside this roadmap. The first executable
slice should prove a small transport-runtime host path before moving broad SDK
assembly. Detailed implementation slices can live in narrower roadmaps that
reference this blueprint.

## Non-Goals

- Do not make transport select workers or reopen scheduling decisions.
- Do not make endpoint lease, adapter id, connection id, route key, session id,
  or mailbox key part of engine scheduling truth.
- Do not add a cross-module worker-receipt or execution confirmation
  requirement for accepted delivery.
- Do not move worker-runtime health or admission decisions into transport.
- Do not add adapter health monitor/restart/reconcile loops to command drain or
  final-hop paths.
- Do not preserve old and new assembly paths as two long-lived runtime tracks.
- Do not move Java callback/bootstrap APIs into `transport_api` unless a real
  external adapter protocol needs that stable public surface.

## Do Not Start With

- A broad rename of every `embedded` symbol.
- A generic `Bridge` or `Facade` layer that only forwards within the same
  module.
- A new central health service that scans transport, engine, and worker runtime
  state.
- A Redis-only design that makes memory independent-thread tests second class.
- A new message envelope carrying task shell metadata, adapter/session facts,
  or worker-runtime lifecycle facts.
- Server or Spring assembly changes before transport-runtime host behavior has
  a focused proof surface.
- Describing queue offer rejection as an unobservable best-effort failure.

## Roadmap Milestones

### TRHB-0 - Inventory And Slice Boundaries

Goal: classify current runtime assembly responsibilities before moving code.

Scope:

- Inventory `MassApplication`, `TransportRuntimeComposition`,
  `MassApplicationBuilder`, `MassSdk`, and transport runtime host/support
  support classes.
- Classify each responsibility as engine bridge, transport host, embedded
  bootstrap, adapter bootstrap, queue carrier, result bridge, failure
  diagnostics, or SDK user API.
- Classify queue offer outcomes separately from final-hop dispatch outcomes.
- Classify endpoint lease store usage as current residue unless a separate
  owner decision keeps it.
- Decide which existing role names are still accurate and which should move to
  transport-owned host config.

Acceptance:

- A small inventory section or roadmap update lists each current caller and its
  target owner.
- No production code movement yet, unless the inventory finds a trivial
  transport-runtime-only extraction with existing tests.

### TRHB-1 - Transport Runtime Host

Goal: introduce a transport-owned runtime host boundary that can be started and
stopped without SDK hand-rolling mailbox consumer loops or queue bridge
cleanup.

Scope:

- Add a transport-owned host object for runtime executor usage, adapter
  bootstrap contexts, adapter contribution set, mailbox consumer availability,
  role-specific queue endpoints, failure evidence sinks, and start/stop
  cleanup.
- Embedded SDK may call host start/stop in embedded mode, but does not own host
  internals.
- Do not cement endpoint lease store as part of the new host. If endpoint lease
  removal is not done first, keep it explicitly marked as legacy assembly
  residue during this milestone.
- Keep the existing adapter contribution contracts narrow.
- Allow same-JVM independent-thread queue consumption without requiring engine
  callbacks.
- Make `MassApplication` call the transport host instead of
  constructing adapter bootstrap contexts and consumer sets by hand.

Out of scope:

- Redis bridge semantic changes.
- Public SDK API reshaping.
- Server Boot profile changes.

Proof:

- Transport runtime unit tests show the host starts/stops adapter
  contributions and drains assigned mailbox work through memory handoff.
- Existing guard tests continue proving concrete adapters do not receive broad
  runtime owner getters.

### TRHB-2 - Queue Bridge Parity

Goal: make memory and Redis queues two implementations of the same async
assigned-delivery and result-ingress bridge behavior.

Scope:

- Define the narrow bridge contracts needed by engine producer and
  transport consumer roles.
- Split the conceptual queue carrier surface into producer `offer(...)` and
  consumer `poll(...)` views. Memory mode may still implement both views with
  one in-process object; Redis mode may create independent endpoint clients
  sharing one namespace.
- Make queue offer synchronous and explicit: accepted, backpressure,
  unavailable, invalid, and shutdown are producer-visible offer outcomes.
- Keep assigned-delivery batches keyed by `adapterMailboxKey` and item
  correctness constrained by `selectedWorkerId`.
- Keep `ResultIngressEntry` opaque to transport and decode task result payloads
  only in the starter/engine bridge.
- Decide whether queue offer needs a name separate from `DispatchOutcome`.

Out of scope:

- Exactly-once delivery.
- Cross-module confirmation for accepted items.
- Adapter/session facts in queue messages.

Proof:

- Memory and Redis handoff tests cover identical offer, poll, shutdown, capacity
  and corrupt-input behavior.
- Producer-side tests prove queue offer rejection is observed synchronously and
  routed to failure/compensation evidence when retryable.
- Result ingress tests cover memory/local buffered and Redis inbox behavior
  without transport parsing task result payloads.

### TRHB-3 - Embedded SDK Slimming

Goal: reduce embedded SDK responsibility to Java integration and bridge wiring.

Scope:

- Move runtime role branching and transport host assembly out of
  `MassApplication`.
- Keep SDK builder APIs only where they express application choices such as
  memory vs Redis configuration, adapter bootstrap list, and engine result
  bridge. SDK APIs should not expose or require queue implementation objects.
- Replace SDK-owned transport loops with transport-runtime host start/stop
  calls.
- Keep engine bridge code narrow: `TaskDispatchBinding` to
  `AdapterMailboxDispatchBatch`, and `ResultIngressEntry` to engine result
  ingest.

Out of scope:

- New public worker SDK behavior.
- Server control-plane schema or API changes.
- Compatibility aliases for old internal assembly paths.

Proof:

- SDK tests prove embedded, engine-producer, and transport-consumer modes still
  assemble.
- Architecture guards prevent `MassApplication` from hand-rolling mailbox
  availability or adapter command drain loops.

### TRHB-4 - Health And Failure Semantics

Goal: keep health and failure evidence useful without making it a cross-module
coordination contract.

Scope:

- Define runtime-local diagnostics for host started/stopped state, queue
  poll loop liveness, mailbox consumer availability, and inbox pump errors.
- Keep known delivery failure evidence visible. Queue admission failures are
  synchronous producer outcomes; final-hop failures are asynchronous evidence.
  Neither becomes a required worker-execution acknowledgement path.
- Make accepted-but-never-consumed delivery explicitly fall back to
  engine-owned attempt timeout/retry behavior.

Out of scope:

- A global health service.
- Transport-driven worker lifecycle mutation.
- Transport-driven dispatch wakeup/recheck.

Proof:

- Tests cover runtime-local health state and failure evidence production.
- Existing worker-runtime tests remain the proof for worker admission and
  reachability decisions.

### TRHB-5 - Residue Cleanup And Owner Docs

Goal: remove old assembly residue after the executable roadmaps land.

Scope:

- Remove stale runtime role branching from embedded SDK after host
  ownership is proven.
- Update `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  SDK README/boundary docs, and `doc/PROOF_REGISTRY.md` only for implemented
  facts.
- Run residue scans for old SDK-owned transport assembly vocabulary.

Acceptance:

- No old and new assembly tracks remain live.
- Docs describe current code, not target direction.
- Proof registry rows point to focused tests for the new host and
  queue bridge.

## Suggested Implementation Order

1. Execute TRHB-0 as a read-only inventory and turn the result into one narrow
   executable slice.
2. Build TRHB-1 with memory queue only, proving independent same-JVM
   transport-runtime host queue consumption and cleanup.
3. Add TRHB-2 Redis parity only after memory host behavior is stable.
4. Run TRHB-3 SDK slimming after the transport-owned host is already proven.
5. Add TRHB-4 diagnostics after core host and queue semantics are stable.
6. Finish with TRHB-5 residue cleanup and doc/proof convergence.

## Verification Candidates

Transport runtime host and handoff:

```powershell
./mvnw -q -pl transport/transport_runtime test -Dtest=TransportRuntimeRegistryTest,TransportRegistrationResolverTest,InMemoryTransportDispatchHandoffTest,RedisTransportDispatchHandoffTest,BufferedTransportResultIngressChannelTest,RedisTransportResultIngressChannelTest,AdapterMailboxConsumerLoopTest,EmbeddedAdapterHostSetTest,TransportConvergenceArchitectureGuardTest
```

Adapter bootstrap and final-hop paths:

```powershell
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter test -Dtest=WebSocketTransportAdapterBootstrapTest,SocketTransportAdapterBootstrapTest,PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,PollingSessionEvidenceDriverTest,DispatcherInboundHandlerTest,SocketTransportServerTest
```

Embedded SDK bridge and distributed role assembly:

```powershell
./mvnw -q -pl sdk/xa-mass-embedded-sdk -am test "-Dtest=MassApplicationDistributedTransportTest,MassSdkTest,RuntimeTaskResultIngestChannelTest,TaskDispatchRoutingSubmitterTest,EmbeddedPullWorkerSessionTest" -Dsurefire.failIfNoSpecifiedTests=false
```

If a later slice touches Spring/server runtime assembly, add a startup-level
Spring context or Boot-shell proof with the relevant profile active. Do not
treat constructor-only tests as sufficient proof for server profile changes.

## Open Decisions

- Whether the bridge contracts remain in `transport_runtime` or move to
  `transport_api` only when an external adapter/process protocol requires that
  stable public surface.
- Whether to split queue offer outcomes into `QueueOfferResult` in TRHB-2,
  or keep `DispatchOutcome` temporarily while guarding that queue offer and
  final-hop dispatch remain separate facts.
- Whether delivery failure inbox remains an optional diagnostic/compensation
  bridge or becomes purely runtime-local diagnostics.
- Whether endpoint lease store should be removed before TRHB-1 or carried as a
  short-lived legacy assembly residue while the host boundary is
  introduced.
- Whether `TransportRuntimeRole` should move immediately into a
  transport-owned host config or first be wrapped by a temporary SDK snapshot
  during TRHB-1.
- Whether Redis queue configuration is passed through SDK builder convenience
  APIs into transport-runtime endpoint factories, and which layer owns the
  defaults.

## Owner Decisions To Confirm Before Execution

- Queue result vocabulary: prefer `QueueOfferResult` for producer-side
  `offer(...)`; confirm whether to split it in the first queue bridge slice.
- Endpoint lease: prefer removing global endpoint lease store from the target
  host and keeping only adapter-local session currentness plus negative
  session-disconnect evidence; confirm whether to make that a
  prerequisite roadmap or fold it into TRHB-1.
- Failure inbox owner: confirm whether Redis delivery failure inbox is
  engine-compensation input or transport-local diagnostics with optional drain.
- Runtime role config owner: confirm whether role/default Redis-memory endpoint
  factory selection moves directly into `transport_runtime` host
  config while embedded SDK remains the bootstrap caller.
- Diagnostics surface: confirm diagnostics remain runtime-local aggregate
  state, with no cross-module session/worker inventory or reconcile loop.

## Completion Criteria

This roadmap is complete when the milestones have landed, old SDK-owned
transport assembly residue is removed, queue offer and final-hop dispatch
failure semantics are separately proven, and proof shows an independently
bootstrapped `transport_runtime` host can use memory and Redis queue bridges
without changing engine scheduling semantics, worker-runtime ownership, or
public task lifecycle contracts.
