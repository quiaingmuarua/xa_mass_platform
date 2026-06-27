# Transport Best-Effort Executor And Runtime Handoff Convergence Roadmap

Status: complete; archived after implementation on 2026-06-27.

## Summary

This roadmap converges transport around one boundary:

```text
transport = best-effort assigned-delivery executor
```

Transport should execute already assigned delivery work, expose bounded queue
handoff and final-hop outcomes, carry opaque result ingress entries, and publish
transport-local evidence. It must not become a second task/result lifecycle
owner. Result reliability, duplicate handling, late-result handling, attempt
timeout, retry, reassign, and final convergence stay with engine-owned result
and task runtime code.

The filename still says assigned-delivery because this roadmap started from the
dispatch lane. Its actual scope is the transport best-effort executor boundary:

- assigned dispatch handoff and adapter final-hop delivery;
- result ingress as an opaque best-effort relay, not a reliable transport-owned
  result ingress queue.

The result-ingress slice is intentionally recorded here because it protects the
same owner invariant: transport must not become a reliability/lifecycle owner.
It is not required to complete the dispatch queue primitive convergence slices.

This replaces the earlier transport host/foundation direction. The target is
not a standalone `transport-runtime` thread or process. Adapter runtimes may own
their own local consumer loops and protocol resources, while SDK/starter
assembly wires the selected memory or Redis queue backends.

## Current Code Observations

- `TransportDispatchHandoff` is already the transport-owned assigned-dispatch
  handoff contract: producers `offer(AdapterMailboxDispatchBatch)` and adapter
  consumers destructively `poll(adapterMailboxKey, maxItems, timeoutMillis)`.
- `InMemoryTransportDispatchHandoff` delegates ready-command storage to the
  shared `String`/`String` `InMemoryKeyedBlockingQueueStore`; transport still
  owns dispatch codecs, `DispatchOutcome` mapping, mailbox consumer evidence,
  and corrupt-item handling.
- `RedisTransportDispatchHandoff` delegates ready-command storage to the shared
  `String`/`String` `RedisKeyedBlockingQueueStore`; transport still owns
  mailbox consumer availability evidence, dispatch codecs, corrupt-item
  handling, and delivery outcome mapping.
- `platform_infra/mass-queue-primitives` owns keyed bounded offer,
  blocking/destructive poll or drain, shutdown, and `size(String key)` point
  read mechanics. The main queue API is no longer generic and no longer
  exposes broad `snapshot()` inventory.
- `platform_infra/mass-runtime-redis` provides the matching
  `RedisKeyedBlockingQueueStore` for opaque string values. It uses lightweight
  capacity checks and Redis list operations; it does not own transport
  `DispatchMessage`, `DispatchOutcome`, mailbox availability, adapter
  final-hop semantics, active queue inventories, or queue snapshots.
- `AdapterMailboxConsumerLoop` is already the local embedded adapter-consumer
  shape: it polls one adapter mailbox, invokes `AdapterCommandExecutor`, and
  emits retryable failure evidence for known final-hop failures.
- `TransportAdapterBootstrapContext` already exposes narrow adapter-facing
  mailbox, result ingress, session evidence, and host-resource capabilities.
- `MassApplication` still assembles backend selection, transport handoff,
  adapter bootstrap context, adapter host resources, result ingress channels,
  inbox pumps, and engine dispatch submitters in one embedded SDK entry point.
- `BufferedTransportResultIngressChannel` and
  `RedisTransportResultIngressChannel` now treat result ingress as a
  best-effort queue. Redis result ingress maintains only a ready list with
  bounded offer and destructive poll; it has no transport-owned
  ready/inflight/complete/visibility lifecycle.
- `TransportResultIngressQueuePump` consumes plain `ResultIngressEntry` values
  and calls the void `TransportResultIngressHandler` sink; handler output does
  not drive transport retry, ack, reclaim, or visibility behavior.
- `RuntimeTaskResultIngestChannel` is the starter/engine-side consumer that
  decodes opaque result entries, validates result correlation and attempt or
  lease identity, and then mutates engine-owned result runtime through
  `TaskResultIngestFacade`.

## Owner Review

Engine owns:

- worker selection and assignment;
- task attempt lifecycle, attempt timeout, retry, reassign, compensation, and
  final recovery;
- result apply, result idempotency, duplicate and late-result classification,
  and stable public result read truth.

