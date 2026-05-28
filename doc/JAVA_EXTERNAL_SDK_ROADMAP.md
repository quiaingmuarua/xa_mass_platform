# Java External SDK Roadmap

Status: draft.

This roadmap defines a new `xa-mass-java-sdk` artifact for repo-external Java
clients. It is intentionally separate from the current `xa-mass-sdk`, and it
should live under a dedicated external-integration source tree rather than as
another root-level module.

The current `xa-mass-sdk` is an embedding/runtime-composition artifact. It can
start an engine, assemble transports, and expose an SDK facade for an embedded
XA Mass runtime. The new `xa-mass-java-sdk` should instead be a pure remote
client for a running `xa-mass-server`:

```java
MassPlatform mass = MassPlatform.builder()
        .baseUrl("http://localhost:8080")
        .apiKey("mass_sk_xxx")
        .build();
```

The goal is to make normal external integration simple without letting a Java
client artifact become another embedded runtime, server builder, worker
runtime SPI, or transport implementation bucket.

## Repository Layout Decision

Create a top-level `integrations/` directory for caller-facing integration
artifacts:

```text
integrations/
  xa-mass-java-sdk/        pure external Java client SDK
  xa-mass-worker-pack/     later move; official sample/dev worker capability pack
  samples/                 later move; external-process sample workers and launchers
```

Rationale:

- `xa-mass-java-sdk` is not kernel, infra, transport, server, or embedded SDK.
  Placing it at repo root makes the boundary look equal to core runtime
  modules.
- `samples/` is currently too thin to justify a root-level domain. It is an
  external integration proof surface, not a platform owner.
- `xa-mass-worker-pack` is sample/dev worker capability and launcher code. Once
  Java worker sessions are available, worker-pack should consume
  `xa-mass-java-sdk` for external-worker paths instead of carrying separate
  raw clients where the public SDK should own the boilerplate.
- keeping all external integration artifacts together makes it clearer which
  modules may depend on public server contracts and which modules must not
  influence engine/runtime ownership.

Initial module path:

```xml
<module>integrations/xa-mass-java-sdk</module>
```

Later layout convergence:

```xml
<module>integrations/xa-mass-worker-pack</module>
```

Keep artifactIds such as `xa-mass-java-sdk` and `xa-mass-worker-pack`; the
directory grouping is repository ownership, not Maven identity. Do not rename
artifacts just to match the folder move.

The first implementation should create `integrations/xa-mass-java-sdk`
directly. Do not create it at repo root and then move it later.

Moving existing `samples/` and `xa-mass-worker-pack` is useful but should be a
separate convergence slice after the SDK client/session API exists. That move
has broad path fallout in black-box tests, README commands, sample launchers,
and Maven module declarations; it should not be mixed into the first SDK
skeleton commit.

## Current Facts

- `xa-mass-sdk` owns embedded runtime composition and should not absorb a
  server-HTTP client mainline.
- `xa-mass-sdk-api` owns stable SDK-facing models used by server, SDK, and
  tests, but it is not an HTTP client artifact.
- `xa-mass-server` exposes the active external HTTP surface:
  - `/api/v1/**` for task, catalog, submitter, runtime/operator surfaces.
  - `/worker-api/v1/**` for repo-external worker registration, polling,
    presence, command acknowledgement, capability/state reports, and result
    submit.
- external worker samples already prove the contract with raw Java
  `HttpClient` plus JSON handling. The new SDK should replace that repeated
  boilerplate with stable client/runtime APIs.
- `samples/` and `xa-mass-worker-pack` are currently root-level caller-facing
  assets. They should converge under `integrations/` after the Java SDK gives
  them a stable public client surface to consume.
- `eventCode` is global event/capability identity. Project binding scopes where
  an event is available; it does not make event identity project-local.
- Worker capability truth is WorkerGroup-first. Workers are execution
  identities with attributes, transport identity, state/capability reports, and
  presence.

## Target Shape

```text
external Java app
  -> integrations/xa-mass-java-sdk
                              pure HTTP/WebSocket client and worker session runtime
      -> JDK HttpClient        HTTP transport
      -> JSON mapper           client wire encoding
      -> optional WebSocket    later realtime client phase

xa-mass-java-sdk
  -/-> xa-mass-engine
  -/-> xa-mass-sdk
  -/-> xa-mass-worker-runtime
  -/-> xa-mass-server
  -/-> transport runtime modules
```

Allowed dependencies:

- Java standard library.
- one JSON library already accepted by the repo build, preferably the same
  mapper used by the public HTTP contract tests.
