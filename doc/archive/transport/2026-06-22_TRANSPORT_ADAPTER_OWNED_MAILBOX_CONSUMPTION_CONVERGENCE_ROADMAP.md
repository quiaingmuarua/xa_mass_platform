# Transport Adapter-Owned Mailbox Consumption Convergence Roadmap

Status: complete; archived 2026-06-22 after adapter-owned mailbox consumption
landed in code, owner docs, guards, and focused tests.

## Summary

Move assigned-dispatch mailbox consumption from a transport-runtime central
mount to adapter-owned mailbox consumers.

Target owner split:

```text
transport runtime owns:
  TransportDispatchHandoff / mailbox queue implementation
  bounded offer admission
  destructive mailbox poll mechanics

adapter host owns:
  mailbox poll loop for its own mailbox key
  protocol final-hop by selectedWorkerId
  outcome / failure evidence emission when failure is known
```

Target dispatch handoff contract:

```java
public interface TransportDispatchHandoff {
    List<DispatchOutcome> offer(DispatchRoutingBatch batch);

    List<DispatchRoutingItem> poll(String adapterMailboxKey,
                                   int maxItems,
                                   long timeoutMillis) throws InterruptedException;

    void shutdown();
}
```

This roadmap intentionally removes `complete(...)`,
`ClaimedDispatchRoutingBatch`, `DispatchHandoffReference`, inflight references,
visibility timeout, and requeue from the assigned-dispatch handoff mainline.
Transport remains a best-effort delivery executor, not a reliable message
broker.

The destructive handoff change and adapter-owned consumer change must land
together in production. A destructive poll behind the current central
`AdapterMailboxMount` is an invalid intermediate state.

## Current Facts

- `TransportDispatchHandoff` currently combines producer offer, mailbox poll,
  handoff claim materialization, handoff completion, and shutdown.
- `TransportDispatchHandoff.poll(...)` returns `ClaimedDispatchRoutingBatch`.
- `TransportDispatchHandoff.complete(...)` is currently the local queue ack.
  Redis removes inflight refs, command payload, and retention index in
  `complete`.
- `InMemoryTransportDispatchHandoff` maintains ready queues, inflight claims,
  visibility deadlines, and reclaim-to-ready behavior.
- `RedisTransportDispatchHandoff` maintains ready commands, inflight commands,
  command payload hash, retention deadline index, and mailbox consumer
  evidence.
- `AdapterMailboxMount` is the current embedded central drain helper: it polls
  `TransportDispatchHandoff`, calls `binding.getCommandExecutor()`, emits
  retryable failure evidence, then calls `complete(...)`.
- `TransportBinding` currently requires `AdapterCommandExecutor`.
- `TransportRuntimeRegistry` currently indexes command executors and exposes
  command-executor resolution.
- `EmbeddedAdapterContributionHost` currently builds one `AdapterMailboxMount`
  per `TransportBinding`.
- `MailboxConsumerAvailabilityPublisher` currently depends on
  `TransportBinding`; it is mounted by the central host path.
- `DispatchRoutingBatch` and `DispatchRoutingItem` are already the flat
  assigned-dispatch carrier. This roadmap does not reopen the dispatch item
  carrier shape.
- `PollingPendingDeliveryBuffer` is polling-adapter owned. Polling adapter
  enqueue into that buffer is a final-hop attempt, not transport-core handoff
  ownership.
