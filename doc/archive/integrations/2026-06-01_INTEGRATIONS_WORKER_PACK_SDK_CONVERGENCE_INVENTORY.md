# Integrations Worker Pack SDK Convergence Inventory

Status: completed WPC inventory.

This inventory supports
`doc/archive/integrations/2026-06-01_INTEGRATIONS_WORKER_PACK_SDK_CONVERGENCE_ROADMAP.md`.

## WPC-0 Decision

Worker-pack Java socket code has no unique current proof value that must remain
inside `integrations/xa-mass-worker-pack`.

The Java socket path is a raw demo client/starter pair. It is not a public Java
SDK session, not a real worker capability, and not the only socket proof for the
platform. Removal can proceed under WPC-1.

Remaining socket proof after worker-pack Java socket removal:

- `NodeSocketWorkerBlackBoxIntegrationTest` as the black-box socket adapter
  fixture.
- `ExternalWorkerPublicContractTraceObservedIntegrationTest` as public external
  worker contract trace proof with socket adapter enabled.
- socket transport/runtime adapter tests in the transport and server proof
  lanes.
- `SdkTransportLoadRunner` as scheduled/manual socket delivery diagnostics.

## Worker-Pack Surface

| Surface | Current owner reason | Decision |
| --- | --- | --- |
| `SampleBootstrapController` | sample-only public bootstrap for dev-shell catalog/rule metadata | Keep as dev-shell support; not server startup truth. |
| `AbstractSampleWorkerClientStarter` | shared embedded sample-client orchestration for dev/test fixtures | Keep for WebSocket fault harness unless replaced by SDK session in WPC-2. |
| `WebSocketClientStarter` | starts raw WebSocket sample clients for command/fault harness | Keep pending WPC-2 classification. |
| `SampleWorkerWebSocketClient` | raw WebSocket dispatch/result plus command/fault behavior | Keep only as worker-pack fault/command harness unless WPC-2 replaces it. |
| `SampleWorkerTaskFrameHandler` | builds normal task results and applies fault profiles | Keep as harness substrate while raw WebSocket harness remains. |
| `SampleWorkerCommandFrameHandler` | handles worker command frames and ACK/result reporting | Keep as worker-pack command/fault harness. |
| `SampleCommandRuntime` | local command dispatcher for worker-pack routes | Keep pending WPC-4 command boundary cleanup. |
| `SampleCommandRoutes` | `mock.*` and `fault.*` command routes | Keep as worker-pack fault harness. |
| `ToolCommandRoutes` | deterministic tool capability routes such as `tool.geo.lookup` | Candidate real worker-pack capability base for WPC-3. |
| `GeoLookupTool` | deterministic geo lookup capability with a stable simulated provider result | First real worker-pack capability; shared by SDK worker path and command route. |
| `GeoLookupWorkerPack` | SDK-backed worker group/session bootstrap for `tool.geo.lookup` | Keep as the first worker-pack external SDK capability proof. |
| `SampleWorkerSocketClient` | raw Java socket demo client | Remove in WPC-1. |
| `SocketClientStarter` | embedded Java socket demo starter | Remove in WPC-1. |
| socket-only tests | prove retired Java socket demo behavior | Remove in WPC-1. |

## Dependency Classification

| Dependency | Current use | Decision |
| --- | --- | --- |
| `xa-mass-embedded-sdk` | embedded dev-shell SDK application, worker discovery, worker-control command ACK/state report models used by active server E2E fault/command harnesses | Keep for the active harness only. It is the in-repo embedded SDK, not the public Java external SDK or a worker-pack capability dependency. |
| `xa-mass-java-sdk` | `GeoLookupWorkerPack` declares topology, starts polling, and reports results through public Java SDK clients/sessions | Keep for real worker-pack external capability paths. Do not use it for server startup or fault-harness command ownership. |
| `xa-mass-transport-socket` | only `SampleWorkerSocketClient` and `SocketClientStarter` | Remove with WPC-1. |
| `gson` | frame parsing and command payload handling | Keep while raw WebSocket command/fault harness remains. |
| `lettuce-core` | inherited worker-pack/runtime support dependency; no direct production import in current worker-pack sources | Recheck during WPC-5; do not remove as part of socket-only cleanup without separate proof. |
| `spring-boot-starter-web` | sample bootstrap controller and Spring configuration | Keep. |
| `spring-boot-starter-test` | module tests | Keep. |