- optionally `xa-mass-sdk-api` only when the type is a stable public contract
  and does not pull embedded/runtime composition.

Forbidden dependencies:

- `xa-mass-engine`
- `xa-mass-sdk`
- `xa-mass-worker-runtime`
- `xa-mass-server`
- transport implementation modules
- Spring Boot or server runtime dependencies

## Boundary Contract

The new SDK owns client ergonomics and remote protocol handling only.

`xa-mass-java-sdk` owns:

- base URL, authentication header, timeout, retry, and JSON envelope handling.
- typed task shell/item/result calls over `/api/v1/tasks`.
- typed catalog/project/submitter reads when needed by external callers.
- typed worker topology registration calls over `/worker-api/v1`.
- polling worker session lifecycle:
  register topology, online, heartbeat, poll, dispatch to handler, submit
  result, poll/ack commands, report capability/state, offline on shutdown.
- client-side examples that show realistic task and worker usage.

`xa-mass-java-sdk` does not own:

- engine scheduling policy.
- worker matching, candidate ranking, worker admission, or reserve/release
  decisions.
- task result convergence.
- server-side authorization truth.
- embedded runtime startup or transport adapter assembly.
- protocol truth for realtime transports before those protocols are documented
  as public contracts.

## Public API Sketch

### Platform Entry

```java
MassPlatform mass = MassPlatform.builder()
        .baseUrl("http://localhost:8088")
        .apiKey("mass_sk_xxx")
        .connectTimeout(Duration.ofSeconds(5))
        .requestTimeout(Duration.ofSeconds(30))
        .build();
```

Main surfaces:

```java
mass.tasks();
mass.catalog();
mass.workers();
mass.workerSessions();
```

The entry type should live under a new public package such as:

```text
com.xa.mass.client
```

Do not reuse `com.xa.mass.sdk` for this artifact. That package already means
embedded SDK compatibility.

### Task Client

Task client should follow the current public task mainline:

```java
MassTask task = mass.tasks().create(TaskCreateRequest.builder()
        .project("crawlerApp")
        .userId("agent")
        .contract(TaskContract.BATCH)
        .sharedConfig(Map.of("routeAttributes", Map.of("region", "us")))
        .build());

mass.tasks().appendItems(task.taskId(), TaskItemBatch.builder()
        .eventCode("crawler.fetch-page")
        .items(List.of(Map.of("url", "https://example.com")))
        .build());

mass.tasks().command(task.taskId(), TaskCommand.seal());
```

Initial task scope:

- list tasks.
- create task shell.
- get task shell/detail.
- patch shell fields allowed by server.
- append item batch.
- sync append one item.
- execute task command.
- read result window.
- read result archive manifest and content stream.
- report/read stage evidence only if the type shape is already stable enough
  to expose without mirroring internal projection fields.

Task client must not:

- create task shell and items in one hidden "convenience" call as the mainline.
- auto-approve or auto-seal unless the method name explicitly says it is a
  helper.
- invent a `target` field. `target` remains a conventional payload key.
- hide `eventCode` resolution; append must make event identity explicit.

### Worker Topology Client

Worker topology is explicit and WorkerGroup-first:

```java
mass.workers().declareGroup(WorkerGroupSpec.builder()
        .groupId("crawler")
        .bindEvent("crawler.fetch-page", List.of("crawlerApp"))
        .defaultAttribute("region", "us")
        .defaultMaxConcurrentWork(20)
        .build());

mass.workers().registerAdapterNode(AdapterNodeSpec.builder()
        .adapterNodeId("crawler-node-us-1")
        .adapterType("polling")
        .endpointId("crawler-node-us-1")
        .attribute("region", "us")
        .build());

mass.workers().bindNodeGroup("crawler-node-us-1", "crawler");

mass.workers().registerWorker(WorkerSpec.builder()
        .workerId("crawler-worker-001")
        .workerGroupId("crawler")
        .adapterNodeId("crawler-node-us-1")
        .transportHint("polling")
        .attribute("region", "us")
        .attribute("fingerprint", "fp-android-13-us")
        .build());
```

Initial topology scope:

- register adapter node.
- declare worker group with event bindings.
- bind adapter node to group.
- register worker execution identity.
- online / heartbeat / offline.
- poll task dispatch items.
- submit task result.
- poll worker commands.
- ack worker command.
- report worker capability.
- report worker state.

The SDK should expose worker attributes directly because Stage-2 match and
diagnostics depend on them. It should not pretend attributes are only labels.

### Polling Worker Session Runtime

