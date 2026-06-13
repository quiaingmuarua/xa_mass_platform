# Transport Selected-Worker Delivery And Reachability Boundary Inventory

Status: current implementation inventory for
`TRANSPORT_SELECTED_WORKER_DELIVERY_AND_REACHABILITY_BOUNDARY_ROADMAP.md`.

## Boundary Symbols

| Symbol | Current owner | Allowed use | Boundary rule |
| --- | --- | --- | --- |
| `WorkerPresenceIngress` / `WorkerRuntimePresenceIngress` | transport-neutral session presence ingress wired by SDK/starter | Project session connect/heartbeat/disconnect evidence into worker-runtime presence/reachability and refresh registry-owned slot heartbeat on connect/heartbeat | Must not consume transport route-owner lease evidence or write worker resource status / dispatch gates |
| `MassSdkApplication#isWorkerReachable` / `listReachableWorkerIds` | SDK worker inspection | Read worker-runtime reachability through `WorkerSchedulingViewRuntime` | Must not read `WorkerDispatchRouteOwnerView` or `activeOwnerForSelectedWorker(...)` |
| `WorkerReachabilityView` | worker runtime scheduling evidence seam | Engine scheduling read of worker-runtime reachability evidence | Must not be documented as transport route-owner truth |
| `TaskDispatchDeliveryCommandSubmitter` | SDK/starter assignment translator | Convert `TaskDispatchContext + TaskDispatchBinding` into `DeliveryCommand` records carrying `selectedWorkerId` | Must not resolve route-owner leases or transport-node liveness |
| `TransportAssignedDeliverySubmitter` | transport runtime delivery | Resolve `adapterId + selectedWorkerId` through route-owner evidence, verify transport-node owner, group by physical lane, offer delivery-command batches, emit retryable delivery failures | Must not select another worker or mutate worker lifecycle |
| `TransportDeliveryCommandListener` | transport runtime consumer | Deliver already batched commands to local adapters; emit consumer-side failures such as adapter unavailable | Must not compensate producer-side owner lookup failures |

## Failure Ownership

| Failure site | Owner | Emission rule |
| --- | --- | --- |
| Missing selected-worker route owner | `TransportAssignedDeliverySubmitter` | one retryable delivery-failure event for the command |
| Route owner points to unavailable transport node | `TransportAssignedDeliverySubmitter` | one retryable delivery-failure event for the command |
| Delivery-command handoff backpressure/shutdown | `TransportAssignedDeliverySubmitter` | one retryable delivery-failure event per retryable outcome |
| Invalid assignment binding before command creation | `TaskDispatchDeliveryCommandSubmitter` | one retryable delivery-failure event because no transport command can be resolved cleanly |
| Adapter unavailable on the consumer node | `TransportDeliveryCommandListener` | one retryable delivery-failure event for the command |

## Redis Manifest Link

The active transport Redis key manifest lives in
`transport/TRANSPORT_BOUNDARY_BASELINE.md` under `Redis Key Manifest` and is
guarded by `TransportRedisKeyspaceGuardTest`.

Retained transport Redis namespaces:

| Component | Default namespace |
| --- | --- |
| route-owner | `xa:mass:transport:route-owner:v1` |
| delivery | `xa:mass:transport:delivery:v1` |
| nodes | `xa:mass:transport:nodes:v1` |
| delivery-command | `xa:mass:transport:delivery-command:v1` |
| result-inbox | `xa:mass:transport:result-inbox:v1` |
| delivery-failure | `xa:mass:transport:delivery-failure:v1` |

Forbidden under `xa:mass:transport:*`: worker runtime lifecycle truth,
scheduling/admission/capacity/reservation facts, worker metadata truth,
`group:{groupId}:slots`, and deprecated route-owner families such as
`workers`, `owner-shards`, `worker-routes:*`, `worker-route:*`, `routes`, and
`route-presence:*`.
