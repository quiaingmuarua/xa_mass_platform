# Transport Runtime Tail Convergence Roadmap

Status: completed and archived after implementation.

## Summary

Transport assigned delivery is now much cleaner: engine/starter submits
`AdapterMailboxDispatchBatch`, adapter-owned consumers drain one dispatch queue
key, adapters final-hop by `selectedWorkerId`, and raw side channels have been
removed.

The remaining high-ROI work is not another broad routing rewrite. It is tail
convergence around four nearby runtime residues:

1. result ingress still has a transport-owned queue pump lifecycle
2. Redis result ingress queue mechanics are not aligned with dispatch queue
   primitives
3. session evidence publishing still carries unused or loose parameters
4. endpoint lease write metadata still has duplicated fencing-token vocabulary

This roadmap converges those residues together because they share the same
owner boundary:

```text
adapter owns protocol frame/session observation
transport owns opaque queue/evidence stores and delivery/result carriers
starter/engine assembly owns result-drain lifecycle into engine convergence
worker-runtime owns derived reachability / scheduling evidence
```

This is a tail cleanup roadmap. It must not reopen the already-settled dispatch
route model or turn endpoint evidence into worker lifecycle truth.

## Current Code Observations

### Result ingress

- `TransportResultIngressQueuePump` lives in `transport_runtime` and starts a
  thread through `RuntimeTaskExecutor`.
- `MassApplication` owns when the pump is created, started, stopped, and paired
  with `RuntimeTaskResultIngestChannel`.
- `TransportResultIngressQueuePump` drains `TransportResultIngressQueue` into
  `TransportResultIngressHandler`; the actual result convergence owner is
  engine/starter assembly, not transport core.
- `RedisTransportResultIngressChannel` implements both
  `TransportResultIngressChannel` and `TransportResultIngressQueue`, but it
  manually performs `LLEN -> RPUSH` for offer and `LPOP + sleep` for poll.
- `TransportResultIngressQueue` is keyed by `resultQueueKey`, but both
  in-memory and Redis implementations currently only accept
  `DEFAULT_RESULT_QUEUE_KEY`.
- `EmbeddedAdapterStarter` validates that embedded adapter specs use the
  default result queue key and exposes a result ingress channel that offers to
  that queue.

### Session evidence

- `AdapterSessionEvidencePublisher` receives `adapterId` and
  `adapterMailboxKey`, but `adapterMailboxKey` is only validated and never used.
- `AdapterSessionEvidencePublisher.connected/heartbeat/disconnected(...)`
  receive `traceId`, but that value is not written to endpoint lease evidence
  or downstream sinks.
- `AdapterSessionEvidencePublisher.connected(...)` also calls
  `CurrentSessionConnectSink.currentSessionConnected(...)`; current
  `MassApplication` wires that sink directly to
  `WorkerDispatchRecoveryRuntime.recoverWorkerDispatch(...)`.
- `AdapterSessionEvidencePublisher.disconnected(...)` calls
  `CurrentSessionDisconnectSink.currentSessionDisconnected(...)` only after the
  current endpoint lease release succeeds; current `MassApplication` wires that
  sink to worker-runtime negative dispatch block evidence.
- WebSocket, Socket, and Polling all publish session evidence through this
  publisher, so the API cleanup is a cross-adapter transport-runtime change.

### Endpoint lease

- `TransportEndpointLeaseClaim`, `TransportEndpointLeaseHeartbeat`, and
  `TransportEndpointLeaseRelease` carry both `sessionHandle` and
  `endpointLeaseId`.
- Their short constructors set `endpointLeaseId = sessionHandle`, and current
  `TransportEndpointLeasePublisher` passes one session token into that short
  constructor.
- `TransportEndpointLeaseMetadata` stores both fields, so the store persists two
  values even when current production writers provide one token.
- `MassApplication` still uses `TransportEndpointLeaseStore` to derive
  worker reachability and current endpoint binding:

```text
workerId -> worker resource -> workerGroupId
  -> currentEndpointLease(workerGroupId, workerId)
  -> ONLINE/OFFLINE or adapter binding hint
```

That dependency means endpoint lease store is still live system evidence and
must not be deleted before a worker-runtime-owned replacement exists.

## Current Engine / SDK / Transport Interaction

Current code is already mostly assembly-owned rather than engine-owned, but
there are still tail residues that this roadmap targets.

### Engine To Transport Dispatch

The engine core does not import transport. It accepts a neutral
`TaskDispatchBatchListener` when `EngineRuntimeKernel.start(...)` runs.
`SimpleTaskDispatchBinder` owns worker selection output, runtime work claim, and
`TaskDispatchBinding` creation. After binding, it invokes:

```text
TaskDispatchBatchListener.onTaskDispatchBatch(TaskDispatchContext, bindings)
```

In embedded runtime, `MassApplication` supplies that listener as
`TaskDispatchRoutingSubmitter`. This starter-owned translator:

```text
TaskDispatchBinding.workerId
  -> selectedWorkerId
  -> worker-runtime delivery target evidence
  -> adapterMailboxKey
  -> AdapterMailboxDispatchBatch
  -> TransportAssignedDeliverySubmitter
  -> TransportDispatchQueue.offer(adapterMailboxKey, DispatchMessage...)
```

So engine owns assignment truth. Transport owns the queue offer/poll mechanics.
The translation from engine binding to transport queue target is currently
starter/assembly work.

### Embedded Adapter Startup

`MassApplication` still creates the shared backend ports:

```text
TransportDispatchHandoff
TransportResultIngressQueue
TransportEndpointLeaseStore
RuntimeTaskExecutor
TransportDeliveryFailureHandler
CurrentSessionDisconnectSink
```

It then calls `EmbeddedAdapterStarterDefaults.createStarter(...)` and passes an
`EmbeddedAdapterRuntimeEnvironment` plus adapter specs from
`TransportRuntimeComposition.resolveEmbeddedAdapterRuntimeSpecs()`.

`EmbeddedAdapterStarter` owns:

```text
type -> EmbeddedTransportAdapterRuntimeFactory
adapterId -> EmbeddedTransportAdapterRuntime
adapter bindings -> TransportRuntimeRegistry
start(adapterId) / startAll() / close(adapterId)
```

Concrete adapter factories own the protocol-local runtime:

```text
WebSocketAdapterRuntimeFactory
SocketAdapterRuntimeFactory
PollingAdapterRuntimeFactory
```

They create dispatch consumers, local session registries/managers, result frame
processors, and optional servers. Adapter specs currently carry
`dispatchQueueKey` and `resultQueueKey`; v1 result queue usage is still
default-only.

### Transport To Adapter Dispatch

Adapters do not receive engine bindings directly. Each embedded adapter runtime
starts an `AdapterDispatchQueueConsumerLoop` for its configured
`dispatchQueueKey`. That loop polls the shared `TransportDispatchQueue` and
calls an adapter-local `AdapterCommandExecutor`.

The protocol-specific part is only final-hop send:

```text
DispatchMessage.selectedWorkerId + payload
  -> WebSocketSessionRegistry.sendTextToWorker(...)
  -> SocketSessionManager.sendToWorker(...)
  -> PollingPendingDeliveryBuffer.offer(...)
```

This means the adapter is already the consumer of its mailbox queue; transport
runtime should not add another central dispatcher thread around it.

### Adapter Result To Engine Result

Adapters parse worker-channel result frames and offer opaque
`ResultIngressEntry` values into `TransportResultIngressQueue`.

Today, `MassApplication` also creates and starts `TransportResultIngressQueuePump`
to drain that queue into:

```text
RuntimeTaskResultIngestChannel -> TaskResultIngestFacade
```

