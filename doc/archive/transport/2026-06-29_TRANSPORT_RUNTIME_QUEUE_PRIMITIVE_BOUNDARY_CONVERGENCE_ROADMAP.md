# Transport Runtime Queue Primitive Boundary Convergence Roadmap

Status: complete; archived after implementation and verification on 2026-06-29.

Current landed shape:

- dispatch Redis/memory paths delegate to `KeyedBlockingQueueStore` variants;
  the duplicate `TransportDispatchHandoff` interface has been removed.
- Redis and memory result ingress delegate to `KeyedBlockingQueueStore`
  variants.
- `TaskResultIngressQueueDrain` already lives on the starter side and drains the
  result ingress queue into result convergence.

Remaining mainline work:

- complete verification and residue scans;
- archive or keep this roadmap according to completion policy after proof.

## Summary

Transport should not own generic runtime queue data structures. Queue mechanics
belong to `platform_infra/mass-queue-primitives` and its Redis implementation
under `platform_infra/mass-runtime-redis`.

Transport may own only thin typed adapters around those primitives:

- dispatch message codec and `DispatchOutcome` mapping
- result ingress entry codec and default result queue semantics
- transport-specific shutdown/backpressure/status translation

The target is not to expose `KeyedQueueEntry` or raw string values to adapters,
SDK assembly, or engine code. The target is to make all real queue mechanics
come from infra primitives while keeping transport contracts focused on
assigned dispatch and result ingress facts.

## Current Code Observations

- `TransportDispatchQueue` is a transport-runtime semantic port for assigned
  dispatch items:
  `transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDispatchQueue.java`.
- `TransportDispatchHandoff` was the old alias for `TransportDispatchQueue`.
  It has been deleted; callers use `TransportDispatchQueue` directly.
- `InMemoryTransportDispatchHandoff` already delegates to
  `InMemoryKeyedBlockingQueueStore`.
- `RedisTransportDispatchHandoff` already delegates to
  `RedisKeyedBlockingQueueStore`.
- `RedisTransportResultIngressChannel` already delegates to
  `RedisKeyedBlockingQueueStore`.
- `InMemoryTransportResultIngressQueue` delegates to
  `InMemoryKeyedBlockingQueueStore`.
- `BufferedTransportResultIngressChannel` was classified as residue and deleted
  with its test; result drain ownership stays with `TaskResultIngressQueueDrain`.
- `TransportResultIngressQueue` carries a `resultQueueKey`, but current
  implementations only accept `TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY`.
  Multi-result-queue topology is not implemented.
- `platform_infra/mass-queue-primitives` currently exposes a string-value
  primitive:
  `KeyedBlockingQueueStore`, `KeyedQueueEntry`, `KeyedQueueOfferResult`,
  `KeyedQueuePollResult`.
- `platform_infra/mass-runtime-api` currently owns task/worker runtime
  contracts, not generic queue mechanics.

## Owner Review

`platform_infra/mass-queue-primitives` owns queue mechanics:

- keyed queue admission
- blocking poll
- drain
- per-key size
- shutdown behavior
- memory/Redis semantic parity

`transport_runtime` owns typed queue semantics:

- `DispatchMessage` serialization/deserialization
- `ResultIngressEntry` serialization/deserialization
- `DispatchOutcome` translation
- default result queue policy
- corrupt payload handling for transport-owned codecs

`engine` owns task lifecycle recovery:

- attempt timeout
- retry/reassign policy
- result convergence
- task mutation

The queue primitive must not become an engine/transport lifecycle owner. It is
only storage and blocking mechanics.

## Boundary Decision

All transport runtime queues must be backed by infra queue primitives. Transport
classes may remain as typed adapters, but they must not contain independent
queue data structures such as `LinkedBlockingQueue`, custom wait/notify loops,
wrapper-owned poll/sleep loops, custom Redis list scripts, or queue-specific
maps.

Allowed transport wrapper shape:

```text
Transport typed queue port
  -> transport codec/status mapping
  -> KeyedBlockingQueueStore
```

Forbidden transport wrapper shape:

```text
Transport typed queue port
  -> custom queue data structure
  -> custom queue lifecycle / retry / ack / claim semantics
```

