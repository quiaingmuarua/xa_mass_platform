# Add Worker And Event

Status: human-facing integration guide.

This page explains the usual integration flow for adding a new worker
capability.

## The Flow

```text
define event
  -> bind event to project
  -> declare WorkerGroup eventBindings
  -> register worker into the group
  -> start worker presence / client session
  -> append items with eventCode
  -> worker handles eventCode
  -> worker submits result
```

## 1. Define The Event

An event describes a capability that can be invoked. For normal task work, it
is worker-targeted.

```java
app.registerEventDefinition(EventDefinition.builder()
        .code("image.resize")
        .name("Resize Image")
        .description("Resize one image payload")
        .payloadTypes(java.util.List.of(PayloadType.JSON))
        .taskModes(java.util.List.of(TaskMode.SINGLE_RUN))
        .priorityClass(PriorityClass.STANDARD)
        .responseMode(ResponseMode.FINAL_RESULT)
        .targetScope(TargetScope.WORKER)
        .build());
```

Rules:

- `code` / `eventCode` is the stable capability identity.
- Event metadata is descriptive. It does not directly own queue placement or
  result finality.
- Worker-side code should dispatch local handlers by `eventCode`.

## 2. Bind The Event To A Project

```java
app.registerProject(ProjectDefinition.builder()
        .code("mediaApp")
        .name("Media App")
        .eventCodes(java.util.List.of("image.resize"))
        .build());
```

The project is the business container. Task creation and worker capability both
use project membership when deciding eligibility.

## 3. Declare Capability And Register The Worker

```java
app.declareWorkerGroup(WorkerGroupDeclaration.builder()
        .groupId("image-workers")
        .eventBindings(java.util.List.of(
                WorkerEventBinding.builder()
                        .eventCode("image.resize")
                        .projectCodes(java.util.List.of("mediaApp"))
                        .build()
        ))
        .build());

app.registerAdapterNode(AdapterNodeRegistration.builder()
        .adapterNodeId("media-polling-node")
        .adapterType("polling")
        .endpointId("media-polling-node")
        .build());

app.bindNodeGroup(NodeGroupBindingRegistration.builder()
        .adapterNodeId("media-polling-node")
        .workerGroupId("image-workers")
        .build());

app.registerWorker(WorkerRegistration.builder()
        .workerId("image-worker-1")
        .adapterNodeId("media-polling-node")
        .workerGroupId("image-workers")
        .transportHint("polling")
        .attributes(java.util.Map.of(
                "routingTag", "media",
                "runtime", "java"))
        .build());
```

Important distinction:

- WorkerGroup declaration owns capability
- worker registration declares execution identity and node/group membership
- transport presence declares reachability
- scheduling still checks runtime state, reachability, rules, capacity, and
  resource policy before dispatch

## 4. Start The Worker Client

For a polling worker:

```java
var session = app.pullWorker("image-worker-1");
session.connect();
```

For an external non-JVM worker, use the public polling protocol described in
[`../doc/EXTERNAL_WORKER_QUICKSTART.md`](../doc/EXTERNAL_WORKER_QUICKSTART.md).

Realtime workers can use websocket or socket adapters, but the same conceptual
rules apply: capability registration is separate from online presence, and the
engine remains the owner of dispatch.

## 5. Append Work Items

```java
app.appendTaskItems(taskId, MassTaskItemBatchAppendRequest.builder()
        .eventCode("image.resize")
        .items(java.util.List.of(
                java.util.Map.of(
                        "source", "s3://bucket/input.png",
                        "width", 512,
                        "height", 512)))
        .build());
```

The event code on appended items must match a registered event and a worker
capability binding.

## 6. Execute By Event Code

Worker-side logic should be explicit:

```java
switch (item.getEventCode()) {
    case "image.resize" -> resizeImage(item.getInput());
    default -> throw new IllegalArgumentException("unsupported event: " + item.getEventCode());
}
```

Do not infer business behavior from task id, project name, transport adapter,
or worker id when `eventCode` is available.

## 7. Submit Result

```java
session.submitResult(
        item,
        true,
        "image resized",
        java.util.Map.of(
                "output", "s3://bucket/output.png",
                "width", 512,
                "height", 512)
);
```

The result path is runtime-first:

```text
worker result
  -> result ingest
  -> TaskWorkRuntime apply
  -> TaskResultRuntime stable-final row
  -> progress / terminal convergence
```

The worker reports execution outcome. It does not directly mark a task
terminal, update result projection, or release worker resources.

## Checklist

Before a new worker capability is ready:

- event registered with a stable `eventCode`
- project allows the event
- WorkerGroup declaration has `eventBindings`
- worker registration has `workerGroupId`
- worker transport identity is explicit enough for the chosen adapter
- worker can become reachable through its transport path
- appended items use the same `eventCode`
- result output is JSON-safe or stored behind an approved `payloadRef` pattern
