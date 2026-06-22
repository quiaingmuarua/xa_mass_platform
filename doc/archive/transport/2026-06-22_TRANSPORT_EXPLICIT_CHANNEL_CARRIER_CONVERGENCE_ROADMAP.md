# Transport Explicit Channel Carrier Convergence Roadmap

Status: archived complete on 2026-06-22.

Completion note: current facts moved into `transport/AGENTS.md`,
`transport/TRANSPORT_BOUNDARY_BASELINE.md`, `doc/PROOF_REGISTRY.md`, and
`doc/TASK_LIFECYCLE_BASELINE.md`. Production dispatch now uses explicit
`AdapterMailboxDispatchBatch` / `DispatchMessage`; result ingress uses explicit
`ResultIngressEntry` / `ResultIngressMessage`.

## Summary

Transport does not currently have a real generic routing runtime. It has
explicit channels:

- assigned dispatch handoff to an adapter mailbox
- result ingress from worker/adapters back to engine result convergence
- delivery failure evidence
- system/session evidence

`RoutingTarget(ownerKind, ownerRef)` and `RoutingEnvelope(target, payload)` were
introduced as a generic owner-routed carrier. Current production code did not
converge into that generic shape. Dispatch uses `DispatchRoutingBatch` plus
`DispatchRoutingItem`; result ingress uses `RoutingEnvelope` only with
`result-ingress`.

The goal of this roadmap is to remove the misleading generic routing carrier
from the transport mainline and replace it with explicit channel carriers.
Earlier discussion considered one unified DTO for multiple routes. That is not
the target. The current rule is narrower: carriers at the same transport layer
may use similar field spelling when the fact is truly the same, but class names
and field names stay channel-owned. This is deliberate. A neutral shared DTO
needs hidden context to interpret fields such as `ownerRef`, `subjectId`, or
`correlation`, and that ambiguity has repeatedly caused implementation drift.

Target shorthand:

```text
dispatch handoff:
  AdapterMailboxDispatchBatch(adapterMailboxKey, messages)
  DispatchMessage(deliveryId, selectedWorkerId, correlationRef, payload, deadline, createdAt)

result ingress:
  ingest(ResultIngressEntry(partitionKey, message, diagnostics))
  ResultIngressMessage(resultMessageId, resultCorrelationRef, payload, deadline, createdAt)
```

The important boundary decision is that `RoutingTarget.ownerKind/ownerRef`
should not remain as an abstract layer when every channel already has a
specific owner and address field. The message carrier pattern can be similar
across channels, but field names must stay channel-specific so callers do not
need hidden context to understand what an id means.

## Current Code Observations

- `RoutingEnvelope` lives in `transport_api` as a generic
  `envelopeId + RoutingTarget + payload + diagnostics + createdAt` carrier.
- `RoutingTarget` stores `ownerKind + ownerRef` and currently exposes adapter,
  adapter-mailbox, engine, and result-ingress helpers.
- Assigned dispatch does not use `RoutingEnvelope`. It uses
  `DispatchRoutingBatch(RoutingTarget.adapterMailbox(adapterMailboxKey), items)`.
- `DispatchRoutingBatch` immediately rejects any target whose owner kind is not
  `adapter-mailbox`, so the `RoutingTarget` abstraction adds indirection but no
  real polymorphism on the dispatch path.
- `TransportDispatchHandoff.poll(...)` already takes `adapterMailboxKey`
  directly. Only `offer(...)` still wraps that key in `RoutingTarget`.
- `DispatchRoutingItem` is the item-level assigned delivery fact consumed by
  adapter-owned mailbox consumers and concrete adapter command executors.
- Result ingress currently uses `RoutingEnvelope(target=result-ingress:<ref>)`
  through `TransportResultIngressChannel`, `BufferedTransportResultIngressChannel`,
  `RedisTransportResultIngressChannel`, WebSocket/socket result readers, and
  starter-owned `TaskResultCallbackCodec`.
- `TaskResultCallbackCodec` validates that the envelope target owner ref
  matches the payload `resultCorrelationRef`, which means result ingress needs
  an explicit `resultCorrelationRef` field rather than a generic target object.
- Current owner docs and proof registry still describe `RoutingEnvelope` as the
  result-ingress carrier. This roadmap intentionally supersedes that carrier
  direction; those docs must be updated in the slice that changes each channel.

## Owner Review

Transport owns channel mechanics:

