# xa-mass-java-sdk

Status: JSDK-5 current external Java SDK mainline.

`xa-mass-java-sdk` is the pure external Java client for a running
`xa-mass-server`.

It is intentionally separate from `xa-mass-embedded-sdk`, which is the embedded runtime
composition SDK.

## Runtime Target

- Java 21 JVM process.
- Production dependencies are the public contract module, JDK `HttpClient`,
  and Jackson.
- No Spring Boot, engine, server, embedded SDK, worker runtime, transport
  adapter, worker-pack, or `xa-mass-base` production dependency.
- Android/device worker-host support is outside this JVM SDK module.

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

Worker API keys may be bound by server credential attributes. When the
authenticated principal has `attributes.workerId`, worker registration,
presence, polling, result submit, state/capability report, command ack, and
offline calls must use that same worker id.

## Scope

Current implemented surface:

- `MassPlatform.builder()`
- base URL normalization
- API key or bearer auth header injection
- JDK `HttpClient` based HTTP core
- Jackson-based `ApiResponse<T>` envelope handling
- typed client exceptions
- task shell, item ingest, command, result window, and archive clients
- source-labeled task read model fields through `TaskView.fieldSources` so
  callers can distinguish shell, runtime/current, execution, timestamp, and
  compatibility fields in composite task reads
- typed task-create routing helpers for WorkerGroup selectors, routing code,
  route attributes, and target worker attributes
- task-scoped `TaskHandle` for repeated operations against one existing task
- worker topology client:
  adapter node registration, WorkerGroup declaration, node/group binding, and
  worker execution identity registration
- direct polling worker calls:
  online, heartbeat, poll, submit result, command poll/ack, capability report,
  state report, and offline
- managed polling worker session:
  worker registration, online, current worker-local capability/state report,
  heartbeat, poll, handler dispatch, result submit, and best-effort offline on
  close. WorkerGroup declaration and adapter topology setup remain explicit
  `mass.workers()` operations outside the session.
- managed WebSocket worker session:
  realtime worker registration, JDK WebSocket connection, canonical task
  dispatch frame handling, queued result frame submission, bounded reconnect
  attempts, queue-full/requeue failure reporting,
  heartbeat/frame/connection lifecycle callbacks, and queued-result terminal
  callbacks on close or reconnect exhaustion.
  Realtime worker presence is transport-owned; the session does not call
  polling-only online, heartbeat, capability report, state report, or offline
  APIs. Polling is the stable third-party worker protocol; WebSocket is an
  implemented JVM session and internal staging validation path while its wire
  shape continues hardening.
- transport-neutral worker handler runtime:
  event handler registry, handler invocation, deterministic handler failure
  conversion, and session-owned result sink hooks
- common worker-session model:
  narrow `WorkerSession` lifecycle contract and `WorkerSessionSpec` shared
  identity/options object for `workerId`, `workerGroupId`, attributes, event
  handlers, and listener wiring. `WorkerSession` does not expose transport
  internals, report-capability/state policy, polling methods, reconnect
  controls, session tokens, or result queues.

Stable public entry points are:

- `MassPlatform.builder()`
- `mass.tasks()`
- `mass.workers()`
- `mass.workerSessions()`

`mass.http()` and `com.xa.mass.client.http.*` are advanced unstable escape
hatches for diagnostics and temporary route coverage. External callers should
prefer typed clients; raw HTTP helpers are not a compatibility promise.

Use `MassPlatform.workerSessions()` as the stable session factory. Direct
`new WorkerSessions(...)` construction is marked `@UnstableApi` and is reserved
for advanced or internal wiring.

`WorkerSession.transportHint()` is the public worker registration hint from
`WorkerSpec`. Current managed sessions return `polling` for
`PollingWorkerSession` and `realtime` for `WebSocketWorkerSession`; this value
is not an adapter id, protocol id, route key, endpoint id, or transport runtime
owner id.

Task append ergonomics stay identity-preserving: do not add a Java SDK bulk
append helper while `TaskAppendResult` lacks per-item message identity or an
equivalent append receipt identity. Use `TaskHandle` to bind one task, append or
sync-append items when the task lifecycle already allows it, and read results
through task result APIs. Task lifecycle governance commands are operator/server
control-plane behavior, not scenario task-launcher behavior. A future chunking
helper must return identity-preserving receipts, must work against one existing
task, and must not create one task per item or auto-seal unless the method name
explicitly says it seals.