## Generic Queue API Decision

Adding a generic typed queue can help, but it should not be the first slice.
The first slice should prove that current transport queue implementations are
thin over `KeyedBlockingQueueStore`.
For this roadmap, the typed generic helper is deferred. Do not implement it in
the queue primitive convergence slice.

If a generic API is added, the preferred first placement is
`platform_infra/mass-queue-primitives`, not `platform_infra/mass-runtime-api`.
Reason: the queue abstraction is storage mechanics, while `mass-runtime-api`
currently owns task/worker runtime contracts. Moving a generic queue API into
`mass-runtime-api` is justified only if multiple runtime owners need the same
stable typed queue contract at their public runtime boundary.

Candidate later shape:

```java
public interface RuntimeQueueCodec<T> {
    String encode(T value);
    T decode(String value);
    long createdAtEpochMillis(T value);
}

public final class TypedKeyedBlockingQueueStore<T> {
    KeyedQueueOfferResult offer(String key, T value, int maxItemsPerKey);
    TypedKeyedQueuePollResult<T> poll(String key, int maxItems, long timeoutMillis);
    TypedKeyedQueuePollResult<T> drain(String key, int maxItems);
    int size(String key);
    void shutdown();
}
```

This would remove repeated encode/decode boilerplate from transport without
making infra understand `DispatchMessage` or `ResultIngressEntry`.
The typed helper must preserve primitive offer/poll outcome semantics such as
empty, shutdown, rejected, or unavailable. It must not collapse poll into a bare
`List<T>` and force transport wrappers to recreate queue status translation.

## Non-Goals

- Do not add ack, claim, visibility timeout, reclaim, retry, or reliable-message
  semantics to transport queues.
- Do not make queue primitives parse task, worker, dispatch, or result payloads.
- Do not expose `KeyedQueueEntry` or raw string queue values to engine, SDK
  assembly, adapters, or worker-facing APIs.
- Do not implement multi-result-queue topology in this roadmap. Current result
  ingress remains default-key only unless a separate result topology roadmap
  defines pump ownership.
- Do not move engine task lifecycle recovery into queue primitive status.
- Do not rename every queue key vocabulary as a first step.

## Do Not Start With

Do not start by adding a generic queue API to `mass-runtime-api`.

That would create a broader public-looking runtime contract before proving the
current transport wrappers are thin. Start by removing transport-owned queue
data structures and alias interfaces. Add generics only if duplicated typed
codec adapters remain after the first slices.

Do not start by deleting `TransportDispatchQueue` or
`TransportResultIngressQueue` either. These are current transport semantic
ports. First retarget their implementations to infra primitives, then decide
whether a typed generic helper makes the ports smaller.

Do not keep `BufferedTransportResultIngressChannel` as a special-case buffered
result path. Classify it in QP-0, then delete it. If it has a production caller,
move that caller first; do not preserve or migrate the buffered channel itself.

## Target Shape

```text
engine/starter assigned dispatch
  -> TransportDispatchQueue.offer(dispatchQueueKey, List<DispatchMessage>)
  -> transport dispatch codec/status adapter
  -> KeyedBlockingQueueStore
  -> adapter-owned consumer poll(dispatchQueueKey)

adapter result ingress
  -> TransportResultIngressQueue.offer(defaultResultQueueKey, ResultIngressEntry)
  -> transport result codec/default-key adapter
  -> KeyedBlockingQueueStore
  -> starter-owned result drain poll(defaultResultQueueKey)
```

Transport does not own the queue container. Transport owns only the value type,
codec, and status translation.

## QP-0 Inventory And Classification

Scope:

- Inventory all production classes that own queue-like data structures under:
  - `transport/transport_runtime`
  - `transport/polling-adapter`
  - `transport/socket-adapter`
  - `transport/websocket-adapter`
  - `transport/adapter-starter`
  - `sdk/xa-mass-embedded-sdk`
- Classify each queue-like object as:
  - infra primitive backed
  - transport typed adapter
  - adapter-local buffer
  - test fixture
  - residue

Acceptance:

- Inventory separates production and test-only queue usage.
- Inventory explicitly classifies:
  - `TransportDispatchQueue`
  - `TransportDispatchHandoff`
  - `InMemoryTransportDispatchHandoff`
  - `RedisTransportDispatchHandoff`
  - `TransportResultIngressQueue`
  - `InMemoryTransportResultIngressQueue`
  - `RedisTransportResultIngressChannel`
  - `BufferedTransportResultIngressChannel`
  - polling pending delivery buffer classes
- Inventory states whether each queue should remain transport-owned,
  adapter-owned, or infra-backed.
- Inventory states whether `BufferedTransportResultIngressChannel` has any
  production caller and names the caller migration needed before deletion. The
  target is deletion with its tests, not migration.

## QP-1 Collapse Dispatch Queue Alias

Goal:

Remove the duplicate `TransportDispatchQueue` / `TransportDispatchHandoff`
mainline vocabulary.

Recommended direction:

- Keep one transport semantic port for assigned-dispatch queues.
- Prefer keeping `TransportDispatchQueue` as the neutral name.
- Delete `TransportDispatchHandoff` after migrating call sites that only need
  `offer(String, List<DispatchMessage>)` and `poll(...)`.
- If batch helper is still useful, make it a static helper or method on
  `TransportAssignedDeliverySubmitter`, not a second interface.

Acceptance:

- No production code imports `TransportDispatchHandoff`.
- SDK/starter config uses the surviving dispatch queue type, including:
  - `TransportConfig`
  - `TransportRuntimeComposition`
  - `MassApplication`
  - `MassApplicationBuilder` Redis dispatch factory wiring
  - SDK/starter tests and fixtures that currently implement or inject
    `TransportDispatchHandoff`
- `InMemoryTransportDispatchHandoff` / `RedisTransportDispatchHandoff` class
  names may remain as deferred naming residue after the interface is removed.
  Do not rename them piecemeal; when naming cleanup happens, rename both
  implementations, fixtures, docs, and proof references in one cleanup slice.
- `TransportDispatchHandoffContractTest` is renamed or replaced by the
  surviving queue contract proof, such as `TransportDispatchQueueContractTest`.
- Focused dispatch tests still prove:
  - offer returns `DispatchOutcome`
  - poll returns only decoded `DispatchMessage`
  - corrupt queue entries are dropped at codec boundary
  - shutdown rejects/empties according to current contract

## QP-2 Move In-Memory Result Ingress To Queue Primitive

Goal:

Remove `LinkedBlockingQueue<ResultIngressEntry>` from
`InMemoryTransportResultIngressQueue`.

Scope:

- Retarget `InMemoryTransportResultIngressQueue` to
  `InMemoryKeyedBlockingQueueStore`.
- Delete `BufferedTransportResultIngressChannel`. If QP-0 finds a production
  caller, retarget that caller to the primitive-backed result ingress queue or
  to the owner-owned result drain path before deleting the channel.
- Reuse `ResultIngressEntryCodec` for memory and Redis paths if result queue
  storage is string-value only.
- Preserve current default-result-key behavior:
  `TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY` remains the only
  accepted key.

Acceptance:

- `InMemoryTransportResultIngressQueue` does not import
  `java.util.concurrent.LinkedBlockingQueue`.
- `BufferedTransportResultIngressChannel` is deleted from main/test sources.
- Memory and Redis result queues share the same primitive status semantics.
- Corrupt result queue payload handling is defined at the transport codec
  boundary.
- `TaskResultIngressQueueDrainTest`,
  `RedisTransportResultIngressChannelTest`, and a memory result queue successor
  test pass.

## QP-3 Defer Typed Generic Queue Helper

Goal:

Record that the typed generic queue helper is intentionally not part of this
roadmap.

Decision:

- Do not put this in `mass-runtime-api` unless there is a proven runtime API
  caller outside transport.
- Do not implement a codec-backed helper in `mass-queue-primitives` in this
  roadmap.
- Revisit only after QP-1 and QP-2 leave meaningful duplicated codec/status
  boilerplate.

Acceptance:

- No new typed generic queue helper is introduced.
- Any duplicated transport encode/decode/status boilerplate remains explicit
  and small after QP-1/QP-2.