- Current `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  and `doc/PROOF_REGISTRY.md` still describe claim/ack/inflight as current
  dispatch handoff truth. This roadmap supersedes that portion after
  implementation, but those docs remain accurate current-state evidence until
  the code changes.

## Owner Review

`TransportDispatchHandoff` belongs to transport runtime as queue mechanics:

```text
offer(batch) -> admitted / rejected with DispatchOutcome
poll(mailbox) -> destructive dequeue for an adapter host
```

It must not own:

- adapter final-hop dispatch loop
- adapter protocol send/poll result handling
- worker result parsing
- retry, reassign, compensation, or task attempt timeout
- adapter health, restart, takeover, migration, or lifecycle supervision
- failure-emission retry loops after an adapter host has accepted a poll item
- `TransportBinding` callback dispatch through `AdapterCommandExecutor`

An adapter host owns mailbox consumption:

```text
poll mailbox
attempt final hop for selectedWorkerId
emit immediate DispatchOutcome / retryable failure evidence when possible
```

For embedded Java, each concrete adapter contributes an adapter-owned mailbox
consumer. A shared helper is allowed only if it is constructed by that adapter
host with a mailbox key, mailbox client, final-hop callback, and outcome sink.
It must not resolve `TransportBinding`, inspect registries, own endpoint lease
stores, parse payload, select workers, or decide retry policy.

Engine remains the recovery owner:

```text
worker does not consume
adapter process crashes after poll
failure evidence write fails after poll
result never arrives
```

Those cases resolve through engine-owned task attempt timeout, retry, reassign,
and compensation. Transport must not add requeue/reclaim policy to recover
them.

## Boundary Decision

`poll(...)` is destructive:

```text
poll success = adapter host accepted one or more delivery attempts
```

After `poll(...)` returns items, transport handoff state for those items is
done. The adapter host is responsible for immediate final-hop outcome or
failure evidence when it can observe the failure. If the adapter host crashes
or cannot write evidence, the engine task attempt timeout is the recovery
mechanism.

`complete(...)` exists today only to maintain handoff-local inflight state. It
does not prove worker execution, task result, or business completion. Since
transport is not the reliable-message owner, this ack state should leave the
assigned-dispatch mainline.

`DispatchOutcome` remains the delivery-attempt evidence model. Offer
backpressure, unavailable mailbox, invalid item, no endpoint, and adapter
final-hop failure still return or emit `DispatchOutcome` when known. Removing
ack must not remove known-failure observability.

## Target Shape

### Handoff

```java
public interface TransportDispatchHandoff {
    List<DispatchOutcome> offer(DispatchRoutingBatch batch);

    List<DispatchRoutingItem> poll(String adapterMailboxKey,
                                   int maxItems,
                                   long timeoutMillis) throws InterruptedException;

    void shutdown();
}
```

Rules:

- `offer` validates one mailbox-targeted batch and returns one
  `DispatchOutcome` per item.
- `offer` rejects unavailable mailbox, shutdown, invalid target, or queue
  backpressure as immediate outcomes.
- `poll` reads only one adapter mailbox key.
- `poll` returns flat `DispatchRoutingItem` values, not claim wrappers.
- `poll` must not return items from another mailbox.
- `poll` removes returned items from handoff storage before returning.
- `poll` must not parse payload or diagnostics.
- `shutdown` closes local queue resources and wakes waiters; it is not adapter
  lifecycle truth.

### Adapter-Owned Consumer Contract

The concrete type names can change during implementation, but the owner seam
must be equivalent to:

```java
public interface AdapterMailboxConsumer {
    String adapterMailboxKey();

    void start();

    void stop();
}

public interface AdapterMailboxClient {
    List<DispatchRoutingItem> poll(String adapterMailboxKey,
                                   int maxItems,
                                   long timeoutMillis) throws InterruptedException;
}

public interface DeliveryFailureEvidenceSink {
    void accept(List<DispatchOutcome> outcomes);
}
```

Rules:

- `AdapterMailboxConsumer` is adapter-host owned, not transport-registry
  resolved.
- `AdapterMailboxConsumer.start/stop` controls only the local mailbox poll-loop
  resource. It is not adapter health, lifecycle, failover, restart, or
  migration truth.
- `AdapterMailboxClient` may delegate to `TransportDispatchHandoff`, but the
  concrete adapter must not receive producer-side queue ownership unless it
  truly owns offer.
- `DeliveryFailureEvidenceSink` accepts final-hop outcomes and publishes only
  retryable failure evidence. Delivered/success outcomes may be logged or
  observed in tests, but must not be written as failure evidence.
- `DeliveryFailureEvidenceSink` failure after destructive poll does not requeue
  the item; engine attempt timeout remains the recovery path.
- `TransportBinding` must not require or expose `AdapterCommandExecutor`.
- `TransportRuntimeRegistry` must not register or resolve command executors.

### Redis Shape

Target Redis dispatch handoff no longer needs:

```text
mailbox:<mailbox>:inflight-commands
mailbox:<mailbox>:command-retention-deadlines
encoded DispatchHandoffReference
visibility timeout reclaim
complete/ack cleanup
```

Target Redis dispatch handoff may keep:

```text
mailbox:<encodedAdapterMailboxKey>:ready-commands
mailbox-consumers
mailbox-consumer-deadlines
queues
```

`ready-commands` should store `DispatchRoutingItem` values or flat
mailbox-targeted batch fragments directly. `adapterMailboxKey` is in the Redis
queue key and the offer batch target; it must not be duplicated into each item.

If bounded admission requires an additional counter or metadata key, it must be
handoff-private and must not become lifecycle, retry, or recovery truth.

### Mailbox Consumer Availability

Mailbox consumer availability is queue-admission evidence only:

```text
adapterMailboxKey
consumerId
generation
deadline
```

It is not adapter health, not adapter lifecycle, not failover ownership, and
not worker reachability truth.

The availability publisher must be owned by the adapter mailbox consumer or
its host context. It should be constructed from mailbox facts directly, not
from `TransportBinding`.

### Adapter Consumption

Push adapter flow:

```text
adapter-owned mailbox consumer
  -> mailboxClient.poll(adapterMailboxKey, maxItems, timeout)
  -> concrete adapter selected-worker final-hop
  -> outcomeSink.accept(outcomes)