Realtime session hardening current truth:

- polling remains the stable first external worker session
- Java SDK WebSocket is an implemented JVM session and internal staging
  validation path; socket is not yet a first-class Java SDK session
- Android host support is not part of the pure Java SDK
- do not introduce a shared `RealtimeWorkerSession` abstraction until at least
  two realtime transports share a proven public lifecycle
- frame/protocol failures report through listener callbacks and do not
  increment connection-failure counters
- frame/protocol failure callbacks expose bounded `framePreview` plus
  `frameLength`, not the complete raw frame
- successful reconnect reports `onConnectionRecovered(workerId)`
- queued-result close, reconnect exhaustion, and send-failure requeue failure
  terminal outcomes report through `onQueuedResultAbandoned(...)`; requeue
  failure uses `REQUEUE_FAILED`
- `onSubmitFailure(...)` is an attempt-level callback; a queued result may
  still later report a terminal `onQueuedResultAbandoned(...)`
- queue-full outcomes report through `onQueuedResultDropped(...)`
- close sends a best-effort WebSocket close frame before terminal result
  abandonment
- platform `connectTimeout`, `HttpClient`, and `ObjectMapper` defaults flow
  into WebSocket session builders unless explicitly overridden

Open realtime hardening topics remain WebSocket result idempotency under
reconnect, malformed frame flood ceilings, socket session ownership, and
worker-pack convergence as an SDK consumer rather than an SDK dependency.

Public readiness is current for local/internal staging. Public registry
publication is outside the current module scope.

The standalone consumer metadata template is [pom.consumer.xml](pom.consumer.xml).
It documents the dependency shape external consumers should see; normal reactor
development continues to use [pom.xml](pom.xml).

The internal executable adopter is
[../../integrations/xa-mass-scenario-launcher](../../integrations/xa-mass-scenario-launcher),
not standalone Java sample apps. It assumes catalog, rules, and credentials are
prepared by server-owned seed/import, real control-plane setup, or test
fixtures before its worker launcher registers worker topology and its task
launcher submits scenario tasks through SDK-backed external calls.

For the short task-producer plus worker-session onboarding path, use
[EXTERNAL_SDK_QUICKSTART.md](./EXTERNAL_SDK_QUICKSTART.md).

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
        .workerGroupId("crawler-workers")
        .routingCode("us")
        .build());

TaskHandle handle = mass.tasks().forTask(task.taskId());

handle.appendItems(TaskItemBatch.builder()
        .eventCode("crawler.fetch-page")
        .item(Map.of("url", "https://example.com"))
        .build());
```

Task-scoped interactive invocation:

```java
TaskHandle handle = mass.tasks().forTask("existing-ready-session-task-id");

TaskSyncAppendResult result = handle.appendItemSync(TaskItemSyncRequest.builder()
        .eventCode("crawler.fetch-page")
        .item(Map.of("url", "https://example.com"))
        .timeoutMs(5000L)
        .build());

TaskResultWindow window = handle.results(TaskResultReadRequest.builder()
        .limit(100)
        .build());
```

Result reading:

```java
TaskResultWindow window = mass.tasks().results(task.taskId(),
        TaskResultReadRequest.builder()
                .afterSeq(0L)
                .limit(100)
                .build());

for (TaskResultItem item : window.items()) {
    System.out.println(item.output());
}
```

Polling worker topology setup and direct poll:

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
        .workerGroupId("phone-device-probe")
        .polling()
        .attribute("fingerprint", "fp-android-13-sg")
        .attribute("region", "sg")
        .build());

String sessionToken = UUID.randomUUID().toString();
mass.workers().online("phone-worker-sg-001", sessionToken, "startup");
WorkerPollResult poll = mass.workers().poll("phone-worker-sg-001",
        WorkerPollRequest.builder().maxMessages(10).timeoutMs(500L).build());
```

Adapter-node and node-group binding calls are topology/admin setup. Normal
worker registration and `WorkerSession` helpers use `workerId`,
`workerGroupId`, worker attributes, and `transportHint`; they do not carry
`adapterNodeId`.

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