Transport owns:

- `AdapterMailboxDispatchBatch`, `DispatchMessage`, `TransportDispatchHandoff`,
  `DispatchOutcome`, and delivery-failure evidence;
- mailbox-scoped bounded queue admission and destructive poll semantics;
- adapter endpoint/session evidence and mailbox consumer availability evidence;
- opaque `ResultIngressEntry` carrier construction, buffering, enqueueing, and
  diagnostics.

Concrete adapters own:

- protocol server/client I/O;
- local session indexes and selected-worker final-hop sends;
- adapter-owned mailbox consumer runtime handles;
- protocol frame parsing into transport result-ingress entries.

Platform infra owns:

- memory and Redis `String` keyed queue mechanics;
- runtime-state primitives that do not import transport DTOs or interpret
  task, worker, adapter, or result lifecycle semantics.

Embedded SDK / starter owns:

- runtime assembly and backend selection;
- wiring engine dispatch output to transport handoff;
- wiring adapter result ingress to starter/engine result ingest;
- start/stop ordering for configured runtime pieces.

## Boundary Decision

Assigned dispatch and result ingress are both runtime-state handoff concerns,
but only dispatch needs transport delivery semantics.

Dispatch handoff remains transport-owned because known offer rejection,
unavailable mailbox, invalid dispatch input, backpressure, shutdown, and
adapter final-hop failures must become `DispatchOutcome` or retryable failure
evidence. Accepted work with no later worker consumption or result remains an
engine attempt-timeout concern.

Backpressure is a best-effort admission guard, not a strict correctness
boundary. Redis dispatch may do a lightweight capacity check, such as a point
`LLEN` before offer, but it does not need Lua-level atomicity for exact
`LLEN < max` enforcement. Slight over-admission under race is acceptable because
transport is not the reliability owner and engine attempt timeout remains the
recovery boundary.

Transport internal queues should not require Lua to maintain correctness.
Memory-backed and Redis-backed dispatch queues must share the same behavioral
contract tests: bounded offer, unavailable mailbox rejection, destructive poll,
shutdown, corrupt-item handling, and `DispatchOutcome` mapping. Redis may use
ordinary list/hash commands for implementation convenience. Any new or retained
Lua script in the dispatch path is an exception and must document:

- the production invariant it protects;
- the failure mode without Lua;
- why ordinary Redis commands plus best-effort semantics are insufficient;
- why the invariant is high ROI enough for transport to carry script
  maintenance.

Result ingress should be best-effort from transport's perspective. Transport
may enqueue and relay `ResultIngressEntry`, but it should not maintain
ready/inflight/complete/visibility lifecycle for result application. If the
engine process is unavailable after a result is handed off, retry/reassign
protection belongs to engine-owned attempt timeout and result convergence, not
to transport-owned result ingress queue claims.

That means the current `TransportResultIngressOutcome` / ackable inbox contract
is part of the result-ingress convergence surface, not a stable transport API.
The result handler may keep starter/engine-local classification internally, but
transport queue code must not use handler output to decide ack, retry, reclaim,
or visibility lifecycle.

Future reliable result delivery, if ever required, must start with an
infra-owned ack/visibility queue primitive plus an explicit engine owner
decision. It must not remain a private transport-runtime Redis inbox lifecycle.

## Target Shape

```text
dispatch path
  engine assignment
    -> starter builds AdapterMailboxDispatchBatch
    -> TransportDispatchHandoff.offer(...)
    -> infra keyed queue backend (memory or Redis)
    -> adapter-owned consumer poll(adapterMailboxKey)
    -> AdapterCommandExecutor.dispatch(...)
    -> DispatchOutcome / retryable delivery-failure evidence

result path
  worker protocol result
    -> concrete adapter parses protocol frame
    -> AdapterResultIngressEntries.from(...)
    -> best-effort result ingress offer
    -> engine-side result consumer poll/drain
    -> RuntimeTaskResultIngestChannel
    -> engine-owned result convergence
```

The same transport semantic contract should work with memory and Redis queue
backends. Infra supplies storage mechanics; transport supplies delivery meaning;
SDK/starter wires the chosen backend.

The dispatch hot path should stay simple:

```text
offer(adapterMailboxKey, item)
  -> best-effort capacity/readiness check
  -> enqueue item
  -> DispatchOutcome

poll(adapterMailboxKey, maxItems)
  -> pop up to maxItems
  -> adapter final-hop
```