## WebSocket Classification

| Behavior | Code surface | WPC-2 classification |
| --- | --- | --- |
| normal task dispatch detection | `SampleWorkerWebSocketClient.onMessage`, `SampleWorkerTaskFrameHandler.isTaskDispatchFrame` | Happy-path substrate. Duplicates SDK behavior, but needed only if the harness stays raw. |
| normal result construction/submission | `SampleWorkerTaskFrameHandler.prepareResponse`, `SampleWorkerWebSocketClient.sendTaskResponse` | Harness substrate because fault profiles mutate or delay the normal result frame. |
| worker command frame handling | `SampleWorkerCommandFrameHandler`, `SampleCommandRuntime` | Worker-pack-owned command/fault harness; do not move to Java SDK. |
| delay/drop/duplicate/late/malformed/wrong-identity result behavior | `SampleWorkerTaskFrameHandler` plus `SampleWorkerFaultProfile` | Worker-pack fault harness. |
| disconnect before/after receive/result | `SampleWorkerWebSocketClient.disconnectForFaultPhase` | Worker-pack realtime fault harness. |
| reconnect/session lifecycle | `SampleWorkerWebSocketClient.onClose` | Harness support only; not public SDK recommendation. |

WPC-2 should keep the raw WebSocket client only if this harness value remains
needed. If it stays, docs must label it as fault/command harness, not public
Java SDK usage.

## Command Boundary Classification

| Import family | Current worker-pack use | Decision |
| --- | --- | --- |
| `com.xa.mass.command.core` | command definitions, registry, dispatcher, core routes | Worker-pack command runtime dependency; keep while command/fault harness remains. |
| `com.xa.mass.command.model` | command context/response data returned into task results | Worker-pack command model dependency; keep pending WPC-4. |
| `com.xa.mass.command.runtime` | command logging for sample runtime | Worker-pack command runtime dependency; keep pending WPC-4. |
| `com.xa.mass.command.event` | no worker-pack production import in this inventory | Separate embedded event-runtime concern; do not conflate with worker-pack cleanup. |
| `com.xa.mass.base.exception` | command route parse/validation errors | Base exception/value dependency; classify separately from command runtime residue. |

## First Capability

`tool.geo.lookup` is implemented as the first WPC-3 capability. It has a small
deterministic input contract, structured output, and more business value than
`tool.time.now`.

Minimal contract:

- event code: `tool.geo.lookup`
- input: `query` or `city`
- output: `city`, `countryCode`, `timeZone`, `currency`, `latitude`,
  `longitude`, `provider`, `simulated`
- worker group: `worker-pack.tools.geo`
- value statement: proves worker-pack can publish a reusable external tool
  capability through the Java/public worker path instead of preserving a raw
  transport demo.

Implementation:

- `GeoLookupTool` owns the deterministic business result contract.
- `GeoLookupWorkerPack` owns the SDK-backed worker group/session bootstrap and
  uses Java SDK `WorkerEventHandler` so the handler is independent of polling
  transport.
- `ToolCommandRoutes` delegates `tool.geo.lookup` to `GeoLookupTool` so the
  command/dev route does not fork the result shape.
- `WorkerPackGeoLookupExternalSdkIntegrationTest` proves registration,
  polling dispatch, result reporting, and result readback through the Java
  SDK/public HTTP path against an empty-worker server fixture.