- dispatch queue admission and mailbox handoff
- adapter mailbox destructive poll mechanics
- dispatch outcome and failure evidence
- result ingress queueing and buffering

Transport does not own a generic owner-routing plane. It should not expose
`ownerKind` as if dispatch, result ingress, system events, and raw/manual
channels are interchangeable. If a channel needs a queue address, the channel
contract should name that address directly.

Engine/starter owns task assignment facts and result callback decoding.
Worker-runtime owns selected worker to adapter mailbox evidence.
Concrete adapters own final-hop protocol IO and result frame/request parsing.

## Boundary Decision

Remove `RoutingTarget` and generic `RoutingEnvelope` from the transport
mainline. Replace the current generic routing carrier with explicit channel
message values plus explicit channel addresses:

- dispatch handoff uses an adapter-mailbox batch carrier with
  `adapterMailboxKey` as a named batch field
- result ingress uses a named `partitionKey` argument or outer batch field
- dispatch carries `DispatchMessage`
- result ingress carries `ResultIngressMessage`
- channel APIs should not accept `ownerKind`
- channel codecs should not serialize `ownerKind/ownerRef`

This is not a payload-schema convergence. Payload remains opaque to transport.

This roadmap supersedes only the generic carrier decision from the active
adapter-mailbox routing roadmap. It does not undo the adapter-mailbox dispatch
boundary, selected-worker delivery constraint, adapter-owned mailbox
consumption, or worker-runtime `selectedWorkerId -> adapterMailboxKey`
evidence direction.

## Module Ownership

- `DispatchMessage` and `AdapterMailboxDispatchBatch` are dispatch-handoff
  runtime contracts and should live with `transport_runtime.delivery` unless a
  real external caller requires promotion.
- `ResultIngressMessage`, `ResultIngressDiagnostics`, and any result ingress
  entry/record type belong with `transport_api.channel` if they appear in
  `TransportResultIngressChannel` or `TransportResultIngressHandler`.
- Starter-owned result callback decoding stays in SDK/starter. Transport
  carries result ingress records and diagnostics but must not decode task id,
  message id, attempt id, success, result code, retry policy, or finality.
- Concrete adapters may construct result ingress records from protocol frames,
  but they must not parse task-result semantics beyond recognizing the result
  frame shell and copying the opaque payload.

## Channel-Specific Message Models

A shared neutral `TransportMessage(subjectId, ...)` reduces DTO count, but it
reintroduces the same context-dependent naming problem that made
`RoutingTarget.ownerRef` drift. Dispatch and result ingress should instead use
separate public records with explicit field names.

The repeated shape is acceptable only at the layer of primitive facts:
`payload`, `createdAtEpochMillis`, and `deadlineEpochMillis` can use the same
spelling when they mean the same thing. The record names and identity fields
must remain explicit: `DispatchMessage.deliveryId` is not
`ResultIngressMessage.resultMessageId`, and `selectedWorkerId` has no result
ingress twin.

Dispatch message:

```java
public record DispatchMessage(
        String deliveryId,
        String selectedWorkerId,
        String correlationRef,
        String payload,
        long deadlineEpochMillis,
        long createdAtEpochMillis
) {}
```

Result ingress message:

```java
public record ResultIngressMessage(
        String resultMessageId,
        String resultCorrelationRef,
        String payload,
        long deadlineEpochMillis,
        long createdAtEpochMillis
) {}
```

Result ingress sidecar:

```java
public record ResultIngressDiagnostics(
        String traceId,
        String adapterId,
        String routeKey
) {}

public record ResultIngressEntry(
        String partitionKey,
        ResultIngressMessage message,
        ResultIngressDiagnostics diagnostics
) {}
```

The exact sidecar class name may be adjusted during implementation, but the
role is mandatory. Diagnostics are not task-result truth and must not be added
to `ResultIngressMessage`, but current trace/MDC and bounded adapter diagnostics
need a legal carrier when `RoutingEnvelope.diagnostics` is removed.

These records intentionally live in separate files if they are public Java
types:

```text
DispatchMessage.java
ResultIngressMessage.java
```

Do not introduce a wrapper such as `TransportMessages.DispatchMessage` just to
share one source file. These are channel contracts, not utility tuples.

Do not add a fake `subjectId` to `ResultIngressMessage` only to match
`DispatchMessage` field count. Result ingress has no selected-worker target.
The repeated structure should stop at the facts both channels actually own.