```

Polling adapter flow:

```text
polling adapter-owned mailbox consumer
  -> mailboxClient.poll(adapterMailboxKey, maxItems, timeout)
  -> enqueue PollingPendingDeliveryBuffer by selectedWorkerId
  -> outcomeSink.accept(queued/backpressure/unavailable outcomes)
  -> worker poll drains its authenticated selectedWorkerId slot
```

The polling pending buffer remains adapter-owned. It is not the engine to
adapter dispatch handoff.

## Non-Goals

- Do not build external adapter registration, authentication, or process
  supervision.
- Do not add adapter health lifecycle, restart, failover, takeover, or
  migration.
- Do not add reliable queue semantics, dead-letter policy, crash recovery, or
  failure-emission retry ownership to transport.
- Do not change `DispatchRoutingItem` fields unless a direct implementation
  gap proves a missing fact.
- Do not change result-ingress `RoutingEnvelope` behavior.
- Do not move engine retry, reassign, compensation, or task timeout into
  transport.
- Do not add stats/list/count/inspect APIs to the dispatch mainline.
- Do not preserve old claim/ack APIs through compatibility aliases after
  callers move.
- Do not preserve `AdapterCommandExecutor` as a production dispatch callback on
  `TransportBinding`.

## Do Not Start With

Do not start by changing `TransportDispatchHandoff.poll(...)` to destructive
behavior while keeping `AdapterMailboxMount` as the production consumer. That
creates a central destructive-consumption path and makes the owner boundary
worse.

Do not start by adding a second helper that still calls:

```text
handoff.poll(...)
binding.getCommandExecutor().dispatch(...)
handoff.complete(...)
```

That only renames `AdapterMailboxMount`.

Do not start by deleting `DispatchOutcome`. Outcome is the observable delivery
evidence. The target removes queue ack, not known-failure evidence.

Do not start by implementing remote adapter protocol. First make the embedded
Java path express the same owner boundary: adapter host owns mailbox
consumption, transport handoff owns queue mechanics only.

## AOMC-0 - Inventory Current Claim/Ack And Central Dispatch Usage

Goal: classify every production and test usage of claim/ack/inflight and the
central mount before changing the interface.

Scope:

- Inventory `TransportDispatchHandoff.complete(...)`,
  `ClaimedDispatchRoutingBatch`, `DispatchHandoffReference`,
  `inflight`, `visibilityTimeout`, `reclaimExpiredInflight`, and
  Redis `inflight-commands` / `command-retention-deadlines`.
- Classify each use as one of:
  queue ack, crash/requeue recovery, test fixture, diagnostic support,
  capacity accounting, or stale residue.
- Inventory `AdapterMailboxMount` callers and determine which concrete adapter
  should own each mailbox-consumption loop.
- Inventory `TransportBinding.getCommandExecutor()`,
  `TransportRuntimeRegistry.registerCommandExecutor(...)`,
  `TransportRuntimeRegistry.resolveCommandExecutor(...)`, and tests/guards that
  currently protect the executor-on-binding model.
- Inventory failure emission behavior currently tied to `AdapterMailboxMount`.
- Inventory mailbox consumer availability publication currently tied to
  `TransportBinding`.

Acceptance:

- Inventory proves `complete(...)` does not carry business completion or
  worker-result semantics.
- Inventory proves which code paths are central mount wiring and which are
  adapter-local final-hop code.
- Inventory identifies tests that must be rewritten because they currently
  protect reliable queue behavior or central mount behavior rather than
  best-effort adapter-owned delivery executor behavior.
- Inventory confirms whether capacity accounting needs a replacement that does
  not require inflight.

## AOMC-1 - Adapter-Owned Destructive Mailbox Consumption

Goal: replace central claim/ack dispatch with destructive mailbox poll owned by
adapter-contributed consumers.

This is the first production implementation slice. It intentionally combines
the handoff contract pivot and adapter ownership pivot so no central
destructive-consumption intermediate state can land.

Scope:

- Change `TransportDispatchHandoff.poll(...)` to return
  `List<DispatchRoutingItem>`.
- Add `maxItems` to poll so adapter hosts can batch without claim wrappers.
- Delete `complete(...)` from the dispatch handoff contract.
- Update in-memory handoff to store mailbox queues of `DispatchRoutingItem`
  and remove items destructively on poll.
- Update Redis handoff to store direct ready item values and remove items
  destructively on poll.
- Remove `ClaimedDispatchRoutingBatch`, `DispatchHandoffReference`, inflight
  maps/zsets, visibility timeout, reclaim, command retention deadline indexes,
  and tests that prove requeue after unacked claim.
- Keep `DispatchRoutingBatch` as producer offer shape and
  `DispatchRoutingItem` as item shape.
- Introduce adapter-owned mailbox consumer contribution or equivalent seam.
- Replace production `AdapterMailboxMount` wiring with adapter-contributed
  mailbox consumers.
- Remove `AdapterCommandExecutor` from `TransportBinding` production contract.
- Remove command-executor registration and resolution from
  `TransportRuntimeRegistry`.
- WebSocket adapter owns its mailbox loop and calls its selected-worker send
  path.
- Socket adapter owns its mailbox loop and calls its selected-worker send path.
- Polling adapter owns its mailbox loop and enqueues
  `PollingPendingDeliveryBuffer`.
- Move mailbox consumer availability publication to adapter mailbox consumer
  context. It must be constructed from mailbox facts, not from
  `TransportBinding`.
- Add or reuse a narrow `DeliveryFailureEvidenceSink` for adapter-owned loops
  to emit retryable failure evidence when possible.
- If a shared embedded Java helper remains, it must be adapter-host scoped:
  it can poll a provided mailbox key and invoke a provided final-hop callback,
  but it must not resolve `TransportBinding`, inspect registries, own endpoint
  leases, parse payload, or decide retry policy.

Acceptance:

- `TransportDispatchHandoff` has only `offer`, destructive `poll`, and
  `shutdown`.
- No production code calls dispatch handoff `complete(...)`.
- No production dispatch handoff code imports `ClaimedDispatchRoutingBatch` or
  `DispatchHandoffReference`.
- In-memory and Redis poll are mailbox-scoped and destructive.
- Offer still returns per-item `DispatchOutcome` for shutdown, unavailable
  mailbox, invalid target, and backpressure.
- Redis keyspace no longer contains dispatch `inflight-commands` or
  `command-retention-deadlines`.
- Handoff code does not parse worker payload.
- Existing `DispatchRoutingItem` field ownership remains unchanged.
- `MassApplication` / embedded host assembly does not own a central
  poll-dispatch-complete loop.
- No production class named or acting as `AdapterMailboxMount` owns dispatch
  for all concrete adapters.
- Concrete adapter modules contain or contribute their own mailbox-consumption
  loop or explicit adapter-host consumer.
- `TransportBinding` does not require or expose `AdapterCommandExecutor`.
- `TransportRuntimeRegistry` does not register or resolve command executors.
- WebSocket and socket use `selectedWorkerId` only for local final-hop lookup.
- Polling adapter enqueue into `PollingPendingDeliveryBuffer` is the polling
  final-hop attempt.
- Mailbox consumer availability is published by adapter mailbox consumer
  context and remains queue-admission evidence only.
- Adapter host known failures produce `DispatchOutcome` or retryable failure
  evidence when possible.
- If failure evidence writing fails after destructive poll, transport does not
  requeue; engine attempt timeout remains the recovery path.

## AOMC-2 - Docs, Guards, And Residue Removal

Goal: make the new owner boundary hard to regress.

Scope:

- Update `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  `doc/PROOF_REGISTRY.md`, and `doc/INFRA_TRUTH_LAYERS.md`.
- Update active roadmaps that still say dispatch handoff owns
  claim/ack/inflight, central adapter mount dispatch, or binding-owned
  command executors.
