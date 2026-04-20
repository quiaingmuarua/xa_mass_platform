# XA Mass SDK

`xa-mass-sdk` is the real Java embedding module for XA Mass Platform.

It carries both:

- the embedded runtime composition (`com.xa.mass.starter.*`)
- the consumer-facing SDK facade (`com.xa.mass.sdk.*`)

The runtime composition has been folded into this artifact so library callers
can depend on one SDK module without pulling the HTTP/demo control surface.

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

MassSdkApplication app = MassSdk.builder()
        .server(19090, "/ws")
        .gateway(gateway -> gateway.enabled(false))
        .engine(engine -> engine.enabled(true).workerThreads(4))
        .build();
```

The returned `MassSdkApplication` exposes:

- lifecycle: `start()`, `stop()`, `isRunning()`
- common task operations after `start()`: `createTask(...)`, `getTask(...)`, `getAllTasks()`, `getTasksByStatus(...)`, `approveTask(...)`, `rejectTask(...)`, `blockTask(...)`, `pauseTask(...)`, `resumeTask(...)`, `resumeTaskDetailed(...)`, `cancelTask(...)`, `terminateTask(...)`
- open-ended task operations after `start()`: `appendTaskItems(...)`, `sealTask(...)`
- audit and message operations after `start()`: `getTaskMessages(...)`, `resolveTaskStateFromMessages(...)`, `validateTaskState(...)`
- common worker operations after `start()`: `addWorker(...)`, `addWorkerContext(...)`, `getWorker(...)`, `getAllWorkers()`, `getAllWorkerContexts()`, `getWorkerContexts(...)`, `getWorkerContextById(...)`, `isWorkerLocked(...)`, `isWorkerOnline(...)`
- advanced engine access: `getEngine()`, `getTaskManager()`, `getWorkerManager()`
- escape hatch: `unwrap()` returns the underlying `MassApplication`

`getTaskManager()` and `getWorkerManager()` reflect runtime state and become useful after the engine starts. Common SDK operations intentionally fail fast if the SDK application was built without an engine or has not been started yet.

## Positioning

Use this module when:

- you are embedding XA Mass Platform into another JVM application
- you want the real embedded runtime plus a clearer SDK entry surface in one artifact
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