Transport dispatch should not require Lua-maintained global counters, active
queue sets, oldest-item metadata, enqueue/drain stats, or exact capacity
transactions. Queue length and operator diagnostics should be read through
point reads or a separate diagnostics path, not maintained as hot-path truth.
The runtime queue primitive should not expose a broad `snapshot()` API. If
queue size is needed, expose only a narrow `size(String key)` point read; do
not expose all keys, active queue inventories, oldest age, or aggregate stats
from the main queue contract.

## Non-Goals

- Do not introduce a standalone `transport-runtime` process or thread as the
  primary target of this roadmap.
- Do not move `DispatchMessage`, `DispatchOutcome`,
  `AdapterMailboxDispatchBatch`, or `TransportDispatchHandoff` into infra.
- Do not make infra understand adapter mailbox availability, selected workers,
  task attempts, result correlation, or retry semantics.
- Do not make transport parse task result payloads, enforce attempt lifecycle,
  or own result reliability after engine process failure.
- Do not add adapter health supervision, restart, takeover, migration, or
  reconciliation loops to adapter consumer code.
- Do not add or preserve Lua-backed dispatch correctness, stats, or metadata
  maintenance unless the roadmap names the invariant, failure mode, and high
  ROI justification. Capacity checks may be approximate; monitoring should read
  queue state out of band.
- Do not keep reliable and best-effort result ingress implementations as two
  live production semantics after convergence.
- Do not preserve old internal compatibility wrappers once callers move.

## Do Not Start With

Do not start by adding a new transport host, facade, bridge, or standalone
runtime shell. That repeats the wrong abstraction. Start by proving that the
existing transport contracts can reuse infra queue primitives while preserving
transport-owned `DispatchOutcome` semantics.

Do not start by deleting result ingress code before engine/starter assembly has
a plain best-effort replacement path. Result ingress must keep opaque carrier
tests and engine idempotency proof while removing transport-owned ack/visibility
lifecycle.

## TDE-0 - Boundary Decision And Residue Inventory

Goal: record the executor boundary and current residue without making
current-truth docs claim unimplemented behavior.

Scope:

- Keep this roadmap as the target-direction artifact for result ingress
  best-effort convergence.
- Inventory the current transport result-ingress residue:
  `TransportResultIngressOutcome`, `ackable()`, `ClaimedTransportResultIngress`,
  Redis ready/inflight/complete/visibility behavior, and inbox-pump
  `complete(...)` calls.
- Inventory current dispatch queue residue:
  `InMemoryTransportDispatchHandoff` raw `LinkedBlockingQueue` mechanics and
  `RedisTransportDispatchHandoff` transport-owned Redis queue scripts.
- Do not update `transport/TRANSPORT_BOUNDARY_BASELINE.md` to describe result
  ingress as already best-effort until TDE-3 changes production behavior and
  proof.
- Do not update `doc/PROOF_REGISTRY.md` to remove current result-ingress
  visibility/ack proof until the replacement best-effort proof exists.
- If any active doc is touched in this slice, it must label the best-effort
  result behavior as target direction or current residue, not current fact.

Acceptance:

- The roadmap distinguishes current code behavior from target behavior.
- Current-truth owner docs are not changed to claim best-effort result ingress
  before TDE-3 lands.
- The inventory names the classes and concepts that must disappear or become
  starter-local in later slices.
- No production behavior changes are required in this slice.

Verification candidates:

```bash
rg -n "TransportResultIngressOutcome|ClaimedTransportResultIngress|visibilityTimeout|inflight|ackable|complete\\(" transport sdk
rg -n "current residue|target direction|best-effort result" roadmap/TRANSPORT_ASSIGNED_DELIVERY_EXECUTOR_CONVERGENCE_ROADMAP.md
```

## TDE-1 - Queue Primitive Narrows To String/String And In-Memory Dispatch Adopts It

Goal: prove a `String` keyed queue primitive can carry simple transport
dispatch queue mechanics without moving transport semantics into infra.

Scope:

- Narrow the `platform_infra/mass-queue-primitives` main queue contract from
  generic `KeyedBlockingQueueStore<K, V>` to a runtime primitive with
  `String` key and `String` value.
