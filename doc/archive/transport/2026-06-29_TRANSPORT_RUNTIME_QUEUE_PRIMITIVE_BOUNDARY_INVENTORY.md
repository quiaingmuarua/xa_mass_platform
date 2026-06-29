# Transport Runtime Queue Primitive Boundary Inventory

Status: archived completion inventory for
`2026-06-29_TRANSPORT_RUNTIME_QUEUE_PRIMITIVE_BOUNDARY_CONVERGENCE_ROADMAP.md`.

## Symbols

| Symbol | Current Owner | Caller | Classification | Target |
| --- | --- | --- | --- | --- |
| `TransportDispatchQueue` | `transport_runtime` | SDK/starter producer and adapter-owned consumer loops | transport typed port | Keep as assigned-dispatch semantic port. |
| `TransportDispatchHandoff` | `transport_runtime` | formerly SDK/starter config and tests | alias residue | Deleted; callers use `TransportDispatchQueue`. |
| `InMemoryTransportDispatchHandoff` | `transport_runtime` | embedded default dispatch queue and tests | primitive-backed typed adapter with deferred name residue | Keep behavior; class name can be cleaned in a later naming slice. |
| `RedisTransportDispatchHandoff` | `transport_runtime` | distributed dispatch queue factory and tests | primitive-backed typed adapter with deferred name residue | Keep behavior; class name can be cleaned in a later naming slice. |
| `TransportResultIngressQueue` | `transport_runtime` | adapter result sinks and starter-owned result drain | transport typed port | Keep as result ingress queue semantic port. |
| `InMemoryTransportResultIngressQueue` | `transport_runtime` | embedded result ingress path and tests | primitive-backed typed adapter | Retargeted to `InMemoryKeyedBlockingQueueStore`. |
| `RedisTransportResultIngressChannel` | `transport_runtime` | split transport result ingress path and tests | primitive-backed typed adapter | Keep; already uses `RedisKeyedBlockingQueueStore`. |
| `BufferedTransportResultIngressChannel` | `transport_runtime` | test-only after current mainline scan; old docs referenced it | residue | Deleted with tests; no replacement channel. |
| `TaskResultIngressQueueDrain` | `sdk/xa-mass-embedded-sdk` starter assembly | engine/starter result convergence | owner-owned result drain | Keep; it drains `TransportResultIngressQueue`, not a transport-owned queue container. |
| polling pending delivery buffers | `polling-adapter` | polling worker pull path | adapter-local pull buffer | Keep separate from engine-to-transport dispatch/result queues. |

## Production Callers

| Caller | Queue Dependency | Target |
| --- | --- | --- |
| `MassApplication` | `TransportDispatchQueue`, `TransportResultIngressQueue` | Starter assembly owns wiring only. |
| `TransportRuntimeComposition` | dispatch queue factory | Returns `TransportDispatchQueue`. |
| `TransportConfig` | dispatch queue factory | Exposes `Supplier<TransportDispatchQueue>`. |
| `MassApplicationBuilder` / `MassSdk.TransportOptions` | Redis dispatch queue setup | Uses `redisDispatchQueue(...)`, not handoff vocabulary. |
| `EmbeddedAdapterRuntimeEnvironment` | dispatch/result queue ports | Adapter runtimes receive typed ports only. |
| `AdapterDispatchQueueConsumerLoop` | `TransportDispatchQueue.poll(...)` | Adapter-owned consumer loop. |

## Decisions

- Queue mechanics belong to `platform_infra/mass-queue-primitives` and
  `platform_infra/mass-runtime-redis`.
- Transport owns typed value codecs and status translation only.
- `BufferedTransportResultIngressChannel` is not retained as an async result
  bridge. Result drain ownership is starter/engine side.
- `InMemoryTransportDispatchHandoff` and `RedisTransportDispatchHandoff` class
  names remain as deferred naming residue only; the interface alias is removed.
- No typed generic queue helper is introduced in this roadmap.
