# Quick Start

Status: human-facing quick start.

This is the shortest SDK-shaped path to understand the platform. It is not a
replacement for the full SDK README.

## 1. Create An SDK Application

```java
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;

MassSdkApplication app = MassSdk.builder()
        .engine(engine -> engine.enabled(true).workerThreads(4))
        .build();

app.start();
```

For detailed transport options, read
[`../sdk/xa-mass-embedded-sdk/README.md`](../sdk/xa-mass-embedded-sdk/README.md).

## 2. Register An Event

```java
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.base.event.PriorityClass;
import com.xa.mass.base.event.ResponseMode;
import com.xa.mass.base.event.TargetScope;

app.registerEventDefinition(EventDefinition.builder()
        .code("crawler.fetch-page")
        .name("Fetch Page")
        .description("Fetch one page and return extracted data")
        .payloadTypes(java.util.List.of(PayloadType.JSON))
        .taskModes(java.util.List.of(TaskMode.SINGLE_RUN))
        .priorityClass(PriorityClass.STANDARD)
        .responseMode(ResponseMode.FINAL_RESULT)
        .targetScope(TargetScope.WORKER)
        .build());
```

`eventCode` is the worker-side handler identity. SDK/intake may use it to
validate WorkerGroup capability and resolve an explicit worker-group selector,
but the scheduling kernel dispatches from `workerGroupId(s)`, not from event
metadata. It is not a worker selector.

## 3. Register A Project

```java
import com.xa.mass.sdk.catalog.ProjectDefinition;

app.registerProject(ProjectDefinition.builder()
        .code("crawlerApp")
        .name("Crawler App")
        .description("Crawler tasks")
        .eventCodes(java.util.List.of("crawler.fetch-page"))
        .build());
```

A project binds a business domain to the events it can use.

## 4. Declare WorkerGroup And Register A Worker

```java
import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;
import com.xa.mass.sdk.model.WorkerRegistration;

app.declareWorkerGroup(WorkerGroupDeclaration.builder()
        .groupId("crawler")
        .eventBindings(java.util.List.of(
                WorkerEventBinding.builder()
                        .eventCode("crawler.fetch-page")
                        .projectCodes(java.util.List.of("crawlerApp"))
                        .build()
        ))
        .build());

app.registerAdapterNode(AdapterNodeRegistration.builder()
        .adapterNodeId("crawler-polling-node")
        .adapterType("polling")
        .endpointId("crawler-polling-node")
        .build());

app.bindNodeGroup(NodeGroupBindingRegistration.builder()
        .adapterNodeId("crawler-polling-node")
        .workerGroupId("crawler")
        .build());

app.registerWorker(WorkerRegistration.builder()
        .workerId("crawler-worker-1")
        .adapterNodeId("crawler-polling-node")
        .workerGroupId("crawler")
        .transportHint("polling")
        .attributes(java.util.Map.of("routingTag", "us"))
        .build());
```

Capability is declared on `WorkerGroupDeclaration.eventBindings`. Worker
registration only declares execution identity, adapter-node/group membership,
transport, and worker attributes. Item payload and `eventCode` do not own
worker-selection policy.

## 5. Create A Task And Append Items

```java
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.MassTaskCommandRequest;

var task = app.createTaskShell(MassTaskShellCreateRequest.builder()
        .userId("crawler-user")
        .project("crawlerApp")
        .sharedConfig(java.util.Map.of("routingCode", "us"))
        .executionSpec(new TaskExecutionSpec())
        .build());

app.appendTaskItems(task.getTid(), MassTaskItemBatchAppendRequest.builder()
        .eventCode("crawler.fetch-page")
        .items(java.util.List.of(
                java.util.Map.of("url", "https://example.com")))
        .build());

app.executeTaskCommand(task.getTid(), MassTaskCommandRequest.builder()
        .command("SEAL")
        .build());
```

The task shell and task items are intentionally separate:

```text
createTaskShell(...)
  -> creates lifecycle shell

appendTaskItems(...)
  -> adds executable work
```

For `BATCH` tasks, sealing intake lets the task close when all items are final.
For `SESSION` tasks, a temporary empty work set does not automatically mean the
session is complete.

## 6. Pull Work And Submit Result

```java
var session = app.pullWorker("crawler-worker-1");
session.connect();

var items = session.poll(1);
if (!items.isEmpty()) {
    var item = items.get(0);
    session.submitResult(
            item,
            true,
            "page fetched",
            java.util.Map.of("title", "Example Domain")
    );
}
```

The worker does not mutate task state directly. It submits a result; the engine
and runtime decide retry, finality, result visibility, release, and task
convergence.

## 7. Read Results

```java
var window = app.readTaskResults(task.getTid(), 0, 100);

for (var row : window.getItems()) {
    System.out.println(row.getMessageId() + " -> " + row.getStatus());
}
```

Result reads use runtime-owned stable-final rows. They do not read server
review/export materialization.

## Next Docs

- Add a real worker and event: [Add Worker And Event](./add-worker-and-event.md)
- Architecture overview: [Mental Model](./mental-model.md)
- External Java SDK quickstart: [`../sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md`](../sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md)
