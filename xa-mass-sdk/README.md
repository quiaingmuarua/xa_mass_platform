# XA Mass SDK

`xa-mass-sdk` is the real Java embedding module for XA Mass Platform.

It carries both:

- the embedded runtime composition (`com.xa.mass.starter.*`)
- the consumer-facing SDK facade (`com.xa.mass.sdk.*`)

The runtime composition has been folded into this artifact so library callers
can depend on one SDK module without pulling the HTTP/demo control surface.
Stable SDK-facing catalog/auth/model contracts now live in the internal
`xa-mass-sdk-api` module and are pulled transitively through this artifact.
Transport-neutral runtime contracts now live in `xa-mass-transport-api`; the
current bundled transport implementation still includes a WebSocket adapter,
but `xa-mass-sdk` now assembles worker transports through a transport runtime
registry/factory seam and also supports pull-style workers without server push.

## Dependency

```xml
<dependency>
  <groupId>com.xa.mass</groupId>
  <artifactId>xa-mass-sdk</artifactId>
  <version>${xa.mass.version}</version>
</dependency>
```

## Quick Start

Create an SDK application handle:

```java
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.MassTaskCreateRequest;

MassSdkApplication app = MassSdk.builder()
        .transportServer(19090, "/ws")
        .gateway(gateway -> gateway.enabled(false))
        .engine(engine -> engine.enabled(true).workerThreads(4))
        .build();

app.start();

app.createTask(MassTaskCreateRequest.builder()
        .userId("agent")
        .project("demoApp")
        .taskName("demo-task")
        .sharedConfig(java.util.Map.of("textContent", "hello"))
        .inputs(java.util.List.of(java.util.Map.of("target", "target-a")))
        .routingCode("us")
        .batchSize(1)
        .build());

app.pullWorker("crawler-worker-1").connect();
```

The returned `MassSdkApplication` exposes:

- lifecycle: `start()`, `stop()`, `isRunning()`
- common task operations after `start()`: `createTask(MassTaskCreateRequest)`, `getTask(...)`, `getAllTasks()`, `getTasksByStatus(...)`, `approveTask(...)`, `rejectTask(...)`, `blockTask(...)`, `pauseTask(...)`, `resumeTask(...)`, `resumeTaskDetailed(...)`, `cancelTask(...)`, `terminateTask(...)`
- open-ended task operations after `start()`: `appendTaskItems(...)`, `sealTask(...)`
- audit and message operations after `start()`: `getTaskMessages(...)`, `resolveTaskStateFromMessages(...)`, `validateTaskState(...)`
- common worker operations after `start()`: `addWorker(...)`, `addWorkerContext(...)`, `getWorker(...)`, `getAllWorkers()`, `getAllWorkerContexts()`, `getWorkerContexts(...)`, `getWorkerContextById(...)`, `isWorkerLocked(...)`, `isWorkerOnline(...)`
- pull-style worker entry after `start()`: `pullWorker(...)`; `pollingWorker(...)` remains as a deprecated compatibility alias
- stable runtime bootstrap surface after `start()`: `publishTaskEvents()`, plus open registration methods such as `addWorker(...)`, `addWorkerContext(...)`, `createTask(...)`, `replaceDefaultRules(...)`
- new bootstrap integration seam: `EngineOptions.bootstrapDataProvider(...)` accepts a pluggable `MassBootstrapDataProvider`
- deprecated compatibility shims: `MassSdkApplication.loadMockData()` and `EngineOptions.mockData(...)` remain callable but no longer embed mock logic
- deprecated compatibility seams for advanced embedding only: `getEngine()`, `getTaskManager()`, `getWorkerManager()`
- deprecated escape hatches: `MassSdkApplication.unwrap()` and SDK builder/option `unwrap()` methods expose lower-level runtime objects

`MassTaskCreateRequest` is the primary SDK create contract. The engine DTO overload remains only as a compatibility seam for callers that still depend on engine packages. Direct engine, manager, and runtime exposure is intentionally deprecated so the default SDK path stays on `MassSdkApplication` methods instead of leaking callers back into engine/runtime internals. Common SDK operations intentionally fail fast if the SDK application was built without an engine or has not been started yet. Mock/demo bootstrap data should be loaded outside the SDK module through `MassBootstrapDataProvider` and `MassRuntimeControl` instead of SDK-internal mock generators.

## Compatibility Policy

`com.xa.mass.sdk.*` is the stable public API surface for this artifact.

`com.xa.mass.starter.*` remains available for advanced embedding, but it is a
lower-level runtime composition layer and does not carry the same compatibility
commitment as the SDK facade.

Mock/demo bootstrap behavior is intentionally outside the SDK core. Keep custom
bootstrap code on `MassRuntimeControl`, and treat `loadMockData()` /
`mockData(...)` as deprecated compatibility shims only.

The repository CI can enforce binary/source compatibility for `com.xa.mass.sdk.*`
by setting `XA_MASS_SDK_API_BASELINE_VERSION` in GitHub Actions, which wires
through to `-Dmass.sdk.api.baselineVersion=...` and enables the SDK `japicmp`
profile.

## Positioning

Use this module when:

- you are embedding XA Mass Platform into another JVM application
- you want the real embedded runtime plus a stable SDK entry surface in one artifact
- you do not want to pull the demo/control HTTP layer into your dependency graph

Do not treat this module as:

- the Spring Boot runnable entry
- a replacement for `xa-mass-dev-app`
- the HTTP/demo control surface

Current runnable Boot entry remains `xa-mass-dev-app`.

## Internal Runtime Surface

If you need the lower-level runtime composition directly, start here:

- `src/main/java/com/xa/mass/starter/MassApplication.java`
- `src/main/java/com/xa/mass/starter/builder/MassApplicationBuilder.java`
- `src/main/java/com/xa/mass/starter/MassEngine.java`

Treat this lower-level `starter` surface as an advanced embedding path. It remains available, but the default compatibility commitment is on `com.xa.mass.sdk.*`.
