# xa-mass-java-sdk

Status: JSDK-5 current external Java SDK mainline.

`xa-mass-java-sdk` is the pure external Java client for a running
`xa-mass-server`.

It is intentionally separate from `xa-mass-sdk`, which is the embedded runtime
composition SDK.

## Runtime Target

- Java 21 JVM process.
- Production dependencies are JDK `HttpClient` and Jackson.
- No Spring Boot, engine, server, embedded SDK, worker runtime, transport
  adapter, worker-pack, or `xa-mass-base` production dependency.
- Android/device worker-host support is a separate future artifact decision.

## Authentication

The builder supports one outbound auth header:

```java
MassPlatform mass = MassPlatform.builder()
        .baseUrl("http://localhost:8088")
        .apiKey("mass_sk_xxx")
        .build();
```

Use `bearerToken(...)` instead of `apiKey(...)` when the server deployment
expects bearer auth. If neither is set, requests are sent without SDK-managed
auth headers.

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
- transport-neutral worker handler runtime:
  event handler registry, handler invocation, deterministic handler failure
  conversion, and session-owned result sink hooks

Stable public entry points are:

- `MassPlatform.builder()`
- `mass.tasks()`
- `mass.workers()`
- `mass.workerSessions()`

`mass.http()` and `com.xa.mass.client.http.*` are advanced unstable escape
hatches for diagnostics and temporary route coverage. External callers should
prefer typed clients; raw HTTP helpers are not a compatibility promise.

Not implemented yet:

- realtime worker client; deferred until a public realtime protocol contract
  exists

Those are tracked in [../../doc/JAVA_EXTERNAL_SDK_ROADMAP.md](../../doc/JAVA_EXTERNAL_SDK_ROADMAP.md).

Public readiness and publication hardening are tracked in
[../../doc/JAVA_EXTERNAL_SDK_PUBLIC_READINESS_ROADMAP.md](../../doc/JAVA_EXTERNAL_SDK_PUBLIC_READINESS_ROADMAP.md).

The standalone consumer metadata template is [pom.consumer.xml](pom.consumer.xml).
It documents the dependency shape external consumers should see; normal reactor
development continues to use [pom.xml](pom.xml).

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

Result reading:

```java
TaskResultWindow window = mass.tasks().readResults(task.taskId(),
        TaskResultReadRequest.builder()
                .afterSeq(0L)
                .limit(100)
                .build());

for (TaskResultItem item : window.items()) {
    System.out.println(item.result());
}
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
WorkerEventHandlers handlers = WorkerEventHandlers.builder()
        .event("probe.phone.metadata", dispatch -> {
            String phone = dispatch.input().requiredString("phone");
            return WorkerResult.success(Map.of(
                    "phone", phone,
                    "mcc", "525",
                    "mnc", "01"
            ));
        })
        .build();

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
        .eventHandlers(handlers)
        .start()) {
    Thread.currentThread().join();
}
```

`PollingWorkerSession.start()` does not declare WorkerGroups. Group declaration
is an explicit topology/setup operation through `mass.workers()`.

`PollingWorkerSession` uses the transport-neutral
`com.xa.mass.client.worker.handler` runtime internally. Future realtime
sessions should reuse the same `WorkerEventHandlers` and `WorkerResultSink`
contracts instead of adding transport-local handler execution.

Lifecycle callbacks:

```java
PollingWorkerSession session = mass.workerSessions().polling()
        .workerId("phone-worker-sg-001")
        .workerGroupId("phone-device-probe")
        .adapterNodeId("phone-poll-node-sg-1")
        .listener(new WorkerSessionListener() {
            @Override
            public void onDispatchFailure(WorkerSessionDispatchFailure failure) {
                System.err.println(failure.message());
            }
        })
        .event("probe.phone.metadata", dispatch -> WorkerResult.success(Map.of()))
        .start();
```

Timeouts and remote errors are reported through
`com.xa.mass.client.http.exception.*`:

- `MassTimeoutException` means the SDK-side request timeout elapsed.
- `MassApiException` means the server returned a structured API failure.
- `MassProtocolException` means the response shape did not match the SDK
  contract.
- `MassHttpException` covers non-API HTTP failures.

## Verification

From repo root:

```bash
./mvnw -pl integrations/xa-mass-java-sdk test
./mvnw -pl integrations/xa-mass-java-sdk dependency:tree
./mvnw -pl integrations/xa-mass-java-sdk -DskipTests source:jar javadoc:jar
./mvnw -f integrations/xa-mass-java-sdk/pom.consumer.xml -DskipTests package
./mvnw -pl integrations/samples/java/worker-polling -am -DskipTests package
```

## Boundary

Production code in this module must not depend on engine, server, embedded SDK,
worker runtime, or transport implementation modules.

`xa-mass-java-sdk` is a JVM SDK. Android/device worker-host support is a
separate future artifact decision, not part of this module.