- Guards still prevent transport from owning custom queue data structures.
- If a future roadmap adds a typed helper, it must preserve primitive
  offer/poll outcome semantics and must not return only `List<T>`.

## QP-4 Remove Transport-Owned Queue Data Structures

Goal:

Make it impossible for transport runtime to grow another queue implementation.

Scope:

- Add architecture guard for transport runtime production code:
  - forbid `LinkedBlockingQueue`, `ArrayBlockingQueue`, raw `BlockingQueue`
    imports in transport runtime queue implementations
  - forbid transport queue implementations from implementing timeout behavior
    with `Thread.sleep`, `TimeUnit.*.sleep`, or repeated non-blocking primitive
    polls when `KeyedBlockingQueueStore.poll(...)` can own the wait
  - forbid direct Redis list operations in transport queue code when an infra
    primitive exists
  - allow adapter-local protocol/session buffers only when explicitly scoped
    outside transport runtime queue contracts
- Keep polling pending delivery buffers classified separately because they are
  polling-adapter-local pull buffers, not engine-to-transport handoff queues.

Acceptance:

- Guard fails if `transport_runtime` queue implementations import Java blocking
  queue classes directly.
- Guard fails if transport queue implementations bypass
  `KeyedBlockingQueueStore` / `RedisKeyedBlockingQueueStore`.
- Guard fails if dispatch/result queue adapters implement their own poll/sleep
  loop instead of delegating timeout behavior to `KeyedBlockingQueueStore.poll(...)`.
- Docs say queue primitives own mechanics; transport owns typed value adapters.

## QP-5 Documentation And Proof Registry

Scope:

- Update `transport/AGENTS.md`.
- Update `transport/TRANSPORT_BOUNDARY_BASELINE.md`.
- Update `doc/INFRA_TRUTH_LAYERS.md`.
- Update `doc/PROOF_REGISTRY.md`.

Acceptance:

- Transport docs no longer imply transport owns runtime queue data structures.
- Redis key manifest remains transport-key aware but queue mechanics are
  attributed to infra primitives.
- Proof registry points to:
  - queue primitive tests
  - dispatch queue contract tests
  - result ingress queue tests
  - architecture guard tests

## Suggested Implementation Order

1. QP-0 inventory.
2. QP-2 move in-memory result ingress to `InMemoryKeyedBlockingQueueStore`.
3. QP-1 collapse `TransportDispatchQueue` / `TransportDispatchHandoff`.
4. QP-3 decide generic typed helper only after duplicate code remains visible.
5. QP-4 add guards.
6. QP-5 update docs/proof registry and run residue scan.

This order keeps behavior stable while removing transport-owned queue mechanics
first.

## Verification Candidates

Compile:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-queue-primitives,platform_infra/mass-runtime-redis,transport/transport_runtime,transport/adapter-starter,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests test-compile
```

Focused tests during implementation:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-queue-primitives,platform_infra/mass-runtime-redis,transport/transport_runtime,sdk/xa-mass-embedded-sdk -am test "-Dtest=InMemoryKeyedBlockingQueueStoreTest,RedisKeyedBlockingQueueStoreTest,InMemoryTransportDispatchHandoffTest,RedisTransportDispatchHandoffTest,TransportDispatchQueueContractTest,RedisTransportResultIngressChannelTest,InMemoryTransportResultIngressQueueTest,TaskResultIngressQueueDrainTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DtrimStackTrace=true"
```

The focused command above may be used during implementation while successor
test classes are being created. It is not sufficient completion proof.