- Refactor the in-memory queue implementation and tests to the same
  `String`/`String` contract. Memory must not keep an object-typed fast path.
- Refactor `InMemoryTransportDispatchHandoff` to use the narrowed in-memory
  queue primitive.
- Add a transport-owned dispatch queue codec if needed:
  `DispatchMessage -> String -> queue -> String -> DispatchMessage`.
- Keep `TransportDispatchHandoff` public behavior unchanged.
- Keep mailbox consumer availability, `DispatchOutcome` mapping, queue
  admission policy, and shutdown semantics in transport.
- Keep `DispatchMessage` validation and outcome construction in transport.
- Do not introduce stats or snapshot reads into the dispatch mainline.
- Remove broad queue `snapshot()` from the primitive main API. If diagnostics
  need queue depth, expose only `size(String key)` or an equivalent
  owner-local point read.
- Do not keep memory-only typed storage such as
  `KeyedQueueEntry<DispatchMessage>` or generic
  `InMemoryKeyedBlockingQueueStore<K, V>`. Memory and Redis should prove the
  same opaque `String` queue contract.
- Extract or add a shared dispatch handoff contract proof, such as
  `TransportDispatchHandoffContractTest`, that can run against both in-memory
  and Redis implementations. The first slice should run it against in-memory so
  TDE-2 does not depend on a test class that does not exist.

Acceptance:

- `InMemoryTransportDispatchHandoff` no longer owns raw keyed
  `LinkedBlockingQueue` mechanics.
- `mass-queue-primitives` main queue API no longer exposes type parameters for
  key or value; key and value are both `String`.
- `mass-queue-primitives` main queue API no longer exposes broad `snapshot()`.
  `size(String key)` is allowed for targeted diagnostics.
- `InMemoryKeyedBlockingQueueStore` no longer exposes or stores typed caller
  values.
- Transport owns any `DispatchMessage` encoding and decoding around the queue
  primitive.
- No infra module imports transport packages.
- Existing in-memory dispatch handoff tests still prove offer rejection,
  backpressure, destructive poll, shutdown, and mailbox availability behavior.
- The in-memory proof is shaped as reusable dispatch handoff contract semantics
  that Redis must satisfy too, not as implementation-specific assertions.
- The shared contract proof exists and is wired to the in-memory
  implementation before Redis convergence starts.
- `TransportDispatchHandoffContractTest` must exist before this slice is
  considered complete. A command that uses
  `-Dsurefire.failIfNoSpecifiedTests=false` is support evidence only, not proof
  that the contract test was created and executed.
- Any stats/snapshot behavior remains diagnostic-only and is not required for
  dispatch correctness.

Verification candidates:

```bash
./mvnw -q -pl platform_infra/mass-queue-primitives,transport/transport_runtime -am test -Dtest=InMemoryKeyedBlockingQueueStoreTest,TransportDispatchHandoffContractTest,InMemoryTransportDispatchHandoffTest,AdapterMailboxConsumerLoopTest,TransportConvergenceArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl transport/transport_runtime -am test -Dtest=TransportDispatchHandoffContractTest,InMemoryTransportDispatchHandoffTest,AdapterMailboxConsumerLoopTest,TransportConvergenceArchitectureGuardTest
rg -n "java.util.concurrent.LinkedBlockingQueue|ConcurrentHashMap<.*DispatchMessage" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/InMemoryTransportDispatchHandoff.java
```

## TDE-2a - Redis Dispatch Queue Primitive Decision

Goal: choose the Redis implementation of the already-narrowed `String` keyed
queue primitive before migrating `RedisTransportDispatchHandoff`.

Scope:

- Compare current `RedisKeyedBlockingQueueStore` against the narrowed
  `String`/`String` primitive needs: mailbox-keyed offer, destructive poll,
  bounded admission, shutdown, and corrupt-item tolerance.
- Default decision: do not back dispatch with current
  `RedisKeyedBlockingQueueStore`, because that store maintains Lua-scripted
  stats, active queue sets, global counters, and oldest-item metadata that the
  dispatch hot path should not depend on.
- Prefer a smaller Redis implementation of the same `String` keyed queue
  primitive owned by infra. Its target operation set is:
  - `offer(String key, String value)` with best-effort or approximate capacity
    rejection;
  - `poll(String key, maxItems, timeoutMillis)` or a simple drain equivalent;
  - optional `size(String key)` point read for targeted diagnostics;
  - `shutdown/close` behavior for local runtime lifecycle;
  - no global stats, active queue set, oldest metadata, visibility, inflight,
    or exact Lua capacity transaction.
