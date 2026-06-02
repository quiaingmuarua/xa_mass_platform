# Java External SDK Public Hardening Roadmap

Status: proposed implementation roadmap.

This roadmap hardens `sdk/xa-mass-java-sdk` from an internally usable external
SDK into a more reliable public-facing Java SDK surface. It is scoped to SDK
contract correctness, worker session lifecycle semantics, README
compile-readiness, and dependency/boundary guardrails.

Companion inventory:
[JAVA_EXTERNAL_SDK_PUBLIC_HARDENING_INVENTORY.md](./JAVA_EXTERNAL_SDK_PUBLIC_HARDENING_INVENTORY.md).

Related direction:
[JAVA_EXTERNAL_SDK_REALTIME_SESSION_HARDENING_DECISION.md](./JAVA_EXTERNAL_SDK_REALTIME_SESSION_HARDENING_DECISION.md).

## Current Code Observations

- `xa-mass-java-sdk` production dependencies are currently limited to
  `xa-mass-public-contract`, Jackson, and JDK HTTP/WebSocket APIs.
- `MassPlatform` owns base URL, auth, platform `connectTimeout`,
  `requestTimeout`, optional `HttpClient`, and optional `ObjectMapper`.
- `WorkerSessions` currently receives only `WorkerClient`, so managed WebSocket
  sessions do not inherit platform-level `connectTimeout`, `HttpClient`, or
  `ObjectMapper` defaults.
- `PollingWorkerSession` and `WebSocketWorkerSession` both use the
  transport-neutral handler runtime, but their session observability is not yet
  cleanly separated by lifecycle event type.
- `WebSocketWorkerSession.handleFrame()` currently reports frame decode failure
  as connection failure.
- `PollingWorkerSession.heartbeatOnce()` currently reports heartbeat failure as
  poll failure with a hard-coded consecutive failure count of `1`.
- `PollingWorkerSession` registers a polling worker without explicitly setting
  concrete `adapterId`, while `WebSocketWorkerSession` sets
  `adapterId(adapterType)`.
- `sdk/xa-mass-java-sdk/README.md` currently contains stale snippets that do
  not compile against the current SDK classes.
- `./mvnw -pl sdk/xa-mass-java-sdk test` is not a reliable clean-checkout
  command because `xa-mass-public-contract` must also be built; the verified
  command is `./mvnw -pl sdk/xa-mass-java-sdk -am test`.

## Owner Review

`xa-mass-java-sdk` owns external Java caller ergonomics for task producers,
worker topology registration, and external worker sessions.

`xa-mass-public-contract` owns public HTTP wire DTOs shared by server and SDK.
The Java SDK may consume and document those DTOs, but must not fork or redefine
wire DTO shape locally.

`integrations` owns executable adopters and worker capability packs. It may use
the Java SDK to prove external registration and execution, but it must not
become the SDK product owner.

`transport` owns adapter runtime behavior. Java SDK sessions may implement JDK
transport clients, but adapter-local wire frames must not redefine kernel
Task/Worker/Scheduling/Matching semantics.

## Boundary Decision

- Keep `xa-mass-java-sdk` as a pure external JVM SDK.
- Keep `xa-mass-public-contract` as the only production shared model dependency
  for public HTTP Controller DTOs.
- Treat session listener callbacks as the public Java SDK observability
  contract; do not leak transport implementation internals into handler APIs.
- Treat `adapterId` as concrete worker registration/routing truth and
  `transportHint` as the coarse family hint.
- Treat public-contract request mutability as a separate public-contract
  decision, not as isolated Java SDK cleanup.

## Target Shape

- README and quickstart snippets compile against the current SDK API.
- Public verification commands work from a clean checkout.
- Polling and WebSocket managed sessions expose clear lifecycle callbacks:
  startup, handler failure, submit failure, queued result failure, poll failure,
  heartbeat failure, connection failure, connection recovery, shutdown failure,
  and frame/protocol failure where applicable.
- Frame/protocol failures do not mutate connection-failure counters or trigger
  reconnect exhaustion by themselves.
- Platform-level `connectTimeout`, optional `HttpClient`, and optional
  `ObjectMapper` defaults reach managed WebSocket sessions unless explicitly
  overridden by the session builder.
- Managed worker sessions register consistent concrete `adapterId` values.
- WebSocket reconnect backoff reaches the configured maximum and has tests for
  close/reconnect terminal result behavior.

## Hard Rules

- Do not add dependencies from `xa-mass-java-sdk` to engine, server, base,
  transport implementation modules, embedded SDK modules, or worker-pack.
- Do not make `integrations` or worker-pack a dependency of SDK modules.
- Do not describe target behavior as current behavior in README, quickstart, or
  owner docs.
- Do not move worker capability code into SDK modules.
- Do not convert public-contract DTOs locally in Java SDK; public DTO shape
  changes must be coordinated through `xa-mass-public-contract`.
