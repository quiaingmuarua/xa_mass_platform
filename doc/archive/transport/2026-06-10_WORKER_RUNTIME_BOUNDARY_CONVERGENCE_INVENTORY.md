# Worker Runtime Boundary Convergence Inventory

Status: archived WRB inventory for
`2026-06-10_WORKER_RUNTIME_BOUNDARY_CONVERGENCE_ROADMAP.md`.

Last refreshed: 2026-06-10.

Trust order: current code first, then transport/engine owner docs, then roadmap
target direction. This file records the implementation facts and proof gaps that
were current during WRB execution; current truth now lives in the owner docs and
proof indexes.

## Scope

This inventory covers the worker route/reachability boundary across:

- `transport/transport_api`
- `transport/transport_runtime`
- `transport/polling-adapter`
- `transport/websocket-adapter`
- `transport/socket-adapter`
- `sdk/xa-mass-embedded-sdk`
- `sdk/xa-mass-java-sdk`
- `xa-mass-server`
- `xa-mass-engine`
- `xa-mass-worker-runtime`
- `platform_infra/mass-runtime-redis`

Out of scope: task lifecycle queue/lease/result truth and worker capability
truth. Those owners may consume worker reachability, but this inventory does
not move their ownership.

## Current Owner Classes

| Fact / path | Current owner | Classification | Notes |
| --- | --- | --- | --- |
| Worker schedulable identity | `xa-mass-worker-runtime` / engine worker registry consumers | Canonical scheduling truth | Worker identity is `workerGroupId + workerId` on the scheduling path. |
| Worker route reachability | `WorkerPresenceStore` in transport | Transport runtime truth | Current storage is keyed by canonical `routeKey`; `adapterId` is owner value evidence. |
| `WorkerPresenceStore#getPresence(workerId)` | transport projection | Compatibility projection | Returns latest/current worker projection; not enough for route-owner proof. |
| `WorkerPresenceStore#currentOwner(routeKey)` | transport route-owner view | Current dispatch route view | Dispatch selector reads this after engine matching selected a worker. |
| `WorkerPresenceStore#findOwners(workerId)` | transport projection | Compatibility/operator projection | Derived from active presences; not on dispatch route selection. |
| `WorkerPresenceStore#listActivePresences()` | transport projection | Cleanup/display/support | Redis implementation uses route set scans for active list/prune. |
| `WorkerPresenceStore#isWorkerOnline(workerId)` | transport projection | Operator/SDK display | Default uses `getPresence(workerId)`; not a scheduling owner. |
| `TaskDispatchBinding#workerId` | engine assignment binding | Current dispatch worker evidence | Present on all worker-level bindings. |
| `TaskDispatchBinding#workerGroupId` | engine assignment binding | Current route-key input | Production route selection requires worker group evidence; missing group does not silently fall back to raw worker id. |
| `CanonicalWorkerRouteKeyCodec` | `transport_api` | Current route-key contract owner | Mints opaque route key from `workerGroupId + workerId`. |
| `TransportRouteKeyResolver` | transport runtime assembly | Current route-key assembly owner | Production adapter bootstraps use `TransportRouteKeyResolvers.canonicalWorkerSubject()`. |
| Delivery queue key | `TransportDeliveryStore` | Transport queue ownership | Current queue ownership is canonical `routeKey`; `adapterId` remains request/value metadata. |
| `WorkerHeartbeatProjectionListener` | embedded SDK listener | Compatibility heartbeat projection | Current embedded listener converts process-local worker online/heartbeat events into worker-registry heartbeat evidence. It does not own transport reachability or worker declaration truth. |

## Transport Presence Redis Families

Owner implementation: `RedisWorkerPresenceStore`.

| Key family | Current shape | Classification | Current behavior |
| --- | --- | --- | --- |
| `owner:{shard}` | HASH | Canonical route-owner truth | Field is canonical `routeKey`; value stores worker, adapter, route, node, connection, state, lease, update time. |
| `deadline:{shard}` | ZSET | Derived stale/prune index | Member is canonical `routeKey`; score is lease expiry. |
| `worker-route:{workerId}` | STRING | Derived worker projection | Maps worker id to the current route key for bounded compatibility reads. |
| `workers` | SET | Projection/list index | Supports worker projection bookkeeping. |
| `owner-shards` | SET | Diagnostics/index bookkeeping | Records touched owner shards; not route-owner truth. |