That drain lifecycle is not transport-owned. TRT-1 moves it to a starter-owned
drain class while leaving queue contracts and queue implementations in
transport runtime.

### Adapter Session Evidence To Worker Runtime

Adapters publish connect/heartbeat/disconnect through
`AdapterSessionEvidencePublisher`, which writes `TransportEndpointLeaseStore`.
`MassApplication` also reads the endpoint lease store to resolve worker
reachability and current adapter binding when worker-runtime delivery target
evidence is not explicitly configured.

Current residue:

- session connect still goes through `CurrentSessionConnectSink` and directly
  calls `WorkerDispatchRecoveryRuntime.recoverWorkerDispatch(...)`
- session disconnect goes through `CurrentSessionDisconnectSink` only after a
  current endpoint lease release succeeds

The first path is positive recovery leaking from transport into worker-runtime
decision-making and is removed by TRT-3. The second path is a narrower negative
signal and remains until worker-runtime owns a cleaner replacement.

## Owner Review

### Result Ingress

Transport owns the opaque result ingress carrier and queue store:

```text
ResultIngressEntry(partitionKey, ResultIngressMessage)
TransportResultIngressQueue.offer/poll(...)
```

Transport does not own result convergence or the lifecycle of a thread that
drains results into engine. In embedded or engine-producer roles, that drain is
starter/engine assembly work because it wires transport queue output to
`TaskResultIngestFacade`.

Adapter runtime factories may write result entries to the queue. They must not
parse task-result payloads or call engine result APIs directly.

### Session Evidence

Concrete adapters own protocol sessions and observe connect/heartbeat/
disconnect. Transport runtime owns the endpoint evidence write capability. The
publisher API should expose only fields required to write current endpoint
evidence:

```text
AdapterSessionIdentity(deliveryBucketId, workerId)
sessionToken / fencing token
reason
observedAtMillis if the caller owns an observation timestamp
```

`adapterMailboxKey` is not endpoint lease evidence. `traceId` should not appear
on this API unless trace storage actually records it.

Positive session connect is not a transport-owned worker recovery decision. A
transport session connect may refresh endpoint evidence; it must not directly
clear worker-runtime dispatch blocks or reopen eligibility. Positive recovery
must either go through a worker-runtime-owned signal/recheck entry or be left
to worker-runtime's normal explicit evidence paths. Current-session disconnect
may remain a narrow negative signal only when the current endpoint release
proves the disconnected session is still the active session.

### Endpoint Lease

Endpoint lease remains transport-local evidence:

```text
key: deliveryBucketId + workerId
value: endpointDriverId + current-session fencing token
deadline index: lease expiry
```

It protects current endpoint freshness and stale disconnect fencing. It is not
worker lifecycle truth, adapter health truth, dispatch retry policy, or
scheduling admission truth.

If `sessionHandle` and `endpointLeaseId` are not distinct in current production
writers, they should converge into one token name. Keep two fields only if a
real caller, store behavior, or future-proofed decision proves different owner
semantics.

## Boundary Decision

Target ownership after this roadmap:

```text
Adapter runtime:
  - parse protocol-local inbound frames
  - publish session evidence through narrow publisher
  - offer opaque ResultIngressEntry to result queue
  - consume dispatch queue and final-hop selected-worker messages

Transport runtime:
  - own ResultIngressEntry codec and queue store mechanics
  - own dispatch/result queue primitive behavior
  - own endpoint lease store and current endpoint evidence write/read shape
  - not own engine result-drain thread lifecycle

Starter / engine assembly:
  - own result queue drain resource when engine convergence is local
  - wire drained ResultIngressEntry to RuntimeTaskResultIngestChannel
  - decide whether engine-producer consumes a Redis result queue

Worker runtime:
  - own derived reachability/eligibility facts above transport endpoint evidence
  - own positive dispatch recovery/recheck decisions after transport evidence
    changes
```

Concrete decisions for this roadmap:

- delete the direct positive recovery path from transport session connect to
  `WorkerDispatchRecoveryRuntime.recoverWorkerDispatch(...)`; do not introduce
  a replacement worker-runtime signal in this roadmap unless implementation
  proves one is required for correctness
- move result queue drain into starter/engine assembly, with the first concrete
  target being a starter-owned class such as
  `com.xa.mass.starter.TaskResultIngressQueueDrain`
- collapse endpoint lease token vocabulary in this roadmap unless TRT-0 finds
  a production caller that truly needs separate `sessionHandle` and
  `endpointLeaseId`

## Target Shape

### Result Ingress Drain

Move the drain lifecycle out of transport runtime:

```text
TransportResultIngressQueue
  -> starter-owned ResultIngressQueueDrain
  -> RuntimeTaskResultIngestChannel
  -> TaskResultIngestFacade
```

The drain class may live in `sdk/xa-mass-embedded-sdk` or another starter-owned
assembly package. It should not remain a transport-runtime lifecycle owner.

Transport runtime may keep queue contracts and queue implementations. It may
not start or stop result-to-engine drain loops.

### Result Queue Mechanics

Redis result ingress should use the same queue primitive family as dispatch
handoff instead of maintaining hand-rolled capacity and sleep-poll logic.
This means bounded keyed queue store hygiene only; it does not mean result
ingress adopts dispatch handoff lifecycle semantics.

V1 remains one shared result queue:

```text
resultQueueKey = TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY
```

This roadmap does not introduce per-adapter result queues. If multiple result
queues become real product work, a separate roadmap must define engine-side
drain registry ownership and stop order.

Result ingress queue semantics stay intentionally simple:

- destructive poll only
- no claim/ack/reclaim/visibility timeout
- no dispatch mailbox vocabulary
- no `DispatchMessage`, `DispatchOutcome`, or delivery-failure semantics
- no Lua/statistics consistency layer beyond the queue primitive's existing
  bounded-offer behavior

### Session Evidence Publisher

Replace loose evidence calls with identity-first calls:

```java
connected(AdapterSessionIdentity identity, String sessionToken, String reason)
heartbeat(AdapterSessionIdentity identity, String sessionToken, String reason)
disconnected(AdapterSessionIdentity identity, String sessionToken, String reason)
```

The exact method names may remain if call sites are clearer, but the owner rule
is fixed:

- no `adapterMailboxKey` on the publisher constructor
- no `traceId` unless the evidence sink records it
- no direct positive worker-runtime recovery from transport session connect
- no routeKey, connection id, dispatch queue key, result queue key, or endpoint
  lease store internals in adapter-facing evidence methods

TRT-3 must classify and remove or replace `CurrentSessionConnectSink`. The
target in this roadmap is:

```text
connected(identity, token, reason)
  -> claim/refresh transport endpoint evidence only
  -> no direct WorkerDispatchRecoveryRuntime.recoverWorkerDispatch(...)
```

If positive recovery is required, it must move behind a worker-runtime-owned
signal or recheck ingress in the same slice. Leaving the current direct
`CurrentSessionConnectSink -> recoverWorkerDispatch(...)` path in place means
TRT-3 is not complete.

The disconnect path remains negative-only:

```text
disconnected(identity, token, reason)
  -> release endpoint evidence only if token matches current session
  -> if released current endpoint, emit narrow disconnect block evidence
```

### Endpoint Lease Token

Converge endpoint lease write records toward one current-session fencing token:

```java
TransportEndpointLeaseClaim(
    String workerId,
    String deliveryBucketId,
    String endpointDriverId,
    String sessionToken,
    String reason
)
```

`sessionToken` is a transport-local fencing token. It is not a public worker
identity and not an adapter mailbox key.

Keep `endpointLeaseId` only if the implementation proves a second token is
needed. If kept, the roadmap must record the distinct owner and the reason
tests need both.

## Non-Goals