- Add architecture guards against reintroducing:
  `complete(...)` on `TransportDispatchHandoff`,
  `ClaimedDispatchRoutingBatch`, `DispatchHandoffReference`, dispatch
  `inflight`, dispatch visibility timeout, central `AdapterMailboxMount`
  ownership, `TransportBinding.getCommandExecutor()`,
  `TransportRuntimeRegistry.registerCommandExecutor(...)`, and production
  command-executor dispatch through binding.
- Ensure residue scans do not confuse result-ingress `complete(...)` or engine
  work-runtime leases with removed dispatch handoff ack.
- Rewrite or delete tests that protect old central mount semantics.

Acceptance:

- Active owner docs say transport dispatch handoff is destructive mailbox poll.
- Active owner docs say adapter host owns mailbox consumption and final-hop
  outcome emission.
- Active owner docs say transport does not recover adapter crash or failure
  evidence write failure after destructive poll.
- Architecture guard fails if dispatch handoff ack/inflight returns to
  production code.
- Architecture guard fails if `AdapterMailboxMount` or an equivalent central
  transport-runtime dispatcher is reintroduced.
- Architecture guard fails if `TransportBinding` regains command-executor
  ownership or `TransportRuntimeRegistry` regains command-executor indexing.
- Broad residue scan finds old terms only in archived roadmaps or explicit
  negative guards.