Raw endpoint wrappers are not enough for Java workers. The first runtime value
of this artifact should be a small polling worker session:

```java
PollingWorkerSession session = mass.workerSessions().polling()
        .workerId("crawler-worker-001")
        .workerGroupId("crawler")
        .adapterNodeId("crawler-node-us-1")
        .project("crawlerApp")
        .event("crawler.fetch-page", dispatch -> {
            URI url = dispatch.input().requiredUri("url");
            FetchResult result = fetch(url);
            return WorkerResult.success(Map.of(
                    "url", url.toString(),
                    "statusCode", result.statusCode(),
                    "title", result.title()
            ));
        })
        .attribute("region", "us")
        .attribute("fingerprint", "fp-android-13-us")
        .maxMessages(10)
        .heartbeatInterval(Duration.ofSeconds(10))
        .pollInterval(Duration.ofSeconds(1))
        .start();
```

Session responsibilities:

- idempotent startup sequence:
  declare group if configured, register adapter node, bind node/group,
  register worker, mark online, report capability, report state.
- heartbeat loop.
- poll loop with bounded backoff.
- handler registry keyed by global `eventCode`.
- result conversion and submit.
- command poll and ack hook.
- offline best effort on close.
- graceful shutdown through `AutoCloseable`.
- structured error callbacks for handler errors, submit failures, auth
  failures, and repeated poll failures.

Session responsibilities explicitly excluded:

- no local scheduling.
- no local task retries after server has accepted a result.
- no local worker matching.
- no hidden WorkerGroup capability mutation after startup unless an explicit
  API call requests it.
- no auto-run task creation.

### Realtime Worker Client

Realtime Java client support should be a later phase, not the first public
freeze. The current Java WebSocket/socket samples prove protocol feasibility,
but the SDK should not lock a public realtime API until frame lifecycle,
command frames, reconnect behavior, and route identity are documented as a
stable external contract.

Target later shape:

```java
RealtimeWorkerSession session = mass.workerSessions().realtime()
        .workerId("crawler-realtime-001")
        .adapterId("websocket")
        .transportHint("realtime")
        .endpoint(URI.create("ws://127.0.0.1:18088/ws"))
        .event("crawler.fetch-page", handler)
        .start();
```

Realtime phase must preserve `adapterId + transportHint` semantics and must not
collapse websocket/socket into a generic hidden transport guess.

## Error And Response Handling

All HTTP APIs use the `ApiResponse<T>` envelope:

```json
{"code":0,"msg":"ok","data":{}}
```

Client rules:

- non-2xx HTTP status throws `MassHttpException`.
- 2xx with non-zero `code` throws `MassApiException`.
- JSON parse failure throws `MassProtocolException`.
- timeout throws `MassTimeoutException`.
- auth failures should preserve HTTP status, server `msg`, and request path.
- exceptions must include method/path and safe request identity, but must not
  log API-key values.

The SDK should provide a raw response access option only where it is needed for
streaming archive content or diagnostics. Normal callers should not parse the
envelope manually.

## Authentication

Initial auth support:

- `X-Mass-Api-Key: <credential>`
- `Authorization: Bearer <credential>` as optional builder mode

Builder default should use `X-Mass-Api-Key`, matching current server worker and
submitter routes.

The SDK should not own API-key lifecycle policy. It may call server API-key
routes later, but key issuance, approval, revocation, and usage accounting are
server identity-access concerns.

## DTO Strategy

Do not depend on server controller DTOs.

Use one of these paths, in order:

1. Reuse `xa-mass-sdk-api` models only when they are already stable public
   contracts and do not pull embedded runtime composition.
2. Define small `xa-mass-java-sdk` wire DTOs for HTTP-specific requests and
   responses.
3. If too many DTOs become duplicated between server and client, add a later
   roadmap to extract a shared HTTP contract artifact. Do not do that in the
   first slice.

Client DTOs should model caller intent, not internal runtime records. Avoid
mirroring every engine/task/worker field into the Java client.

## Roadmap

### JSDK-0: Contract Inventory

Scope:

- finalize `integrations/` as the repository owner directory for this SDK and
  record which existing modules are candidates for later migration.
- inventory current `/api/v1/tasks`, `/api/v1/catalog`, `/api/v1/submitters`,
  and `/worker-api/v1` routes used by the first SDK.
- classify which `xa-mass-sdk-api` models can be reused safely.
- identify server DTOs that must not be imported.
- decide JSON mapper dependency and Java baseline.
- choose exact artifact coordinates and package names.

Out of scope:

- implementation.
- generated OpenAPI client.
- WebSocket public client API.
- moving existing `samples/` or `xa-mass-worker-pack`.

Acceptance:

- target module path is fixed as `integrations/xa-mass-java-sdk`.
- inventory document lists every route included in JSDK-1 through JSDK-4.
- every reused model has a stated owner and dependency reason.
- every non-reused server DTO has a replacement client DTO plan.
- target package is fixed as `com.xa.mass.client` or a documented alternative.

### JSDK-1: Module Skeleton And HTTP Core

Scope:

- add `integrations/xa-mass-java-sdk` Maven module with artifactId
  `xa-mass-java-sdk`.
- add `MassPlatform` builder.
- add HTTP core with base URL normalization, auth header injection,
  timeouts, JSON encode/decode, envelope unwrap, and typed exceptions.
- add architecture tests that block imports from forbidden runtime/server
  modules.
- add README with positioning relative to `xa-mass-sdk`.

Out of scope:

- worker session loops.
- task convenience workflows.
- realtime transports.
- moving existing sample or worker-pack directories.

Acceptance:

- module builds standalone through reactor.
- architecture guard proves no dependency on engine, server, embedded SDK,
  worker-runtime, or transport implementation modules.
- unit tests cover envelope success, non-zero API response, non-2xx response,
  timeout configuration, and auth header redaction.

### JSDK-2: Task Client

Scope:

- implement task shell create/list/get/patch.
- implement item append and sync append.
- implement task command.
- implement result window read and archive manifest/content read.
- add typed request builders for current public task mainline.
- add focused HTTP contract tests with a fake server or mock HTTP layer.

Out of scope:

- task auto-run as the default API.
- internal debug invocation.
- console review/export internal routes.

Acceptance:

- example task flow compiles:
  create shell -> append items -> seal -> read results.
- SDK keeps shell create and item ingest explicit.
- unknown or unsupported server response is surfaced as protocol error instead
  of silently ignored.
- task APIs preserve `eventCode` on append rather than moving it to shell
  truth.

### JSDK-3: Worker Topology Client

Scope:

- implement adapter-node registration.
- implement WorkerGroup declaration.
- implement node/group binding.
- implement worker registration.
- implement direct worker online/heartbeat/offline/poll/result-submit.
- implement command poll/ack.
- implement capability and state reports.

Out of scope:

- managed background worker session.
- realtime worker client.
- operator runtime worker list APIs unless needed for tests.

Acceptance:

- Java polling sample can be rewritten to use only `xa-mass-java-sdk` topology
  client plus direct poll calls.
- WorkerGroup-first registration is visible in the client API.
- worker attributes, including fingerprint-style attributes, are represented
  as first-class registration/report inputs.
- direct client calls do not hide transport identity defaults except the
  server-supported polling default.

### JSDK-4: Polling Worker Session Runtime

Scope:

- add managed polling worker session builder.
- add handler registry keyed by global `eventCode`.
- add heartbeat loop, poll loop, result submit, command poll/ack hook, state
  report hook, and close/offline handling.
- add bounded retry/backoff policy for network errors.
- add lifecycle callbacks for startup failure, dispatch handler failure,
  submit failure, and shutdown.
- migrate Java polling sample to the managed session.

Out of scope:

- local work queue durability.
- local task retry after server acceptance.
- worker matching or reserve decisions.
- task creation from inside the session by default.

Acceptance:

- black-box Java polling worker test passes using the SDK session.
- session shutdown reports offline best effort.
- handler exception submits a failed result with structured detail.
- repeated server/auth failures stop or back off according to documented
  policy.
- command acknowledgement remains explicit and observable.

### JSDK-5: Samples And External Proof

Scope:

- add quickstart snippets for:
  - task-only submitter.
  - polling worker group with fingerprint attributes.
  - task producer plus polling worker end-to-end.
- update sample README references to prefer `xa-mass-java-sdk` for Java
  external client code.
- keep raw HTTP samples only where they prove non-Java parity or protocol
  minimalism.
- add black-box proof that uses a real server port where existing test
  infrastructure supports it.
- decide whether sample path convergence should happen in this slice or a
  follow-up slice:
  - `samples/...` -> `integrations/samples/...`
  - update README commands, launch scripts, and black-box test process paths.

Out of scope:

- replacing Node.js samples.
- generated docs site.
- moving `xa-mass-worker-pack`; that depends on whether its Java worker paths
  already consume the new SDK.

Acceptance:

- examples are runnable against `xa-mass-server`.
- Java sample output includes worker identity and eventCode evidence.
- proof demonstrates:
  task shell -> item append -> polling worker dispatch -> result submit ->
  result read.
- proof includes at least one WorkerGroup with fingerprint-like attributes so
  match diagnostics have real attribute evidence.

### JSDK-5.5: Worker Pack Integration Convergence

Scope:

- migrate Java external-worker code inside `xa-mass-worker-pack` to consume
  `xa-mass-java-sdk` where it talks to public server/worker APIs.
- keep worker-pack sample command/fault runtime local to worker-pack.
- move module path only after dependencies are clean:
  `xa-mass-worker-pack` -> `integrations/xa-mass-worker-pack`.
- update Maven reactor, README links, sample launchers, and black-box tests in
  the same slice.

Out of scope:

- moving worker-pack command/fault behavior into the Java SDK.
- making worker-pack a dependency of `xa-mass-java-sdk`.
- changing engine, transport, or server ownership to support the move.

Acceptance:

- worker-pack depends on `xa-mass-java-sdk` only for external client/session
  behavior.
- `xa-mass-java-sdk` does not depend on worker-pack.
- Java worker-pack sample/client paths no longer duplicate SDK HTTP polling
  boilerplate.
- all executable sample paths and test launch commands use the new
  `integrations/` locations.

### JSDK-6: Realtime Client Decision Point

Scope:

- inventory WebSocket and socket sample protocols.
- decide whether public realtime SDK should support WebSocket first, socket
  first, or a transport-neutral frame session.
- document frame contract, reconnect behavior, command ack behavior, and
  presence semantics before implementing public API.
- only then add realtime Java session support.

Out of scope:

- changing transport server protocol to fit the client.
- hiding `adapterId` / `transportHint`.

Acceptance:

- a protocol contract exists before public realtime client API is added.
- realtime session has black-box parity with current Java WebSocket or socket
  sample.
- polling client remains the recommended first public worker integration path.

## Architecture Guards

Add automated guards by JSDK-1:

- `xa-mass-java-sdk` must not import:
  - `com.xa.mass.engine..`
  - `com.xa.mass.starter..`
  - `com.xa.mass.worker.runtime..`
  - `com.xa.mass.api.internal..`
  - transport implementation runtime packages.
- server must not depend on `xa-mass-java-sdk`.
- samples may depend on `xa-mass-java-sdk`.
- tests may use server test harnesses, but production SDK code must not.

Add process guard:

- any new public client method must map to a documented public HTTP route or a
  documented realtime public protocol frame.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| SDK becomes second embedded SDK | repeats current boundary confusion | strict forbidden imports and package split |
| DTO duplication grows | client/server drift | start small; consider shared HTTP contract only after evidence |
| Worker session hides platform truth | debugging becomes opaque | keep topology explicit and expose lifecycle callbacks |
| Realtime API freezes too early | public compatibility debt | keep realtime as JSDK-6 decision point |
| Convenience API recreates create-with-inputs | violates task shell/item split | keep task shell and item append explicit |
| WorkerGroup-first model gets buried | match proof weakens | make group declaration and attributes first-class in worker APIs |

## Verification Matrix

Minimum verification per phase:

| Phase | Verification |
| --- | --- |
| JSDK-0 | inventory review only |
| JSDK-1 | module compile, HTTP core unit tests, architecture guard |
| JSDK-2 | task client unit tests, fake-server HTTP contract tests |
| JSDK-3 | worker topology fake-server tests, Java polling sample compile |
| JSDK-4 | real server black-box polling worker test |
| JSDK-5 | end-to-end sample proof through task result read |
| JSDK-5.5 | worker-pack integration tests and path migration proof |
| JSDK-6 | protocol contract review plus realtime black-box test |

Full reactor is not required after every phase, but JSDK-4 and later should run
the relevant server black-box suites because worker lifecycle errors are often
only visible through dispatch/result convergence.

## Non-Goals

- Do not rename or replace current `xa-mass-sdk`.
- Do not merge this client into `xa-mass-sdk`.
- Do not keep new external integration modules at repo root.
- Do not generate a broad OpenAPI client as the first implementation.
- Do not expose internal debug routes as stable client APIs.
- Do not make task creation auto-run by default.
- Do not treat project as part of event identity.
- Do not move worker matching decisions into the client.
- Do not make WebSocket/socket client support part of the first phase unless a
  stable public frame contract is approved first.
- Do not add compatibility aliases under `com.xa.mass.sdk` for this artifact.
- Do not move `samples/` or `xa-mass-worker-pack` in the first SDK skeleton
  slice.