The old `route-presence:*`, `route:*`, and `worker-routes:*` families are no
longer current write targets in `RedisWorkerPresenceStore`. Any live runtime
Redis instance using them is pre-convergence data and should be recreated or
cleaned by an explicit runtime cutover step, not dual-written.

## Worker Runtime Redis Families

Owner implementation: `RedisWorkerRegistry` and `RedisWorkerRegistryKeyspace`.

| Key family | Current shape | Classification | Current behavior |
| --- | --- | --- | --- |
| `worker:group` | HASH | Derived worker-to-group index | Maps worker id to current group id. |
| `groups` | SET | Group index | Lists known worker groups. |
| `group:{groupId}:slots` | HASH | Canonical worker runtime slot truth | Stores worker slot/runtime metadata by worker id. |
| `group:{groupId}:heartbeat:0` | ZSET | Derived liveness deadline index | Scores workers by heartbeat deadline. |
| `group:{groupId}:bucket:{bucket}:workers` | ZSET | Derived candidate bucket index | Candidate lookup/ranking index. |
| `group:{groupId}:buckets` | SET | Derived candidate bucket catalog | Lists candidate bucket keys for cleanup/update. |
| `group:{groupId}:worker:{workerId}:bucket-membership` | SET | Derived cleanup index | Tracks current bucket membership for one worker. |
| `task:{taskId}:active-workers` | SET | Runtime occupancy/release index | Tracks active workers per task. |

This keyspace is separate from transport presence. It owns runtime worker slot
and candidate facts, not adapter connection ownership. WRB must not fold
transport presence into `group:{groupId}:slots` without a separate approved
runtime-state roadmap.

## Read Path Classification

| Caller/path | Reads | Classification | Notes |
| --- | --- | --- | --- |
| `WorkerDispatchRouteSelector#selectRoute` | `currentOwner(canonical routeKey)` | Dispatch route selection | Current post-assignment route selection. Requires worker group + worker id evidence. |
| `NodeTargetedTaskDispatchSubmitter` | route selector output | Dispatch route selection | Sends assigned batch to route owner's transport node. |
| `MassApplication` reachability assembly | `currentOwner(canonical routeKey)` | Reachability evidence | Reads worker-runtime worker group evidence, then bounded route-owner presence. |
| `MassSdkApplication#isWorkerOnline` | `WorkerPresenceStore#isWorkerOnline` | Operator/SDK display | Projection only. |
| E2E tests under `xa-mass-server` | `app.isWorkerOnline(...)` | Proof/support depending test | Presence observation, not route-owner proof by itself. |
| Redis `listActivePresences` | owner shard scan | Cleanup/display | Named scan path; not allowed as hot-path `currentOwner(routeKey)`. |
| Redis `pruneExpired` | deadline shard zsets | Cleanup | Bounded by shard deadline indexes. |

## RouteKey Generation And Consumption

Current route-key producer/consumer map:

| Path | Current routeKey source | Classification |
| --- | --- | --- |
| `TransportRouteKeyResolvers.canonicalWorkerSubject()` | `CanonicalWorkerRouteKeyCodec.encode(workerGroupId, workerId)` | Current production policy. |
| `TransportDispatchRouteContext` | task/binding snapshot carrying worker id and worker group id | Carrier for route-key resolution. |
| `TransportBinding#resolveRouteKey` | configured `TransportRouteKeyResolver` | Runtime assembly owner. |
| `polling-adapter` bootstrap | `TransportRouteKeyResolvers.canonicalWorkerSubject()` | Current production path. |
| `websocket-adapter` bootstrap | `TransportRouteKeyResolvers.canonicalWorkerSubject()` | Current production path. |
| `socket-adapter` bootstrap | `TransportRouteKeyResolvers.canonicalWorkerSubject()` | Current production path. |
| `WebSocketWorkerSession` in Java SDK | `CanonicalWorkerRouteKeyCodec` from worker group + worker id | Current public SDK path. |
| WebSocket handshake/query | explicit `routeKey` required; public SDK supplies canonical route key | Adapter-local ingress evidence; no workerId fallback. |
| WebSocket/socket frame fields | frame/session-provided route key | Adapter-local evidence; no workerId fallback for connection registration. |
| Delivery `drain/poll` | caller-supplied route key, adapter retained as request metadata | Queue consumer path. |