- Do not keep Redis-only generic typing. Queue values are opaque strings; typed
  message codecs stay in the caller.
- Keep mailbox consumer availability evidence in transport. Infra queue
  primitives do not know adapter mailbox liveness, selected workers, delivery
  outcomes, or task attempts.
- If a future implementation wants to reuse `RedisKeyedBlockingQueueStore`
  anyway, this slice must be revised first with a named invariant explaining
  why its stats/Lua behavior is acceptable on this dispatch path.

Acceptance:

- The Redis implementation choice is decision-complete before code migration
  starts.
- The chosen primitive owner and package are named, and infra still does not
  import transport packages.
- TDE-2b can be implemented without re-deciding whether dispatch uses the
  existing stats-heavy Redis queue store or a smaller `String` keyed queue
  primitive.
- The queue primitive API is not generic. It exposes `String` key and `String`
  value for memory and Redis implementations.
- The queue primitive API does not expose broad `snapshot()`; only targeted
  `size(String key)` diagnostics are allowed.
- Any Lua retained or added for the chosen primitive has an explicit invariant
  and failure-mode justification. Capacity exactness alone is not enough.

Verification candidates:

```bash
rg -n "RedisKeyedBlockingQueueStore|activeQueues|globalStats|oldestCreatedAt|eval\\(" platform_infra/mass-runtime-redis/src/main/java/com/xa/mass/runtime/redis/queue
rg -n "Redis dispatch queue primitive decision|String keyed queue primitive|Default decision" roadmap/TRANSPORT_ASSIGNED_DELIVERY_EXECUTOR_CONVERGENCE_ROADMAP.md
```

## TDE-2b - Redis Dispatch Handoff Migrates To The Chosen Primitive

Goal: remove duplicated Redis ready-queue mechanics from dispatch handoff while
preserving transport-owned delivery outcomes and avoiding transport-owned Lua
maintenance.

Scope:

- Migrate `RedisTransportDispatchHandoff` to the primitive selected in TDE-2a.
- Do not use current `RedisKeyedBlockingQueueStore` for dispatch unless TDE-2a
  has explicitly been revised to choose it and accept its hot-path stats/Lua
  cost.
- Keep Redis mailbox consumer availability evidence in transport unless and
  until infra owns a generic lease/evidence primitive.
- Keep dispatch codecs and corrupt/invalid item handling in transport.
- Keep `DispatchOutcome` mapping in transport. The Redis primitive only stores
  and returns opaque string queue items.
- Redis offer may do a lightweight capacity check before enqueue. It may be
  approximate under race. Do not add Lua only to guarantee exact `LLEN < max`.

Acceptance:

- Redis dispatch handoff uses the chosen infra Redis string queue primitive for
  ready command storage.
- Redis dispatch handoff still returns `DispatchOutcome` for known offer
  rejection and observable failures.
- Redis dispatch handoff passes the same dispatch handoff contract semantics
  as the in-memory implementation. Backend-specific tests may cover Redis
  keyspace and corruption behavior, but they must not define a different queue
  correctness model.
- Redis dispatch handoff does not maintain global queued counts, active queue
  sets, oldest-item metadata, enqueue/drain counters, or stats hashes as
  dispatch correctness facts.
- Any Redis Lua retained in dispatch is accompanied by a code comment or
  roadmap note naming the protected invariant, non-Lua failure mode, and why it
  is high ROI. Without that justification, prefer ordinary Redis commands.
- Redis keyspace guards still show transport keys do not contain worker
  lifecycle, scheduling, retry, or task attempt state.

Verification candidates:

```bash
./mvnw -q -pl transport/transport_runtime -am test -Dtest=TransportDispatchHandoffContractTest,RedisTransportDispatchHandoffTest,TransportRedisKeyspaceGuardTest,TransportConvergenceArchitectureGuardTest
rg -n "ready-commands|visibility|inflight|complete|globalStats|queuedItems|activeQueues|oldestCreatedAt|enqueuedItems|drainedItems" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery
rg -n "eval\\(|redis.call|Lua|SCRIPT" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery
rg -n "mailbox-consumers" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery
```