- Do not remove `TransportEndpointLeaseStore` in this roadmap.
- Do not change worker-runtime selection policy or eligibility recovery.
- Do not make endpoint lease evidence a worker online/offline lifecycle owner.
- Do not treat transport session connect as positive worker recovery. Positive
  dispatch recovery belongs to worker-runtime-owned explicit evidence or
  recheck paths.
- Do not implement routeKey removal.
- Do not rename `deliveryBucketId` or solve worker-group bucket policy here.
- Do not introduce per-adapter result queues.
- Do not add adapter health monitoring, restart policy, takeover, or failover.
- Do not merge this roadmap with the due-refresh roadmap. Due refresh may later
  consume the narrowed identity/token vocabulary, but it has a separate proof
  surface.
- Do not rewrite Socket to full WebSocket parity here; only update Socket call
  sites required by shared evidence API changes.

## Do Not Start With

Do not start by deleting endpoint lease fields or deleting
`TransportResultIngressQueuePump`.

Wrong order would either break current worker reachability/binding lookup or
leave result ingress without a drain. Start by moving the drain owner and
inventorying the endpoint token semantics, then remove residue after callers
are retargeted.

## TRT-0 - Inventory And Slice Baseline

Scope:

- inventory all production and test callers of:
  - `TransportResultIngressQueuePump`
  - `TransportResultIngressQueue`
  - `RedisTransportResultIngressChannel`
  - `AdapterSessionEvidencePublisher`
  - `TransportEndpointLeaseClaim/Heartbeat/Release/Metadata`
- record whether any production writer passes distinct `sessionHandle` and
  `endpointLeaseId`
- record whether any consumer needs `traceId` or `adapterMailboxKey` from
  session evidence
- record all `CurrentSessionConnectSink` / `CurrentSessionDisconnectSink`
  production and test callers and classify direct worker-runtime mutation paths
- confirm all result queue keys are still default-only

Acceptance:

- roadmap or sibling inventory records caller classification
- no code behavior changes are required in this slice
- next slice can be implemented without guessing production/test-only usage

## TRT-1 - Move Result Drain Lifecycle Out Of Transport Runtime

Goal:

Transport runtime keeps result queue/carrier mechanics; starter/engine assembly
owns the queue drain into engine result convergence.

Scope:

- move or replace `TransportResultIngressQueuePump` with a starter-owned drain
  resource, for example `TaskResultIngressQueueDrain`
- update `MassApplication` to use the starter-owned drain
- keep `TransportResultIngressQueue` and result queue implementations in
  transport runtime