Diagnostics must not be added back into either message. Current result ingress
diagnostics such as adapter id, route key, and trace id are bounded channel
sidecar facts. Keep them beside the channel record or in logs, not in the
message value.

Dispatch mapping:

```text
direction:
  engine/starter assignment -> transport dispatch handoff -> adapter -> worker

facts:
  deliveryId       = transport dispatch message identity
  selectedWorkerId = engine-selected delivery constraint
  correlationRef   = dispatch/result correlation
  payload          = opaque worker invocation payload
  deadline
  createdAt

does not contain:
  adapterMailboxKey
  routeKey/connection/session facts
  result success/result code/output
```

Result ingress mapping:

```text
direction:
  worker/adapter -> transport result inbox -> starter/engine result apply

facts:
  resultMessageId      = result ingress message identity
  resultCorrelationRef = starter/engine result correlation
  payload              = opaque result payload
  deadline             = 0 unless the result inbox explicitly supports it
  createdAt

does not contain:
  partitionKey
  selectedWorkerId
  result success/result code/output
  task-result decoded fields
```

The address stays outside the message:

- dispatch address: `adapterMailboxKey`
- result address: `partitionKey`

This is the key difference from the old `RoutingTarget`: the channel names its
address directly and the message value stays channel-neutral.

## Target Shape

### Dispatch

Replace:

```java
public record DispatchRoutingBatch(
        RoutingTarget target,
        List<DispatchRoutingItem> items
) {}
```

with:

```java
public record AdapterMailboxDispatchBatch(
        String adapterMailboxKey,
        List<DispatchMessage> messages
) {}
```

The batch name may be adjusted during implementation, but it must name the
channel/address directly. The target field must not be a generic
`ownerKind/ownerRef` pair.

`TransportDispatchHandoff` becomes:

```java
List<DispatchOutcome> offer(AdapterMailboxDispatchBatch batch);

List<DispatchMessage> poll(String adapterMailboxKey,
                           int maxItems,
                           long timeoutMillis) throws InterruptedException;
```

`DispatchRoutingItem` should converge into `DispatchMessage` in the dispatch
slice. This is not a compatibility rename; update in-repo callers and remove
the old item type from production.

### Result Ingress

Replace:

```java
TransportResultIngressChannel.ingest(RoutingEnvelope envelope)
```

with:

```java
TransportResultIngressChannel.ingest(ResultIngressEntry entry)
```

`partitionKey` is queue partitioning input, not task result truth. First-slice
implementations may set it to `entry.message().resultCorrelationRef()` if no
stronger partition contract exists. Transport must not decode `correlationRef`
to recover task id, message id, attempt id, or lifecycle truth.

`TaskResultCallbackCodec` validates `message.resultCorrelationRef()` against the
opaque payload result correlation, then decodes starter-owned task result
callback facts.

`AdapterResultIngressSink` and adapter bootstrap ingress capabilities must move
with this API. Concrete adapters should receive the same explicit result
ingress entry contract that `TransportResultIngressChannel` receives; they
must not keep a private `RoutingEnvelope` compatibility path.

### RoutingTarget And RoutingEnvelope

After both channels stop using them, delete or quarantine:

- `RoutingTarget`
- `RoutingOwnerKinds`
- `RoutingEnvelope`
- `RoutingEnvelopeCodec`

Do not keep compatibility aliases inside the repo.

If a later external adapter protocol needs a generic remote envelope, define it
then from concrete remote process requirements. Do not keep today's generic
types as speculative design inventory.

## Non-Goals

- Do not change worker selection, worker-runtime mailbox evidence, or endpoint
  lease ownership.
- Do not change worker-facing invocation payload schema in this roadmap.
- Do not decode task result payloads in transport.
- Do not add a generic typed `Envelope<T>` abstraction unless a real caller
  family needs it.
- Do not introduce compatibility overloads that keep both `RoutingEnvelope` and
  `ResultIngressMessage` result ingress live.
- Do not change result retry/finality semantics; this is a carrier boundary
  cleanup.
- Do not reintroduce `TransportMessage`, `subjectId`, or another neutral id
  field that requires hidden channel context to interpret.

## Do Not Start With

Do not start by adding a new wrapper around `RoutingEnvelope`.

The first slice should make one channel explicit and remove one generic field
from a hot path. A wrapper that still carries `RoutingTarget` preserves the
same misunderstanding under a new name.