## Failure Semantics

Known failures before or during offer:

```text
shutdown
mailbox unavailable
invalid target
queue backpressure
```

These return `DispatchOutcome` from `offer(...)`.

Known failures during adapter final-hop:

```text
selected worker has no local session
push send fails synchronously
polling pending buffer rejects admission
adapter local validation rejects item
```

These are emitted by adapter host as `DispatchOutcome` or retryable failure
evidence when possible.

Unobserved or post-poll failures:

```text
adapter process crashes
host crashes after poll before writing failure evidence
worker never polls
worker never returns result
failure evidence channel is unavailable
```

These are engine-owned timeout/retry/reassign/compensation cases. Transport
does not requeue or retain inflight recovery state for them.

## Verification Candidates

Compile:

```powershell
.\mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile
```

Runtime handoff proof:

```powershell
.\mvnw -q -pl transport/transport_runtime -am test "-Dtest=InMemoryTransportDispatchHandoffTest,RedisTransportDispatchHandoffTest,TransportDispatchBatchCodecTest,TransportConvergenceArchitectureGuardTest,TransportRedisKeyspaceGuardTest"
```

Adapter ownership proof:

```powershell
.\mvnw -q -pl transport/transport_runtime,transport/websocket-adapter,transport/socket-adapter,transport/polling-adapter,sdk/xa-mass-embedded-sdk -am test "-Dtest=TransportRuntimeRegistryTest,TransportAdapterContributionTest,EmbeddedAdapterHostSetTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest,PollingDeliveryExecutorTest,PollingDeliveryPullChannelTest,MassApplicationDistributedTransportTest,MassSdkTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Polling E2E proof:

```powershell
.\mvnw -q -pl xa-mass-server -am test "-Dtest=ExternalWorkerPollingApiIntegrationTest#pollingWorkersSharingRouteAndQueueCannotCrossConsumeSelectedWorkerItems" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Residue scans:

```powershell
rg -n "ClaimedDispatchRoutingBatch|DispatchHandoffReference|TransportDispatchHandoff\\.complete|complete\\(ClaimedDispatchRoutingBatch|inflight-commands|command-retention-deadlines|visibilityTimeout|reclaimExpiredInflight" transport sdk xa-mass-server doc roadmap --glob "!**/target/**" --glob "!doc/archive/**"
rg -n "AdapterMailboxMount|binding\\.getCommandExecutor\\(|getCommandExecutor\\(|registerCommandExecutor|resolveCommandExecutor" transport sdk xa-mass-server doc roadmap --glob "!**/target/**" --glob "!doc/archive/**"
```

Expected allowed residue after completion:

- archived roadmaps
- explicit negative architecture guards
- unrelated engine work-runtime inflight/lease terminology
- result-ingress `complete(...)` semantics

## Completion Criteria

- `TransportDispatchHandoff` is a bounded offer plus destructive mailbox poll
  queue contract.
- `complete(...)` is gone from dispatch handoff production code.
- `ClaimedDispatchRoutingBatch`, `DispatchHandoffReference`, dispatch
  inflight state, dispatch visibility timeout, and dispatch requeue are gone
  from assigned-dispatch production code.
- `TransportBinding` no longer owns command executors.
- `TransportRuntimeRegistry` no longer registers or resolves command
  executors.
- Adapter hosts own mailbox poll loops and final-hop outcome/failure evidence
  emission.
- Polling adapter still uses `PollingPendingDeliveryBuffer` internally, and
  polling workers sharing one mailbox cannot cross-consume selected-worker
  items.
- Push adapters still use selected-worker local session lookup and do not read
  mailbox key, route key, endpoint lease id, connection id, or adapter id from
  dispatch items.
- Known offer and final-hop failures remain observable as `DispatchOutcome` or
  retryable failure evidence when possible.
- Adapter crash, worker non-consumption, missing result, and post-poll failure
  evidence write failure are documented as engine attempt timeout/retry
  concerns.
- Owner docs and proof registry match the destructive-poll, adapter-owned
  mailbox consumption boundary.