Completion proof must not hide missing tests. First install current reactor
artifacts without tests so module-scoped focused tests resolve current local
classes instead of stale Maven artifacts:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-queue-primitives,platform_infra/mass-runtime-redis,transport/transport_api,transport/transport_runtime,transport/adapter-starter,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests install
.\mvnw.cmd -q -pl platform_infra/mass-queue-primitives test "-Dtest=InMemoryKeyedBlockingQueueStoreTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis test "-Dtest=RedisKeyedBlockingQueueStoreTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/transport_runtime test "-Dtest=InMemoryTransportDispatchHandoffTest,RedisTransportDispatchHandoffTest,TransportDispatchQueueContractTest,RedisTransportResultIngressChannelTest,InMemoryTransportResultIngressQueueTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=TaskResultIngressQueueDrainTest" "-DtrimStackTrace=true"
```

If the implementation keeps a successor proof under a different test class
name, update this command in the same slice. Do not use
`-Dsurefire.failIfNoSpecifiedTests=false` for final acceptance.

Residue checks:

```powershell
rg -n "BufferedTransportResultIngressChannel|LinkedBlockingQueue|ArrayBlockingQueue|BlockingQueue<|\\bTransportDispatchHandoff\\b|RedisCommands|RPUSH|LPOP|BRPOP|BLPOP" transport/transport_runtime/src/main/java sdk/xa-mass-embedded-sdk/src/main/java --glob "*.java" --glob "!**/target/**"
rg -n "Thread\\.sleep|TimeUnit\\.[A-Z]+\\.sleep|TimeUnit\\.MILLISECONDS\\.sleep" transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/InMemoryTransportResultIngressQueue.java transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RedisTransportResultIngressChannel.java --glob "*.java" --glob "!**/target/**"
rg -n "KeyedBlockingQueueStore|InMemoryKeyedBlockingQueueStore|RedisKeyedBlockingQueueStore" transport/transport_runtime/src/main/java platform_infra --glob "*.java" --glob "!**/target/**"
```

Expected after completion:

- No direct Java blocking queue usage in transport runtime queue implementations.
- No custom Redis list operations in transport runtime queue implementations.
- No transport runtime queue wrapper owns timeout waiting via poll/sleep loops.
- `TransportDispatchHandoff` interface references are gone from production
  code. Implementation class names may remain only if explicitly documented as
  deferred naming residue.
- `BufferedTransportResultIngressChannel` is gone.
- Result ingress memory and Redis paths both use infra queue primitives.

## Completion Evidence

Verified on 2026-06-29:

```powershell
.\mvnw.cmd -q -pl platform_infra/mass-queue-primitives,platform_infra/mass-runtime-redis,transport/transport_api,transport/transport_runtime,transport/adapter-starter,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests install
.\mvnw.cmd -q -pl platform_infra/mass-queue-primitives test "-Dtest=InMemoryKeyedBlockingQueueStoreTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl platform_infra/mass-runtime-redis test "-Dtest=RedisKeyedBlockingQueueStoreTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk test "-Dtest=TaskResultIngressQueueDrainTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl transport/transport_runtime test "-Dtest=InMemoryTransportDispatchHandoffTest,RedisTransportDispatchHandoffTest,TransportDispatchQueueContractTest,RedisTransportResultIngressChannelTest,InMemoryTransportResultIngressQueueTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest" "-DtrimStackTrace=true"
.\mvnw.cmd -q -pl sdk/xa-mass-embedded-sdk -DskipTests compile
.\mvnw.cmd -q -pl platform_infra/mass-queue-primitives,platform_infra/mass-runtime-redis,transport/transport_api,transport/transport_runtime,transport/adapter-starter,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests test-compile
```

Residue scans showed no production references to the removed
`TransportDispatchHandoff` interface, no production `BufferedTransportResultIngressChannel`,
and no direct Java blocking queue, direct Redis list operation, or wrapper-owned
poll/sleep loop in the transport runtime queue adapters. Remaining
`InMemoryTransportDispatchHandoff` / `RedisTransportDispatchHandoff` class names
are documented deferred naming residue only.

## Roadmap Completion Criteria

The roadmap can be marked complete only when:

- every production transport runtime queue implementation delegates to infra
  queue primitives;
- transport exposes only typed semantic ports, not raw queue stores;
- no duplicate dispatch queue interface remains, or the remaining duplicate is
  documented as an intentional temporary residue;
- `BufferedTransportResultIngressChannel` is deleted from transport runtime;
- result ingress queue memory/Redis behavior is covered by focused tests;
- docs and proof registry reflect the new owner split;
- residue scan shows no transport-owned queue data structures in the runtime
  queue mainline.
