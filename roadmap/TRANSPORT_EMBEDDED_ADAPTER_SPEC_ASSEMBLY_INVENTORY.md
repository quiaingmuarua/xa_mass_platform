# Transport Embedded Adapter Spec Assembly Inventory

Status: current implementation inventory for
`TRANSPORT_EMBEDDED_ADAPTER_SPEC_ASSEMBLY_CONVERGENCE_ROADMAP.md`.

## Production Owners

| Concern | Current owner | Notes |
| --- | --- | --- |
| Typed SDK adapter declarations | `TransportConfig` | Mutable builder-facing declaration accumulator. Not runtime truth. |
| Immutable SDK transport snapshot | `TransportRuntimeComposition` | Snapshot/projection only. It does not construct specs, descriptors, registration resolvers, or runtime-owned endpoint lease stores. |
| Typed declarations -> embedded specs | `EmbeddedAdapterSpecAssembler` | Package-private SDK starter/config implementation. It is the only production writer of `EmbeddedAdapterRuntimeSpec` for built-in SDK config. |
| WebSocket custom server factory sidecars | `EmbeddedAdapterSpecAssembler` | Sidecars are validated against the final spec list and fail fast when disabled, orphaned, or duplicated. |
| Built-in adapter factory set | `EmbeddedAdapterStarterDefaults` | Fixed first-party list only. No dynamic discovery. |
| `spec.type -> factory` and descriptors | `EmbeddedAdapterRuntimeFactoryRegistry` | Shared by pre-runtime registration resolution and runtime creation. |
| Runtime create/start/close | `EmbeddedAdapterStarter` | Owns `adapterId -> runtime`, lifecycle calls, and live `TransportRuntimeRegistry`. |
| Backend runtime ports | `MassApplication` startup assembly | Creates queues, result sink, endpoint lease store, negative disconnect sink, and executor once per startup. |

## Guard Targets

- `TransportRuntimeComposition` must not contain `new EmbeddedAdapterRuntimeSpec(`.
- `TransportRuntimeComposition` must not import or construct concrete adapter runtime factories.
- `TransportRuntimeComposition` must not own `TransportRegistrationResolver`.
- `EmbeddedAdapterStarterDefaults.transportHintForType(...)` must not exist.
- `transport/adapter-starter` and `transport_runtime` must not import
  `com.xa.mass.starter.config.*`.
- Production `new EmbeddedAdapterRuntimeSpec(...)` is allowed only in the SDK
  starter/config assembler or a named successor.

## Allowed Remaining Surfaces

- `TransportRuntimeComposition.resolveEmbeddedAdapterRuntimeSpecs()` remains as
  a projection method for SDK/starter callers, but delegates to the assembler.
- `TransportRuntimeComposition.resolveWebSocketServerFactoriesByAdapterId()`
  remains as a projection method for SDK/starter callers, but delegates to the
  assembler.
- Tests may construct `EmbeddedAdapterRuntimeSpec` directly as fixtures.