- update tests that reference the old class name
- update `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  and `doc/PROOF_REGISTRY.md` in the same slice so active docs do not preserve
  transport-owned result drain lifecycle wording

Acceptance:

- `transport_runtime` main sources no longer define or start
  `TransportResultIngressQueuePump`
- transport runtime no longer owns result-to-engine drain lifecycle
- embedded and engine-producer roles still drain result queue entries into
  `RuntimeTaskResultIngestChannel`
- stop order still closes drain before result queue shutdown
- no adapter writes directly to engine result APIs
- active owner docs for result ingress match the new drain owner before this
  slice is considered complete

## TRT-2 - Align Redis Result Queue With Queue Primitive Semantics

Goal:

Redis result ingress queue should have the same bounded offer and poll behavior
family as dispatch handoff, without hand-rolled queue commands.

Scope:

- refactor `RedisTransportResultIngressChannel` to use the existing queue
  primitive family where practical for bounded keyed queue hygiene
- preserve `TransportResultIngressChannel.ingest(entry)` as a producer seam
- preserve `TransportResultIngressQueue.offer(DEFAULT_RESULT_QUEUE_KEY, entry)`
  and `poll(DEFAULT_RESULT_QUEUE_KEY, timeoutMillis)` for the current v1 shape
- keep unsupported non-default keys fail-fast until a multi-result-queue owner
  exists
- keep corrupt result queue payload behavior observable and bounded
- do not add claim/ack/reclaim/visibility timeout, dispatch mailbox vocabulary,
  dispatch payload types, or delivery outcome/failure semantics to result
  ingress
- do not introduce a new Lua/statistics layer unless the queue primitive already
  owns it

Acceptance:

- `RedisTransportResultIngressChannel` no longer implements capacity with
  raw `LLEN -> RPUSH`
- Redis and in-memory result queue tests prove the same v1 default-key
  semantics
- full queue offer returns `false` without throwing
- shutdown/close stops new offers and polls cleanly
- result queue remains destructive-poll only
- no result queue implementation introduces engine/task payload parsing

## TRT-3 - Narrow Adapter Session Evidence Publisher API

Goal:

Session evidence calls should carry only evidence facts. Remove loose unused
arguments before they become accidental API truth.

Scope:

- update `AdapterSessionEvidencePublisher` constructor to remove
  `adapterMailboxKey`
- update `connected/heartbeat/disconnected` to use
  `AdapterSessionIdentity` plus a session token and reason
- remove `traceId` from these calls unless a trace sink is introduced in the
  same slice
- delete `claimEndpoint(...)` unless TRT-0 finds a production caller; test-only
  usage must migrate to `connected(...)` or focused endpoint store tests
- remove `CurrentSessionConnectSink` direct positive recovery, or replace it
  with a worker-runtime-owned signal/recheck ingress in the same slice
- update WebSocket, Socket, Polling, and embedded pull worker session call sites
- update `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  and `doc/PROOF_REGISTRY.md` in the same slice so active docs do not preserve
  the old session evidence owner split

Acceptance:

- `AdapterSessionEvidencePublisher` main source has no `adapterMailboxKey`
  constructor parameter
- publisher methods do not accept `traceId`
- `AdapterSessionEvidencePublisher` no longer exposes `claimEndpoint(...)`
- transport session connect no longer directly calls
  `WorkerDispatchRecoveryRuntime.recoverWorkerDispatch(...)`
- all concrete adapters still publish connect/heartbeat/disconnect evidence
- current-session disconnect still only emits negative worker-runtime evidence
  after current endpoint release succeeds
- active docs and guards reflect the new session evidence API before this slice
  is considered complete
- WebSocket/Socket/Polling focused session evidence tests pass

## TRT-4 - Collapse Or Justify Endpoint Lease Fencing Tokens

Goal:

Endpoint lease write records should not persist two token fields unless they
represent two different facts.

Scope:

- classify `sessionHandle` and `endpointLeaseId` in claim, heartbeat, release,
  metadata, consumer evidence, memory store, and Redis store
- if no distinct production writer/consumer exists, collapse to one token name
  such as `sessionToken`
- if both remain, document the distinct semantics and add a focused test that
  fails if they are accidentally made equal or ignored
- preserve stale disconnect protection and current-session refresh matching
- preserve `MassApplication` reachability and current endpoint binding lookup
- update endpoint lease docs/guards in the same slice if record fields or token
  vocabulary change

Acceptance:

- no endpoint lease record has duplicated token fields without a documented
  owner reason
- Redis encode/decode and in-memory store behavior remain equivalent
- stale release/heartbeat still cannot revoke or refresh a newer session
- endpoint lease view remains diagnostic and does not expose dispatch queue,
  routeKey, connection id, or adapter mailbox facts
- active docs and guards match the endpoint token decision before this slice is
  considered complete

## TRT-5 - Residue Scan, Proof Registry Final Pass, And Archive Readiness

Scope:

- verify `transport/AGENTS.md`, `transport/TRANSPORT_BOUNDARY_BASELINE.md`,
  `transport/WEBSOCKET_ADAPTER_BOUNDARY_BASELINE.md`, and
  `doc/PROOF_REGISTRY.md` already reflect the slices that changed them