Do not use `RedisKeyedBlockingQueueStoreTest` as the TDE-2b proof unless TDE-2a
chooses that primitive. If TDE-2a creates a smaller Redis string queue
primitive, the focused primitive test should replace it. Generic infra queue
scripts may still maintain stats for other owners; the transport proof is that
dispatch handoff does not depend on those stats-heavy scripts.

The `mailbox-consumers` scan is not a forbidden-residue scan in this slice.
Mailbox consumer availability may remain transport-owned queue-admission
evidence. The proof is that it does not carry worker lifecycle, task retry, or
result ingress queue visibility semantics.

## TDE-3 - Result Ingress Becomes Best-Effort Queue

Goal: remove transport-owned result ingress queue lifecycle while preserving opaque
result carrier and engine idempotency.

Scope:

- Replace `RedisTransportResultIngressChannel` claim/complete/visibility
  semantics with a plain best-effort offer/poll or offer/drain queue.
- Remove `ClaimedTransportResultIngress` if no longer needed.
- Rewrite `TransportResultIngressQueuePump` to consume plain result entries and
  delegate to `TransportResultIngressHandler` without completing claims.
- Simplify `BufferedTransportResultIngressChannel` so non-ackable handler
  outcomes do not create transport-owned result retry lifecycle.
- Change the `TransportResultIngressHandler` / `TransportResultIngressOutcome`
  contract in the same slice. The target API must not expose `ackable()`,
  `RETRYABLE_FAILURE`, claim refs, or any handler-returned queue retry decision
  to transport runtime. Acceptable shapes are:
  - `void handle(ResultIngressEntry entry)` with handler exceptions logged as
    best-effort loss by the pump; or
  - a renamed starter-local classification that is consumed only inside
    starter/engine result handling and is not visible to transport queue code.
- Rewrite `RuntimeTaskResultIngestChannel` and `ResultIngressHandleOutcome`
  call sites in the same slice so result validation, duplicate/late/stale
  classification, and permanent reject behavior remain engine/starter-owned
  without feeding transport ack/retry semantics.
- Treat this as an intentional internal API break across `transport_api`,
  `transport_runtime`, and SDK/starter assembly. Do not keep deprecated
  overloads, compatibility aliases, or adapter shims for the old
  `ackable()`/retryable result-ingress contract.
- If starter/engine still needs result classification for logs, metrics, or
  tests, keep that type starter-local. It must not be imported by transport
  runtime and must not drive queue ack, retry, reclaim, or visibility behavior.
- Keep `ResultIngressEntry`, `ResultIngressMessage`,
  `AdapterResultIngressEntries`, and `ResultIngressEntryCodec` as opaque
  carrier/codec surfaces unless a smaller carrier already exists.

Acceptance:

- No main transport result ingress implementation uses `inflight`,
  `complete`, `visibilityTimeout`, claim refs, or visibility reclaim.
- `TransportResultIngressHandler` no longer returns transport-visible
  ack/retry decisions, or the remaining return type is explicitly documented as
  starter-local and cannot affect queue retry/ack behavior.
- Main transport API/runtime code no longer references
  `TransportResultIngressOutcome.ackable()`, `RETRYABLE_FAILURE`, claim refs,
  or `ResultIngressHandleOutcome.toTransportOutcome()`.
- All in-repo callers and tests are migrated in the same slice; no compatibility
  wrapper preserves old result-ingress semantics.
- Result ingress tests prove best-effort offer/poll, bounded admission,
  shutdown behavior, opaque carrier preservation, and no task-shaped parsing in
  transport.
- SDK/starter tests prove `RuntimeTaskResultIngestChannel` still handles
  duplicate, late, stale attempt, stale lease, invalid payload, and permanent
  reject cases without relying on transport result retries.

This slice is atomic. Do not first remove Redis `complete(...)` while leaving
`TransportResultIngressOutcome.ackable()` or a retryable handler result as a
live production contract; that creates a hidden second result-ingress lifecycle.

