# Embedded SDK Transport Semantic Boundary Inventory

Status: active implementation inventory for
`EMBEDDED_SDK_TRANSPORT_SEMANTIC_BOUNDARY_CONVERGENCE_ROADMAP.md`.

## Main-Source Leaks

`sdk/xa-mass-embedded-sdk/src/main/java` currently imports transport
implementation packages in these categories:

| File | Category | Disposition |
| --- | --- | --- |
| `MassApplication` | backend runtime construction, endpoint evidence bridge, dispatch/result ports, adapter runtime create/start, pull-worker transport resolution | hide behind `transport/adapter-starter` assembly and stable ports |
| `TaskDispatchRoutingSubmitter` | starter-owned task-to-transport translator using runtime `DispatchMessage`, `AdapterMailboxDispatchBatch`, `TransportAssignedDeliverySubmitter` | keep translator in SDK, replace runtime types with adapter-starter assigned-delivery contracts |
| `TaskResultIngressQueueDrain` | starter-owned result-to-engine drain using runtime `TransportResultIngressQueue` | keep drain in SDK, replace queue with adapter-starter result source |
| `EmbeddedPullWorkerSession`, `EmbeddedPullWorkerSessions` | pull-worker session bridge using runtime `PullSessionEvidenceDriver` | keep SDK session owner, replace evidence driver with adapter-starter pull evidence port |
| `TransportConfig`, `TransportRuntimeComposition`, `EmbeddedAdapterSpecAssembler` | SDK config currently stores concrete adapter config and runtime spec/factory facts | replace with adapter-starter-owned declarations and backend declaration values |
| `MassApplicationBuilder`, `MassSdk` | public/advanced SDK API exposes concrete adapter configs, polling buffer factory, Redis runtime constructors, custom websocket context | keep ergonomic methods only when they set declaration values; remove implementation-object factory hooks |

## POM Leaks

`sdk/xa-mass-embedded-sdk/pom.xml` directly depends on:

- `xa-mass-transport-polling`
- `xa-mass-transport-runtime`
- `xa-mass-transport-socket`
- `xa-mass-transport-websocket`

These are implementation dependencies. After main-source imports move to
`xa-mass-transport-api` and `xa-mass-transport-adapter-starter`, the direct
dependencies should be removed. Transitive runtime/adapter dependencies through
`adapter-starter` remain allowed by this roadmap.

## Superseded Facts

The previous spec-assembly roadmap fact that `EmbeddedAdapterSpecAssembler`
inside the SDK owns `EmbeddedAdapterRuntimeSpec` construction is superseded.
The new boundary is:

```text
SDK config/builder -> adapter-starter declarations -> adapter-starter runtime specs
```

The valid remaining fact from the previous roadmap is that built-in adapter
factory lookup stays fixed and non-dynamic. No `ServiceLoader`, classpath scan,
or remote adapter registration is introduced by this roadmap.

## Test-Source Notes

Test sources still import transport implementation packages for fixture-level
proof. This roadmap enforces main-source cleanup first. Test cleanup can follow
after the stable adapter-starter surface is proven.