- cross-link `TRANSPORT_SESSION_EVIDENCE_DUE_REFRESH_CONVERGENCE_ROADMAP.md`
  if endpoint token vocabulary changes its target shape
- add any missing final architecture guards that could not be safely added in
  earlier slices
- run residue scans for old pump/evidence/token vocabulary

Acceptance:

- active docs already describe result drain lifecycle as starter/engine
  assembly owned
- active docs already remove `TransportResultIngressQueuePump` as a
  transport-runtime owner
- guards forbid:
  - `TransportResultIngressQueuePump` in transport main
  - `adapterMailboxKey` on `AdapterSessionEvidencePublisher`
  - `traceId` on session evidence publisher methods
  - direct `CurrentSessionConnectSink -> recoverWorkerDispatch(...)` positive
    recovery wiring
  - `routeKey` or dispatch queue facts in endpoint lease records
  - duplicated endpoint lease token fields without an explicit allowlisted
    reason
- no active roadmap or baseline contradicts the new result/evidence owner split

## Suggested Implementation Order

1. TRT-0 inventory.
2. TRT-1 move result drain lifecycle out of transport runtime.
3. TRT-2 align Redis result queue with queue primitive semantics.
4. TRT-3 narrow session evidence publisher API.
5. TRT-4 collapse or justify endpoint lease fencing tokens.
6. TRT-5 final residue scan, proof registry pass, and archive readiness.

TRT-1 and TRT-2 may be implemented in the same session if the result queue
tests are stable. TRT-3 and TRT-4 should be separate commits/slices because
they affect every adapter and endpoint lease store codec.

## Verification Candidates

Compile:

```powershell
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/adapter-starter,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am -DskipTests compile
```

Focused transport/runtime tests:

```powershell
.\mvnw.cmd -q -pl transport/transport_api,transport/transport_runtime,transport/adapter-starter,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk -am test "-Dtest=ResultIngressEntryTest,ResultIngressMessageTest,RedisTransportResultIngressChannelTest,BufferedTransportResultIngressChannelTest,AdapterResultIngressEntriesTest,AdapterSessionEvidencePublisherTest,InMemoryTransportEndpointLeaseStoreTest,RedisTransportEndpointLeaseStoreTest,PollingSessionEvidenceDriverTest,WebSocketSessionRegistryTest,WebSocketSessionEvidenceRefresherTest,SocketSessionManagerTest,MassApplicationDistributedTransportTest,MassSdkTest,TransportConvergenceArchitectureGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DtrimStackTrace=true"
```

Residue checks:

```powershell
rg -n "TransportResultIngressQueuePump|adapterMailboxKey.*AdapterSessionEvidencePublisher|traceId.*sessionEvidencePublisher|claimEndpoint\\(|CurrentSessionConnectSink|recoverWorkerDispatch\\(|sessionHandle.*endpointLeaseId|routeKey.*EndpointLease" transport sdk doc roadmap --glob "*.java" --glob "*.md" --glob "!**/target/**" --glob "!doc/archive/**"
```

Expected after completion:

- no `TransportResultIngressQueuePump` in transport main
- no `adapterMailboxKey` or `traceId` session-evidence publisher API
- no `claimEndpoint(...)`
- no direct transport session connect to `recoverWorkerDispatch(...)`
- no endpoint lease duplicated token fields unless allowlisted by TRT-4
- guard strings may remain intentionally in architecture tests

## Roadmap Completion Criteria

This roadmap can be marked complete only when:

- result-to-engine drain lifecycle is owned outside transport runtime
- Redis/in-memory result queues share the same v1 default-key semantics
- session evidence publisher API is identity/token/reason only
- transport session connect no longer directly triggers worker dispatch
  recovery
- endpoint lease token fields are either collapsed or explicitly justified
- active docs and proof registry match the new owner split
- residue scans show old pump/evidence/token vocabulary only in guards or
  archived docs
- focused tests and compile pass