WorkerSessionSpec sessionSpec = WorkerSessionSpec.builder()
        .workerId("phone-worker-sg-001")
        .workerGroupId("phone-device-probe")
        .attribute("fingerprint", "fp-android-13-sg")
        .attribute("region", "sg")
        .eventHandlers(handlers)
        .build();

mass.workers().declareGroup(WorkerGroupSpec.builder()
        .groupId("phone-device-probe")
        .bindEvent("probe.phone.metadata", List.of("probeApp"))
        .build());

try (WorkerSession session = mass.workerSessions().polling(sessionSpec)
        .start()) {
    Thread.currentThread().join();
}
```

`PollingWorkerSession.start()` does not declare WorkerGroups. Group declaration
is an explicit topology/setup operation through `mass.workers()`.

`PollingWorkerSession` uses the SDK-owned
`com.xa.mass.client.worker.handler` runtime internally. `WebSocketWorkerSession`
uses the same handler runtime through the common session dispatch processor and
routes handler results through a session-owned outbound frame queue.
Queue-full outcomes are reported through
`WorkerSessionListener.onQueuedResultDropped(...)`; queued results that cannot
be requeued after send failure, or cannot be submitted because the session
closes or reconnect is exhausted, are reported through
`WorkerSessionListener.onQueuedResultAbandoned(...)`. WebSocket close uses a
best-effort close frame and then drains or abandons queued results according to
the session terminal reason.
Session lifecycle callbacks distinguish poll failure, heartbeat failure,
connection failure, connection recovery, frame/protocol failure, handler
failure, submit failure, queued-result drop, and queued-result abandonment.
Heartbeat failures are reported only through `onHeartbeatFailure(...)`, not as
poll failures. `onSubmitFailure(...)` is an attempt-level signal; a queued
result can later emit terminal `onQueuedResultAbandoned(...)`, including
`REQUEUE_FAILED` after the same failed send attempt.
Frame/protocol failures expose a bounded `framePreview` and `frameLength`
rather than the complete raw frame. The preview can still contain payload
fragments, so do not log it blindly in production.

Managed WebSocket worker session:

```java
mass.workers().declareGroup(WorkerGroupSpec.builder()
        .groupId("realtime-crawler")
        .bindEvent("crawler.fetch-page", List.of("crawlerApp"))
        .build());

try (WebSocketWorkerSession session = mass.workerSessions().webSocket()
        .workerId("crawler-ws-001")
        .workerGroupId("realtime-crawler")
        .endpoint(URI.create("ws://localhost:18088/ws"))
        .maxReconnectAttempts(10)
        .event("crawler.fetch-page", dispatch -> WorkerResult.success(Map.of(
                "url", dispatch.input().requiredString("url")
        )))
        .start()) {
    Thread.currentThread().join();
}
```

`WebSocketWorkerSession.start()` does not declare WorkerGroups. Group
declaration remains an explicit topology/setup operation through
`mass.workers()`.

Lifecycle callbacks:

```java
PollingWorkerSession session = mass.workerSessions().polling()
        .workerId("phone-worker-sg-001")
        .workerGroupId("phone-device-probe")
        .listener(new WorkerSessionListener() {
            @Override
            public void onHandlerFailure(WorkerSessionDispatchFailure failure) {
                System.err.println(failure.cause().getMessage());
            }

            @Override
            public void onSubmitFailure(WorkerSessionDispatchFailure failure) {
                System.err.println(failure.cause().getMessage());
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
./mvnw -pl sdk/xa-mass-java-sdk -am test
./mvnw -pl sdk/xa-mass-java-sdk -am dependency:tree
./mvnw -pl sdk/xa-mass-java-sdk -am -DskipTests source:jar javadoc:jar
./mvnw -f sdk/xa-mass-java-sdk/pom.consumer.xml -DskipTests package
./mvnw -pl integrations/xa-mass-scenario-launcher -am -DskipTests package
```

## Boundary

Production code in this module must not depend on engine, server, embedded SDK,
worker runtime, or transport implementation modules.

`xa-mass-java-sdk` is a JVM SDK. Android/device worker-host support is outside
this module.