- SDK/public-contract/integrations boundary changes must update
  `sdk/README.md` and `integrations/README.md` together.

## Non-Goals

- No Node SDK track.
- No Android/device worker-host support inside the pure Java SDK.
- No socket worker session promotion.
- No shared `RealtimeWorkerSession` abstraction until at least two realtime
  transports share a proven public lifecycle.
- No public registry publication work in this roadmap.
- No server/engine behavior changes unless required to keep the current public
  HTTP contract tests passing.

## Do Not Start With

Do not start by adding a generic transport abstraction or converting
public-contract DTO classes to records. First fix the concrete public SDK
contract failures: compile-ready docs, listener semantics, platform config
propagation, and managed-session consistency.

## JSDKH-0 Inventory And Baseline Verification

Scope:

- Keep `JAVA_EXTERNAL_SDK_PUBLIC_HARDENING_INVENTORY.md` current while executing
  this roadmap.
- Confirm current code observations against source before each implementation
  slice.
- Establish the clean-checkout verification command.

Acceptance:

- Inventory lists first-slice symbols and target owner for each issue.
- README verification uses `./mvnw -pl sdk/xa-mass-java-sdk -am test` as the
  default SDK test command.
- Current single-module command failure is either removed from README or
  documented only as requiring a preinstalled `xa-mass-public-contract`.

Verification:

```powershell
./mvnw -pl sdk/xa-mass-java-sdk -am test
```

## JSDKH-1 Compile-Ready Public Docs

Scope:

- Fix `sdk/xa-mass-java-sdk/README.md` stale examples:
  - use `TaskResultItem.output()`
  - replace nonexistent `onDispatchFailure` with real listener callbacks
  - use `WorkerSessionDispatchFailure.cause()` or explicit dispatch context
    access instead of nonexistent `message()`
- Review `EXTERNAL_SDK_QUICKSTART.md` for the same API drift.
- Keep SDK user lane references in root README, `sdk/README.md`, and
  `integrations/README.md` consistent.

Acceptance:

- No active Markdown under `sdk/xa-mass-java-sdk` references nonexistent SDK
  methods from this inventory.
- README verification commands are clean-checkout safe.
- SDK/integrations owner README boundary wording still points SDK users to the
  SDK quickstart and integrations users to adopters/proofs.

Verification:

```powershell
rg -n "item\\.result\\(|onDispatchFailure|failure\\.message\\(" sdk/xa-mass-java-sdk -g "*.md"
./mvnw -pl sdk/xa-mass-java-sdk -am test
```

## JSDKH-2 Session Listener Semantics

Scope:

- Add or reshape listener failure records so heartbeat failure, poll failure,
  connection failure, connection recovery, and frame/protocol failure are
  distinct enough for external callers.
- Update `PollingWorkerSession.heartbeatOnce()` so heartbeat failure no longer
  reports as poll failure with count `1`.
- Update `WebSocketWorkerSession.handleFrame()` so frame decode/protocol
  failure does not increment `consecutiveConnectionFailures`.
- Preserve transport-neutral handler runtime independence from session and
  transport packages.

Acceptance:

- Poll failures only represent poll-loop failures.
- Heartbeat failures have their own callback or failure type with a meaningful
  consecutive failure count if counted.
- WebSocket frame/protocol failures are observable but do not trigger reconnect
  exhaustion by themselves.
- Existing listener methods remain coherent; if methods are renamed or added,
  README and quickstart examples are updated in the same slice.
- Tests cover heartbeat failure classification and WebSocket frame decode
  classification.

Verification:

```powershell
./mvnw -pl sdk/xa-mass-java-sdk -am "-Dtest=PollingWorkerSessionTest,WebSocketWorkerSessionTest,JavaExternalSdkArchitectureGuardTest" test
```

## JSDKH-3 Platform Configuration Propagation

Scope:

- Extend `WorkerSessions` construction so platform-level defaults can reach
  session builders.
- Propagate platform `connectTimeout` into `WebSocketWorkerSession.Builder`
  default unless the caller overrides it.
- Propagate platform `HttpClient` into the default WebSocket connector unless
  the caller overrides `httpClient(...)` or an internal test connector.
- Propagate platform `ObjectMapper` into WebSocket frame encode/decode instead
  of using a hard static mapper.

Acceptance:

- `MassPlatform.builder().connectTimeout(...)` affects WebSocket session
  connection timeout by default.
- A custom platform `HttpClient` can be used by managed WebSocket session
  default connection setup.
- A custom platform `ObjectMapper` is used for WebSocket frame parsing/result
  encoding unless explicitly overridden.
- Existing session builder explicit overrides still win over platform defaults.

Verification:

```powershell
./mvnw -pl sdk/xa-mass-java-sdk -am "-Dtest=MassPlatformTest,WebSocketWorkerSessionTest" test
```

## JSDKH-4 Managed Worker Registration Consistency

Scope:

- Ensure `PollingWorkerSession` and `WebSocketWorkerSession` both register
  concrete `adapterId` and coarse `transportHint` consistently.
- Keep `adapterId` as concrete routing truth and `transportHint` as family
  hint in README/quickstart wording.

Acceptance:

- Polling managed session registration includes `adapterId("polling")` by
  default or the configured polling adapter type when made configurable.
- WebSocket managed session keeps concrete `adapterId(adapterType)`.
- WorkerClient/session tests assert both `adapterId` and `transportHint` where
  relevant.

Verification:

```powershell
./mvnw -pl sdk/xa-mass-java-sdk -am "-Dtest=PollingWorkerSessionTest,WebSocketWorkerSessionTest,WorkerClientTest" test
```

## JSDKH-5 WebSocket Lifecycle And Result Queue Hardening

Scope:

- Fix reconnect backoff so configured `maxReconnectBackoff` is reachable.
- Decide whether `close()` waits briefly for `sendClose(...)` or documents and
  tests best-effort close behavior.
- Add connection recovery callback behavior if JSDKH-2 introduces it.
- Verify queued-result terminal callbacks for close and reconnect exhaustion.

Acceptance:

- Backoff tests prove default values can reach the configured max.
- Close behavior is explicit in code tests and README wording.
- Reconnect success/recovery is observable if exposed by listener contract.
- Queued results abandoned on close or reconnect exhaustion are reported once
  through `onQueuedResultAbandoned(...)`.

Verification:

```powershell
./mvnw -pl sdk/xa-mass-java-sdk -am "-Dtest=WebSocketWorkerSessionTest" test
```

## JSDKH-6 Public-Contract DTO Shape Decision

Scope:

- Review whether mutable `UnknownFieldRequest`-based task request classes are
  acceptable as the public contract shape for the current internal staging
  level.
- If they remain, document the reason in `xa-mass-public-contract/README.md`
  and avoid treating mutability as a Java SDK-local bug.
- If they should change, create a separate public-contract roadmap before
  changing DTO shape.

Acceptance:

- The roadmap records one decision:
  - keep mutable public-contract wire DTOs for unknown-field capture for now,
    or
  - split a new public-contract DTO-shape roadmap.
- No Java SDK-local duplicate DTOs are introduced.
- `sdk/README.md`, `integrations/README.md`, and public-contract docs stay
  consistent with the decision.

Verification:

```powershell
./mvnw -pl sdk/xa-mass-public-contract,sdk/xa-mass-java-sdk -am test
```

## JSDKH-7 Guards, Proof, And Residue Scan

Scope:

- Add or update tests/guards that prevent recurrence of:
  - SDK README examples drifting from real API names
  - Java integrations hard-coding public platform route literals outside SDK
  - SDK production dependencies crossing forbidden module boundaries
  - handler runtime importing session or transport packages
- Run residue scan for stale worker-only quickstart names and stale listener
  vocabulary.
- Update `sdk/README.md`, `integrations/README.md`, and
  `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md` if boundary rules changed.

Acceptance:

- Architecture guard still passes.
- No active Markdown references stale `EXTERNAL_WORKER_QUICKSTART`,
  `onDispatchFailure`, `item.result()`, or other removed listener/result
  vocabulary.
- Owner README files match implemented behavior.
- Roadmap status can be changed to implemented only after residue scan and
  proof commands pass.

Verification:

```powershell
rg -n "EXTERNAL_WORKER_QUICKSTART|onDispatchFailure|item\\.result\\(|failure\\.message\\(" -g "*.md" -g "!doc/archive/**" -g "!**/target/**"
./mvnw -pl sdk/xa-mass-java-sdk -am test
./mvnw -pl integrations/xa-mass-scenario-launcher -am -DskipTests package
```

## Suggested Implementation Order

1. JSDKH-1
2. JSDKH-2
3. JSDKH-3
4. JSDKH-4
5. JSDKH-5
6. JSDKH-6
7. JSDKH-7

JSDKH-1 should be first because stale public docs can mislead every later SDK
consumer. JSDKH-2 and JSDKH-3 should be completed before deeper WebSocket
queue/reconnect work so lifecycle semantics and platform defaults are clear.

## Completion And Archive Rule

When all mainline slices are implemented:

1. run the JSDKH-7 residue scan
2. extract any still-current behavior into `sdk/README.md`,
   `sdk/xa-mass-java-sdk/README.md`,
   `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md`, or
   `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md`
3. move this roadmap and its inventory to
   `doc/archive/sdk/YYYY-MM-DD_<NAME>.md`

Archived roadmap prose must not remain in active README files as current truth.
