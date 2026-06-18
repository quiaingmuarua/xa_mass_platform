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
presence, polling, result submit, handler evidence, runtime evidence, and
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
  online, heartbeat, poll, submit result, handler evidence, runtime evidence,
  and offline
- managed polling worker runtime:
  online, heartbeat, poll, handler dispatch, result submit, and best-effort
  offline on close. WorkerGroup declaration and worker registration remain
  explicit `mass.workers()` operations outside the runtime.
- managed WebSocket worker runtime:
  JDK WebSocket connection, canonical task dispatch frame handling, queued
  result frame submission, bounded reconnect attempts, queue-full/requeue
  failure reporting through `WorkerRuntimeFailureEvent`, connection recovery
  callback, and queued-result terminal failure events on close or reconnect
  exhaustion.
  Realtime worker presence is transport-owned; the runtime does not call
  polling-only online, heartbeat, handler evidence, runtime evidence, or
  offline APIs. Polling is the stable third-party worker protocol; WebSocket is
  an implemented JVM runtime and internal staging validation path while its
  wire shape continues hardening.
- transport-neutral worker handler runtime:
  event handler registry, handler invocation, deterministic handler failure
  conversion, and runtime-owned result sink hooks
- worker handler invocation is payload-first:
  handlers receive `WorkerInvocation` with opaque `resultCorrelationRef`,
  `eventCode`, `input`, and `sharedConfig`. Result association is a submit
  token that must only be round-tripped; worker handlers and public worker
  result requests must not depend on task ids, message ids, attempt ids,
  transport commands, or raw wire DTOs.
- common worker-runtime definition model:
  `WorkerRuntimeDefinition` owns `workerId`, `workerGroupId`, attributes, and
  event handlers once. Polling and WebSocket runtimes are protocol runtimes over
  that definition. `WorkerRuntime` does not expose transport internals,
  handler/runtime evidence policy, polling methods, reconnect controls, session
  tokens, or result queues.

Stable public entry points are:

- `MassPlatform.builder()`
- `mass.tasks()`
- `mass.workers()`
- `mass.workerRuntimes()`

`mass.http()` and `com.xa.mass.client.http.*` are advanced unstable escape
hatches for diagnostics and temporary route coverage. External callers should
prefer typed clients; raw HTTP helpers are not a compatibility promise.

Use `MassPlatform.workerRuntimes()` as the stable runtime factory. Direct
`new WorkerRuntimes(...)` construction is marked `@UnstableApi` and is reserved
for advanced or internal wiring.

`WorkerRuntime.transportHint()` is the public worker registration hint from
`WorkerSpec`. Current managed runtimes return `polling` for
`PollingWorkerRuntime` and `realtime` for `WebSocketWorkerRuntime`; this value
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

Realtime runtime hardening current truth:

- polling remains the stable first external worker runtime
- Java SDK WebSocket is an implemented JVM runtime and internal staging
  validation path; socket is not yet a first-class Java SDK runtime
- Android host support is not part of the pure Java SDK
- do not introduce a shared `RealtimeWorkerRuntime` abstraction until at least
  two realtime transports share a proven public lifecycle
- frame/protocol failures report as `WorkerRuntimeFailureEvent.Kind.FRAME` and
  do not increment connection-failure counters
- frame/protocol failure event context exposes bounded `framePreview` plus
  `frameLength`, not the complete raw frame
- successful reconnect reports `onConnectionRecovered(workerId)`
- queued-result close, reconnect exhaustion, and send-failure requeue failure
  terminal outcomes report as
  `WorkerRuntimeFailureEvent.Kind.QUEUED_RESULT_ABANDONED`; requeue failure
  uses `REQUEUE_FAILED`
- `WorkerRuntimeFailureEvent.Kind.SUBMIT` is an attempt-level signal; a queued
  result may still later report a terminal queued-result abandoned event
- queue-full outcomes report as
  `WorkerRuntimeFailureEvent.Kind.QUEUED_RESULT_DROPPED`
- close sends a best-effort WebSocket close frame before terminal result
  abandonment
- platform `connectTimeout`, `HttpClient`, and `ObjectMapper` defaults flow
  into WebSocket runtime builders unless explicitly overridden

Open realtime hardening topics remain WebSocket result idempotency under
reconnect, malformed frame flood ceilings, socket runtime ownership, and
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
fixtures before its worker launcher registers workers and publishes explicit
worker evidence, while its task launcher submits scenario tasks through
SDK-backed external calls.

For the short task-producer plus worker-runtime onboarding path, use
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

Polling worker declaration and direct poll:

```java
mass.workers().declareGroup(WorkerGroupSpec.builder()
        .groupId("phone-device-probe")
        .bindEvent("probe.phone.metadata", List.of("probeApp"))
        .defaultAttribute("deviceFamily", "android")
        .defaultMaxConcurrentWork(20)
        .build());

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

Normal worker registration and `WorkerRuntime` helpers use `workerId`,
`workerGroupId`, worker attributes, and `transportHint`; they do not carry
adapter-node topology ids.

Managed polling worker runtime:

```java
WorkerRuntimeDefinition worker = WorkerRuntimeDefinition.builder()
        .workerId("phone-worker-sg-001")
        .workerGroupId("phone-device-probe")
        .attribute("fingerprint", "fp-android-13-sg")
        .attribute("region", "sg")
        .event("probe.phone.metadata", dispatch -> {
            String phone = dispatch.input().requiredString("phone");
            return WorkerResult.success("""
                    {"phone":"%s","mcc":"525","mnc":"01"}
                    """.formatted(phone).trim());
        })
        .build();

mass.workers().declareGroup(WorkerGroupSpec.builder()
        .groupId("phone-device-probe")
        .bindEvent("probe.phone.metadata", List.of("probeApp"))
        .build());

mass.workers().registerWorker(WorkerSpec.polling(worker));

try (WorkerRuntime runtime = mass.workerRuntimes().polling(worker)
        .start()) {
    Thread.currentThread().join();
}
```

`PollingWorkerRuntime.start()` does not declare WorkerGroups and does not
register workers. Group declaration and worker registration are explicit setup
operations through `mass.workers()`.

Worker-local evidence reporting is explicit runtime behavior, not a hidden
startup side effect:

```java
WorkerRuntime runtime = mass.workerRuntimes().polling(worker)
        .start();

runtime.reporter().reportHandlerEvidence();
runtime.reporter().reportAvailable("ready");
```

`PollingWorkerRuntime` and `WebSocketWorkerRuntime` use the same runtime
dispatch processor and route handler results through their protocol-specific
result-submit mechanism. Both managed runtimes expose result correlation only
as an opaque submit token; worker business code must not interpret it as task
identity or lifecycle state.
Runtime failures are reported through one callback:
`WorkerRuntimeListener.onFailure(WorkerRuntimeFailureEvent)`. The event `kind`
distinguishes poll failure, heartbeat failure, connection failure,
frame/protocol failure, handler failure, submit failure, queued-result drop,
queued-result abandonment, startup failure, and shutdown failure. Successful
WebSocket reconnect still reports `onConnectionRecovered(workerId)` because it
is not a failure.
Heartbeat failures use `WorkerRuntimeFailureEvent.Kind.HEARTBEAT`, not
`POLL`. Submit failures use `Kind.SUBMIT` as an attempt-level signal; a queued
result can later emit terminal `Kind.QUEUED_RESULT_ABANDONED`, including
`REQUEUE_FAILED` after the same failed send attempt.
Frame/protocol failures expose bounded `framePreview` and `frameLength` in the
event context rather than the complete raw frame. The preview can still contain
payload fragments, so do not log it blindly in production.
`WorkerRuntimeFailureEvent.context()` is diagnostic-only; use `kind`, `reason`,
`resultCorrelationRef`, `consecutiveFailures`, `errorType`, and `errorMessage`
as the stable event data.

Managed WebSocket worker runtime:

```java
mass.workers().declareGroup(WorkerGroupSpec.builder()
        .groupId("realtime-crawler")
        .bindEvent("crawler.fetch-page", List.of("crawlerApp"))
        .build());
```

WebSocket uses the same worker definition shape:

```java
WorkerRuntimeDefinition worker = WorkerRuntimeDefinition.builder()
        .workerId("crawler-ws-001")
        .workerGroupId("realtime-crawler")
        .event("crawler.fetch-page", dispatch -> WorkerResult.success("""
                {"url":"%s"}
                """.formatted(dispatch.input().requiredString("url")).trim()))
        .build();

mass.workers().registerWorker(WorkerSpec.realtime(worker));

try (WebSocketWorkerRuntime runtime = mass.workerRuntimes().webSocket(worker)
        .endpoint(URI.create("ws://localhost:18088/ws"))
        .maxReconnectAttempts(10)
        .start()) {
    Thread.currentThread().join();
}
```

`WebSocketWorkerRuntime.start()` does not declare WorkerGroups and does not
register workers. Group declaration and worker registration are explicit setup
operations through `mass.workers()`.

Lifecycle callbacks:

```java
WorkerRuntimeDefinition worker = WorkerRuntimeDefinition.builder()
        .workerId("phone-worker-sg-001")
        .workerGroupId("phone-device-probe")
        .event("probe.phone.metadata", dispatch -> WorkerResult.success("{}"))
        .build();

mass.workers().registerWorker(WorkerSpec.polling(worker));

PollingWorkerRuntime runtime = mass.workerRuntimes().polling(worker)
        .listener(new WorkerRuntimeListener() {
            @Override
            public void onFailure(WorkerRuntimeFailureEvent failure) {
                System.err.printf("worker runtime failure kind=%s reason=%s error=%s%n",
                        failure.kind(),
                        failure.reason(),
                        failure.errorMessage());
            }
        })
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
