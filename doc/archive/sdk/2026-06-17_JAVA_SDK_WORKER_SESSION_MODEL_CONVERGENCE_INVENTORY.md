# Java SDK Worker Session Model Convergence Inventory

Status: archived code inventory for
`doc/archive/sdk/2026-06-17_JAVA_SDK_WORKER_SESSION_MODEL_CONVERGENCE_ROADMAP.md`.

## Symbols

| Symbol | Current Owner | Current Caller | Classification | Target |
| --- | --- | --- | --- | --- |
| `MassPlatform.workerSessions()` | `xa-mass-java-sdk` | external Java SDK callers | public SDK factory | stable session factory |
| `WorkerSessions` | `xa-mass-java-sdk` | `MassPlatform`, tests, integrations | public SDK builder entry | keep; add shared spec overloads later |
| `PollingWorkerSession` | `xa-mass-java-sdk` | Java SDK tests, scenario launcher, worker-pack helpers | concrete polling session | implement narrow `WorkerSession` |
| `WebSocketWorkerSession` | `xa-mass-java-sdk` | Java SDK tests, scenario launcher | concrete realtime session | implement narrow `WorkerSession` |
| `WorkerSessionListener` | `xa-mass-java-sdk` | polling and WebSocket sessions | broad callback sink | keep shared; do not treat as clean lifecycle taxonomy |
| `WorkerSessionStartupStep` | `xa-mass-java-sdk` | polling and WebSocket startup failures | union enum with protocol-specific steps | classify before adding common startup policy |
| `WorkerEventHandlers` | `xa-mass-java-sdk` | both sessions and tests | handler registry | keep shared |
| `WorkerEventHandlerRuntime` | `xa-mass-java-sdk` | both sessions and handler tests | handler invocation owner | keep shared |
| `WorkerResultSink` | `xa-mass-java-sdk` | polling session custom result hook | polling extension hook | keep polling-specific in first slices |
| `WorkerSpec` | `xa-mass-java-sdk` | worker registration requests | public worker registration DTO | keep; not session lifecycle truth |
| `WorkerClient.reportCapability(...)` | `xa-mass-java-sdk` | polling startup, scenario registrar, tests | current worker report API | API debt / worker-local evidence path; not `WorkerSession` policy |
| `ExternalWorkerApiController :report-capability` | `xa-mass-server` | external worker HTTP route | public worker report route | review before promoting any shared SDK policy |
| `WorkerCapabilityAuthority` / `WorkerReportOwner` | `xa-mass-worker-runtime` | worker-runtime report projection | worker-originated report projection | separate from WorkerGroup capability declaration |
| `WorkerScenarioRegistrar.markApiOnline(...)` | scenario launcher | scenario worker registration | adopter using report-capability | migrate after API owner decision |

## Dependencies

| Module | Dependency | Scope | Reason | Target |
| --- | --- | --- | --- | --- |
| `sdk/xa-mass-java-sdk` | `xa-mass-public-contract` | production | public task Controller wire DTOs | keep |
| `sdk/xa-mass-java-sdk` | `xa-mass-transport-api` | none | removed historical residue; guard forbids reintroduction | keep absent |
| `sdk/xa-mass-java-sdk` | `jackson-databind`, `jackson-datatype-jsr310` | production | SDK HTTP/WebSocket JSON handling | keep |
| `sdk/xa-mass-java-sdk` | `spring-boot-starter-test` | test | HTTP/test support | keep test-only |

## Current Caller Notes

- Production direct concrete session callers are `ScenarioWorkerRuntime`,
  `GeoLookupWorkerPack`, and `ProbeWorkerPack`.
- Scenario launcher needs both polling and WebSocket concrete session behavior,
  so it can only use the common `WorkerSession` where it only starts/closes
  sessions.
- Worker-pack helper methods intentionally expose polling-specific
  `startPolling()` entry points, so keeping `PollingWorkerSession` return types
  is valid unless those helpers become protocol-neutral.
- `PollingWorkerSession` exposes concrete-only `sessionToken()`.
- `WebSocketWorkerSession` exposes concrete-only `pendingResults()`.
- These concrete-only methods are not part of the common `WorkerSession`
  contract.

## Report Capability Classification

- WorkerGroup event capability declaration remains owned by explicit worker
  group declaration.
- `WorkerCapabilityReport.availableEventCodes` is current worker-local report
  evidence and must not become WorkerGroup capability truth.
- `PollingWorkerSession` currently calls `reportCapability` and `reportState`
  during startup.
- `WebSocketWorkerSession` currently registers and connects without polling
  online/heartbeat/offline/report-capability/report-state calls.
- Any change to `reportCapability` requires a separate API owner decision or
  the JWS-4 slice; it is not required for the minimal `WorkerSession` contract.