## ERC-0 Inventory And Proof Baseline

Scope:

- Inventory production uses of `RoutingEnvelope`, `RoutingTarget`,
  `RoutingOwnerKinds`, and `RoutingEnvelopeCodec`.
- Classify each use as dispatch handoff, result ingress, test fixture, doc, or
  stale roadmap residue.
- Verify which docs currently state generic routing target semantics.
- Keep the removed generic routing-carrier roadmap out of active roadmap
  references.

Acceptance:

- Inventory separates production main sources from tests/docs.
- The roadmap names the first implementation slice from real caller count.
- Existing proof commands are identified before code movement.
- Active owner docs may still describe current implementation until the
  relevant slice lands, but they must not describe the old generic carrier as
  the future target state after this roadmap is adopted.

Suggested commands:

```bash
rg -n "RoutingEnvelope|RoutingTarget|RoutingOwnerKinds|RoutingEnvelopeCodec" transport sdk xa-mass-server doc roadmap --glob "*.java" --glob "*.md" --glob "!**/target/**"
rg -n "DispatchRoutingBatch|DispatchRoutingItem|DispatchMessage|ResultIngressMessage|TransportMessage" transport sdk xa-mass-server --glob "*.java" --glob "!**/target/**"
```

## ERC-1 DispatchMessage Handoff

Goal:

Remove `RoutingTarget` from assigned dispatch handoff and converge
`DispatchRoutingItem` into `DispatchMessage`.

Scope:

- Introduce `DispatchMessage`.
- Introduce `AdapterMailboxDispatchBatch` or equivalent direct mailbox batch.
- Change `TransportDispatchHandoff.offer(...)` to accept the direct batch.
- Change dispatch handoff poll and adapter mailbox client methods to return
  `DispatchMessage`.
- Update in-memory and Redis dispatch handoff implementations.
- Update `TransportDispatchBatchCodec` so process-boundary dispatch JSON stores
  `adapterMailboxKey` directly, not `target.ownerKind/ownerRef`, and serializes
  `DispatchMessage` values.
- Update `TaskDispatchRoutingSubmitter`, `TransportAssignedDeliverySubmitter`,
  `AdapterCommandExecutor`, concrete adapter command channels, polling pending
  buffer, tests, and fixtures.

Acceptance:

- Dispatch handoff production code no longer imports `RoutingTarget`.
- Dispatch JSON has no `ownerKind` or `ownerRef`.
- `adapterMailboxKey` appears once per dispatch batch, not in every
  `DispatchMessage`.
- `DispatchRoutingItem` is removed from production code.
- Dispatch code uses `DispatchMessage.selectedWorkerId()` directly and does not
  require a neutral subject-id mapping.
- `DispatchMessage` does not contain `adapterMailboxKey`, route, endpoint,
  connection, session, or task-result fields.
- Focused dispatch handoff tests pass for in-memory and Redis implementations.
- New mandatory tests such as `DispatchMessageTest` must be created in the
  same slice; completion proof must not rely on missing-test suppression.

Verification candidates:

```bash
mvn -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests compile
mvn -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -Dtest='DispatchMessageTest,AdapterMailboxDispatchBatchTest,TransportDispatchBatchCodecTest,InMemoryTransportDispatchHandoffTest,RedisTransportDispatchHandoffTest,TransportAssignedDeliverySubmitterTest,TaskDispatchRoutingSubmitterTest,PollingDispatchRoutingItemCodecTest,PollingDeliveryExecutorTest,WebSocketTaskDispatchChannelTest,SocketTaskDispatchChannelTest' test
rg -n "RoutingTarget|ownerKind|ownerRef|DispatchRoutingItem|TransportMessage|subjectId" transport/transport_runtime/src/main/java transport/polling-adapter/src/main/java transport/socket-adapter/src/main/java transport/websocket-adapter/src/main/java sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter --glob "*.java"
```

## ERC-2 ResultIngressMessage

Goal:

Replace result `RoutingEnvelope` with `ResultIngressMessage` plus explicit result
partition.

Scope:

- Change `TransportResultIngressChannel`, `TransportResultIngressHandler`,
  buffered channel, Redis channel, and result inbox pump to use
  `ResultIngressEntry` or an equivalent explicit result ingress record.
- Change `AdapterResultIngressSink`, `AdapterIngressCapabilities`,
  `TransportAdapterBootstrapContext.ingress()`, WebSocket adapter ingress, and
  socket adapter ingress to use the same explicit result ingress record.