WRB-1 target contract: the platform worker route key is minted from
`workerGroupId + workerId` through `CanonicalWorkerRouteKeyCodec`. `adapterId`,
`transportNodeId`, and `connectionId` remain delivery-owner value evidence, not
route-key identity.

## Connection Owner Tokens

| Path | Current connectionId source | Classification |
| --- | --- | --- |
| `PollingWorkerAdapter` | caller-provided session token | Current owner token. |
| `PullWorkerSession` in embedded SDK | generated or caller-provided session token | Current owner token. |
| Java SDK `PollingWorkerSession` via server API | generated session token preserved across online/heartbeat/offline | Current public API path. |
| `ExternalWorkerApiController` online/heartbeat/offline | requires request `sessionToken` and delegates it | Current public API path. |
| `ExternalWorkerPresenceApiRequest` | `sessionToken` plus reason | Current public contract. |
| WebSocket adapter server sessions | channel id / endpoint id | Real session token. |
| Socket adapter sessions | endpoint id | Real session token. |
| Redis presence store | accepts nullable connection id and normalizes it | Store support; caller quality varies. |

WRB-2 must ensure every production adapter/session path has a real session or
epoch owner token. Public Java SDK and server worker API must be included; an
internal transport-only migration would leave a bypass path.

## Delivery Queue Ownership

Current delivery queue contract:

```text
TransportDeliveryStore.enqueue(envelope)
TransportDeliveryStore.drain(adapterId, routeKey, maxItems)
TransportDeliveryStore.poll(adapterId, routeKey, maxItems, timeout)
DeliveryQueueKey equality/hash = routeKey
```

Queue ownership is now route-key based. `adapterId` remains the concrete
delivery request metadata and diagnostic evidence, but it is not the queue
subject. This allows a worker that takes over the same canonical route key
through a different adapter to drain already queued envelopes for that route
instead of stranding them in an old adapter-specific queue.

## Public API And SDK Gaps

| Surface | Current behavior | Gap |
| --- | --- | --- |
| `ExternalWorkerApiController` online/heartbeat/offline | requires `sessionToken`, binds worker id from credential/context | Current public path. |
| Java SDK `WorkerClient` presence methods | `workerId + sessionToken + reason` | Current public path. |
| Java SDK `PollingWorkerSession` | generated token across online/heartbeat/offline | Current managed path. |
| Java SDK `WebSocketWorkerSession` | canonical route key from worker group + worker id | Current managed path. |
| Embedded SDK `WorkerClientOperations` | `workerId + sessionToken + reason` | Current embedded facade. |
| Embedded `PullWorkerSession` | canonical route key and session token | Current embedded pull path. |

## Proof Gaps To Carry Forward

1. `getPresence(workerId)` and `isWorkerOnline(workerId)` remain compatibility
   projections for display/support; they must not be promoted to scheduling or
   route-owner truth.
2. `listActivePresences()` remains a cleanup/display scan and must not enter
   scheduling or dispatch route selection.
3. Some protocol-level adapter tests still use explicit simple route strings
   such as `socket-route-9` or `ws-route-1`. These are adapter ingress fixtures,
   not production route-key generation paths; production binding helpers use
   `TransportRouteKeyResolvers.canonicalWorkerSubject()`.
4. Full archive readiness still needs one final end-to-end proof summary tying
   transport presence, worker-runtime evidence, engine assignment, dispatch
   delivery, and compensation together without treating residue scans as
   behavior proof.