Verification candidates:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,sdk/xa-mass-embedded-sdk -am test -Dtest=ResultIngressEntryTest,ResultIngressMessageTest,AdapterResultIngressEntriesTest,BufferedTransportResultIngressChannelTest,RedisTransportResultIngressChannelTest,TransportResultIngressQueuePumpTest,RuntimeTaskResultIngestChannelTest,MassApplicationDistributedTransportTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "ClaimedTransportResultIngress|visibilityTimeout|inflight|TransportResultIngressChannel.*complete|RedisTransportResultIngressChannel.*complete|TransportResultIngressOutcome.*ackable|\\.ackable\\(" transport sdk
```

## TDE-4 - Adapter Runtime Consumer Loop Hardening

Goal: prove and harden the already-landed adapter-owned consumer loop shape
without creating a broad transport host abstraction.

Scope:

- Keep `TransportAdapterBootstrapContext` as the narrow capability surface.
- Verify each concrete adapter bootstrap contributes explicit runtime handles:
  mailbox consumer, managed protocol resources, transport servers, pull
  channels, and session evidence drivers where applicable.
- Keep SDK/starter assembly responsible only for start/stop ordering of
  contributed handles and backend wiring.
- Do not expose `TransportDispatchHandoff`, mailbox registries, endpoint lease
  stores, worker-runtime mutation ports, or engine services to concrete
  adapters.
- Preserve mailbox availability, session evidence, and delivery failure
  evidence as adapter/transport evidence lanes, not lifecycle owners.

Acceptance:

- Concrete adapters can be tested as owning their mailbox consumer contribution
  and final-hop executor without engine object access.
- `MassApplication` does not hand-roll command drain behavior; it starts and
  stops contributed runtime handles.
- Architecture guards fail if concrete adapter bootstraps receive broad
  runtime owner objects instead of narrow capabilities.

Verification candidates:

```bash
./mvnw -q -pl transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am test -Dtest=TransportAdapterContributionTest,AdapterMailboxConsumerLoopTest,EmbeddedAdapterHostSetTest,WebSocketTransportAdapterBootstrapTest,SocketTransportAdapterBootstrapTest,PollingDeliveryExecutorTest,MassApplicationDistributedTransportTest,TransportConvergenceArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false
rg -n "getTransportDispatchHandoff|getAdapterMailboxConsumerRegistry|getEndpointLeaseStore|getWorkerRuntime|TaskResultIngestFacade" transport/*-adapter transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime
```

## TDE-5 - Dependency And Vocabulary Cleanup

Goal: remove stale queue/result lifecycle vocabulary and prevent regression.

Scope:

- Remove unused transport-runtime dependencies after dispatch/result queue
  convergence proves which infra modules are still needed.
- Remove stale result ingress queue visibility tests and replace them with best-effort
  result ingress and engine idempotency tests.
- Add or update architecture guards:
  - infra queue primitives must not import transport packages;
  - transport result ingress must not expose task/result lifecycle fields;
  - transport runtime must not contain result `inflight`, `complete`, or
    visibility-reclaim behavior;
  - concrete adapters must not receive broad runtime owner objects.
- Update `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  `doc/INFRA_TRUTH_LAYERS.md`, and `doc/PROOF_REGISTRY.md` with only current
  implementation facts after slices land.

Acceptance:

- No stale reliable-result-ingress vocabulary remains in mainline transport code
  or active proof docs.
- No compatibility aliases keep old result claim/complete paths alive.
- Active docs describe implemented behavior only; deferred reliable result
  delivery, if desired, is a separate future decision.

Verification candidates:

```bash
rg -n "ClaimedTransportResultIngress|visibilityTimeout|inflight|TransportResultIngressChannel.*complete|RedisTransportResultIngressChannel.*complete|ackable|reclaim" transport sdk doc roadmap
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am test -Dtest=TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest,MassApplicationDistributedTransportTest,RuntimeTaskResultIngestChannelTest -Dsurefire.failIfNoSpecifiedTests=false
```

Avoid broad `complete\(` scans in this cleanup slice. Other subsystems may have
legitimate completion vocabulary; the guard should target result-ingress claim
completion and dispatch-handoff regressions specifically.

## Decisions Needing Agreement

These decisions are intentionally called out before implementation. If they are
accepted, agents should not re-open them during the slice unless current code
proves a contradiction.

1. Redis dispatch queue primitive:
   - recommendation: introduce or choose a minimal Redis implementation of the
     `String` keyed queue primitive for dispatch, not current
     `RedisKeyedBlockingQueueStore`;
   - reason: current `RedisKeyedBlockingQueueStore` carries Lua scripts,
     active queue sets, global stats, and oldest-item metadata that are not
     dispatch correctness facts;
   - memory and Redis should share the same non-generic queue API:
     `String key`, `String value`;
   - Redis capacity/backpressure should use lightweight point checks and may be
     approximate under race; exact Lua capacity enforcement is not required;
   - needs explicit agreement only if the team wants to reuse
     `RedisKeyedBlockingQueueStore` despite that cost.
2. Result ingress API break:
   - recommendation: remove transport-visible `ackable()` / retryable result
     ingress semantics in one atomic slice;
   - reason: keeping a compatibility alias preserves the old reliable-inbox
     lifecycle as a second live path;
   - no further agreement is needed if internal compatibility is not required.
3. Current-truth docs timing:
   - recommendation: do not update `transport/TRANSPORT_BOUNDARY_BASELINE.md`
     or `doc/PROOF_REGISTRY.md` to target best-effort result behavior until
     TDE-3 lands;
   - reason: active owner docs must describe implemented behavior, while this
     roadmap can carry target direction and residue.

## Verification Policy

Commands in the slice sections are implementation candidates. During early
execution, `-Dsurefire.failIfNoSpecifiedTests=false` may be used to discover the
right reactor shape, but it is not a completion proof. Before a slice is marked
done, either:

- rerun the focused tests in their owning modules without hiding missing test
  classes; or
- add a guard that proves the named proof tests exist and the candidate command
  is only a compile/support check.

Any newly named proof test must have at least one owning-module verification
command without `-Dsurefire.failIfNoSpecifiedTests=false` before the related
slice can be marked complete.

## Roadmap Completion Criteria

This roadmap is complete only when all of these are true:

- Dispatch handoff uses shared infra `String` keyed queue mechanics for memory
  and Redis where the primitive fits, while transport still owns
  `DispatchOutcome` semantics.
- `mass-queue-primitives` does not expose generic queue APIs for this runtime
  handoff path; typed carriers are encoded and decoded by callers.
- Memory and Redis dispatch handoff implementations pass the same contract
  semantics; backend-specific tests only cover backend behavior, not alternate
  transport correctness.
- Dispatch queue code has no Lua unless each script has an explicit high-ROI
  invariant justification.
- Result ingress no longer maintains transport-owned ready/inflight/complete or
  visibility timeout lifecycle.
- Result ingress handler API no longer exposes transport-visible ack/retry
  decisions.
- Engine/starter result ingest tests prove idempotent handling of duplicate,
  late, stale, invalid, and rejected result entries without relying on transport
  result retries.
- Adapter runtime contribution tests prove concrete adapters own final-hop
  consumer/runtime handles through narrow bootstrap capabilities.
- Active owner docs and proof registry describe the implemented boundary.
- Residue scan finds no active stale host-thread, reliable-result-ingress, or
  transport-result-lifecycle vocabulary outside archived history.

## Completion Evidence

Implemented on 2026-06-27:

- `mass-queue-primitives` is a non-generic `String` key / `String` value queue
  primitive with targeted `size(String key)` diagnostics and no broad
  `snapshot()` API.
- In-memory and Redis assigned-dispatch handoff both use the shared queue
  primitive while transport retains `DispatchMessage`, mailbox consumer
  availability, corrupt-item handling, and `DispatchOutcome` ownership.
- Redis dispatch uses ordinary list operations with lightweight capacity
  checks; no dispatch Lua script or transport-owned queue stats are used for
  correctness.
- Result ingress is a best-effort result ingress queue. The transport handler
  sink is void and does not expose ack/retry/reclaim decisions.
- Result ingress Redis namespace is `xa:mass:transport:result-ingress:v1`.
- Active owner docs and proof registry were updated; architecture guards now
  block old result claim/ack lifecycle symbols and task-shaped ingress DTOs.

Verified with focused transport, infra, polling, adapter, and SDK/starter test
commands during implementation. Residual historical vocabulary remains only in
archived roadmap text and architecture guard forbidden-token strings.

## Related Current Proof Surfaces

- `transport.endpoint-lease`
- `transport.dispatch-message-ownership`
- `transport.embedded-adapter-independence`
- `transport.adapter-command-executor`
- `transport.push-selected-worker-delivery`
- `transport.polling-selected-worker-delivery`
- `transport.result-ingress-entry`

These proof rows were repaired as the slices landed and now describe the
implemented best-effort boundary.