- Replace `RoutingEnvelopeCodec` with a result ingress message codec that
  serializes `partitionKey` outside `ResultIngressMessage` and serializes
  diagnostics as a sidecar, not as message fields.
- Update WebSocket/socket result frame readers and starter
  `TaskResultCallbackCodec`.
- Keep payload opaque to transport.

Acceptance:

- Result ingress production code no longer imports `RoutingEnvelope` or
  `RoutingTarget`.
- Result ingress JSON has direct `partitionKey` plus `ResultIngressMessage`, not
  `target.ownerKind/ownerRef`.
- Starter-owned decode validates `ResultIngressMessage.resultCorrelationRef()` against
  the payload result correlation ref before engine result apply.
- Trace id, adapter id, and route key diagnostics either move through
  `ResultIngressDiagnostics` or are intentionally removed with corresponding
  test/doc updates. They are not silently dropped.
- Result ingress code has no selected-worker or subject-id field.
- Transport result ingress tests prove buffering, Redis claim/complete, codec
  shape, and malformed payload handling.
- New mandatory tests such as `ResultIngressMessageTest` must be created in the
  same slice; completion proof must not rely on missing-test suppression.

Verification candidates:

```bash
mvn -pl transport/transport_api,transport/transport_runtime,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests compile
mvn -pl transport/transport_api,transport/transport_runtime,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -Dtest='ResultIngressMessageTest,ResultIngressDiagnosticsTest,BufferedTransportResultIngressChannelTest,RedisTransportResultIngressChannelTest,WebSocketFrameReadersTest,WebSocketInputProcessorTest,SocketTransportServerTest,SocketTransportFrameCodecTest,TaskResultCallbackCodecTest,RuntimeTaskResultIngestChannelTest' test
rg -n "RoutingEnvelope|RoutingTarget|RoutingOwnerKinds|RoutingEnvelopeCodec|TransportMessage|subjectId" transport sdk/xa-mass-embedded-sdk xa-mass-server --glob "*.java" --glob "!**/target/**"
```

## ERC-3 Delete Generic Routing Residue

Goal:

Remove the old generic carrier vocabulary from production and current owner
docs.

Scope:

- Delete `RoutingEnvelope`, `RoutingTarget`, `RoutingOwnerKinds`,
  `RoutingEnvelopeCodec`, and `DispatchRoutingItem` if no production callers
  remain.
- Update `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  `doc/TASK_LIFECYCLE_BASELINE.md`, and `doc/PROOF_REGISTRY.md`.
- Update or archive roadmap text that still claims dispatch and result ingress
  should share `RoutingEnvelope`.
- Add architecture guard coverage for channel-specific carrier names.

Acceptance:

- No production Java source imports old routing carrier classes.
- Current docs describe explicit dispatch and result carriers.
- Historical roadmaps either point to this roadmap or remain archived-only.
- Guard tests fail if generic `ownerKind/ownerRef` returns to dispatch handoff
  or result ingress mainline.

Guard candidates:

```text
- dispatch handoff codecs must not serialize ownerKind/ownerRef
- result ingress codecs must not serialize ownerKind/ownerRef
- transport result channel APIs must not accept RoutingEnvelope
- dispatch handoff APIs must not accept RoutingTarget
- DispatchMessage must not grow adapterMailboxKey, partitionKey, routeKey,
  connection/session, task result, or decoded task fields
- ResultIngressMessage must not grow adapterMailboxKey, selectedWorkerId,
  routeKey, connection/session, task result decoded fields, or diagnostics
- TransportMessage and subjectId must not return as neutral shared carrier
  vocabulary
```

## Roadmap Completion Criteria

This roadmap is complete only when:

- assigned dispatch handoff no longer uses `RoutingTarget`
- result ingress no longer uses `RoutingEnvelope`
- dispatch carries `DispatchMessage`
- result ingress carries `ResultIngressMessage`
- result ingress diagnostics have an explicit sidecar contract or are removed
  intentionally with proof
- channel addresses remain outside both message values
- old generic routing carrier classes are deleted or moved out of production
- `DispatchRoutingItem` is removed from production or formally superseded by
  `DispatchMessage`
- docs and proof registry describe explicit channel carriers
- focused dispatch and result ingress tests pass
- residue scans show no production old-carrier imports
