# xa-mass-java-sdk

Status: JSDK-5 current external Java SDK mainline.

`xa-mass-java-sdk` is the pure external Java client for a running
`xa-mass-server`.

It is intentionally separate from `xa-mass-sdk`, which is the embedded runtime
composition SDK.

## Scope

Current implemented surface:

- `MassPlatform.builder()`
- base URL normalization
- API key or bearer auth header injection
- JDK `HttpClient` based HTTP core
- Jackson-based `ApiResponse<T>` envelope handling
- typed client exceptions
- task shell, item ingest, command, result window, and archive clients
- worker topology client:
  adapter node registration, WorkerGroup declaration, node/group binding, and
  worker execution identity registration
- direct polling worker calls:
  online, heartbeat, poll, submit result, command poll/ack, capability report,
  state report, and offline
- managed polling worker session:
  adapter/node registration, node/group binding, worker registration, online,
  capability/state report, heartbeat, poll, handler dispatch, result submit,
  and best-effort offline on close

Not implemented yet:

- realtime worker client; deferred until a public realtime protocol contract
  exists

Those are tracked in [../../doc/JAVA_EXTERNAL_SDK_ROADMAP.md](../../doc/JAVA_EXTERNAL_SDK_ROADMAP.md).

Runnable sample:

- [../samples/java/worker-polling](../samples/java/worker-polling)
  uses this SDK's managed `PollingWorkerSession`.

## Example

```java
MassPlatform mass = MassPlatform.builder()
        .baseUrl("http://localhost:8088")
        .apiKey("mass_sk_xxx")
        .build();
```

Task producer:

```java
var task = mass.tasks().create(TaskCreateRequest.builder()
        .project("crawlerApp")
        .userId("agent")
        .contract(TaskContract.BATCH)
        .sharedConfig("routingCode", "us")
        .build());

mass.tasks().appendItems(task.taskId(), TaskItemBatch.builder()
        .eventCode("crawler.fetch-page")
        .item(Map.of("url", "https://example.com"))
        .build());

mass.tasks().seal(task.taskId());
```

Polling worker topology and direct poll:

```java
mass.workers().declareGroup(WorkerGroupSpec.builder()
        .groupId("phone-device-probe")
        .bindEvent("probe.phone.metadata", List.of("probeApp"))
        .defaultAttribute("deviceFamily", "android")
        .defaultMaxConcurrentWork(20)
        .build());

mass.workers().registerAdapterNode(AdapterNodeSpec.builder()
        .adapterNodeId("phone-poll-node-sg-1")
        .adapterType("polling")
        .endpointId("phone-poll-node-sg-1")
        .attribute("region", "sg")
        .build());

mass.workers().bindNodeGroup("phone-poll-node-sg-1", "phone-device-probe");

mass.workers().registerWorker(WorkerSpec.builder()
        .workerId("phone-worker-sg-001")
        .adapterNodeId("phone-poll-node-sg-1")
        .workerGroupId("phone-device-probe")
        .polling()
        .attribute("fingerprint", "fp-android-13-sg")
        .attribute("region", "sg")
        .build());

mass.workers().online("phone-worker-sg-001", "startup");
WorkerPollResult poll = mass.workers().poll("phone-worker-sg-001",
        WorkerPollRequest.builder().maxMessages(10).timeoutMs(500L).build());
```

Managed polling worker session:

```java
mass.workers().declareGroup(WorkerGroupSpec.builder()
        .groupId("phone-device-probe")
        .bindEvent("probe.phone.metadata", List.of("probeApp"))
        .build());

try (PollingWorkerSession session = mass.workerSessions().polling()
        .workerId("phone-worker-sg-001")
        .workerGroupId("phone-device-probe")
        .adapterNodeId("phone-poll-node-sg-1")
        .attribute("fingerprint", "fp-android-13-sg")
        .attribute("region", "sg")
        .event("probe.phone.metadata", dispatch -> {
            String phone = dispatch.input().requiredString("phone");
            return WorkerResult.success(Map.of(
                    "phone", phone,
                    "mcc", "525",
                    "mnc", "01"
            ));
        })
        .start()) {
    Thread.currentThread().join();
}
```

`PollingWorkerSession.start()` does not declare WorkerGroups. Group declaration
is an explicit topology/setup operation through `mass.workers()`.

## Boundary

Production code in this module must not depend on engine, server, embedded SDK,
worker runtime, or transport implementation modules.
