# Java External SDK Public Hardening Inventory

Status: current code inventory for `JAVA_EXTERNAL_SDK_PUBLIC_HARDENING_ROADMAP.md`.

This inventory records the first hardening set for `sdk/xa-mass-java-sdk` as
verified from current source on 2026-06-02. It is not proof that the target
behavior is implemented.

## Symbols

| Symbol | Current Owner | Current State | Classification | Target |
| --- | --- | --- | --- | --- |
| `MassPlatform` | `sdk/xa-mass-java-sdk` | owns base URI, auth, request/connect timeout, JDK `HttpClient`, Jackson `ObjectMapper`, typed clients | external SDK root | pass platform-level connection/client/mapper defaults into `WorkerSessions` |
| `WorkerSessions` | `sdk/xa-mass-java-sdk` | wraps only `WorkerClient`; does not carry platform `connectTimeout`, `HttpClient`, or `ObjectMapper` | session factory gap | own session builder defaults derived from `MassPlatform` |
| `WebSocketWorkerSession` | `sdk/xa-mass-java-sdk` | JDK WebSocket session, result queue, bounded reconnect, static `ObjectMapper`, independent `connectTimeout=10s` | realtime worker session | distinguish frame/protocol failures from connection failures; inherit platform defaults unless explicitly overridden |
| `PollingWorkerSession` | `sdk/xa-mass-java-sdk` | managed polling session with heartbeat, poll loop, handler runtime, result submit, offline-on-close | stable polling worker session | separate heartbeat failure semantics from poll failure; register explicit `adapterId("polling")` |
| `WorkerSessionListener` | `sdk/xa-mass-java-sdk` | startup, handler, submit, queued-result, poll, connection, shutdown callbacks | public session observability contract | add or reshape callbacks for heartbeat failure, frame/protocol failure, and connection recovery without transport leakage |
| `WorkerSessionPollFailure` | `sdk/xa-mass-java-sdk` | represents poll-loop failure count | polling failure model | keep poll-specific; do not reuse for heartbeat failures |
| `WorkerSessionConnectionFailure` | `sdk/xa-mass-java-sdk` | represents WebSocket connection failure count | connection failure model | connection failures only; frame decode must not increment the reconnect failure counter |
| `WorkerSessionQueuedResultFailure` | `sdk/xa-mass-java-sdk` | queue-full and abandoned-result reporting | result queue observability | keep as result queue contract; verify close/reconnect terminal semantics |
| `WorkerSpec` | `sdk/xa-mass-java-sdk` | carries `adapterId` plus `transportHint` | worker registration request | managed sessions should fill concrete `adapterId` consistently |
| `TaskResultItem` | `sdk/xa-mass-java-sdk` | exposes `output()` result payload | SDK result DTO | README and quickstart snippets must use `output()`, not stale `result()` vocabulary |
| `TaskCommandRequest` | `sdk/xa-mass-public-contract` | mutable public-contract wire DTO extending `UnknownFieldRequest` | public contract DTO | treat as public-contract owner decision; do not locally fork in Java SDK |
| `TaskCreateRequest`, `TaskItemBatch`, `TaskItemSyncRequest` | `sdk/xa-mass-public-contract` | mutable public-contract wire DTOs with builders and unknown-field capture | public contract DTO | evaluate mutability as a public-contract roadmap/decision if needed, not as isolated Java SDK cleanup |

## Verified Findings

| Finding | Evidence | Severity | Target Slice |
| --- | --- | --- | --- |
| README result example calls missing `TaskResultItem.result()` | `sdk/xa-mass-java-sdk/README.md` result-reading example; `TaskResultItem` exposes `output()` | P1 | JSDKH-1 |
| README listener example calls missing `onDispatchFailure` and `failure.message()` | `WorkerSessionListener` has `onHandlerFailure` and `onSubmitFailure`; `WorkerSessionDispatchFailure` has `dispatch()` and `cause()` | P1 | JSDKH-1 |
| single-module README test command fails without building `xa-mass-public-contract` | `./mvnw -pl sdk/xa-mass-java-sdk test` fails; `./mvnw -pl sdk/xa-mass-java-sdk -am test` passes | P1 | JSDKH-1 |
| WebSocket frame decode failure increments connection failure counter | `WebSocketWorkerSession.handleFrame()` increments `consecutiveConnectionFailures` on decode failure | P1 | JSDKH-2 |
| polling heartbeat failure is reported as poll failure with count `1` | `PollingWorkerSession.heartbeatOnce()` calls `onPollFailure(new WorkerSessionPollFailure(workerId, 1, failure))` | P2 | JSDKH-2 |
| WebSocket session ignores platform-level `connectTimeout`, `HttpClient`, and `ObjectMapper` defaults | `MassPlatform` constructs `new WorkerSessions(workerClient)` only; `WebSocketWorkerSession` has independent defaults and static mapper | P2 | JSDKH-3 |
| Polling managed session does not set concrete `adapterId` during worker registration | `PollingWorkerSession.start()` calls `.polling()` but not `.adapterId(adapterType)` | P2 | JSDKH-4 |
| WebSocket backoff max is not reachable with default values | `connectionBackoff()` caps multiplier at `10`, so default `500ms` base never reaches default `10s` max | P2 | JSDKH-5 |
| WebSocket close handshake is best-effort but not documented or tested as such | `close()` calls `sendClose(...)` and ignores returned `CompletableFuture` | P2 | JSDKH-5 |
| public-contract task request mutability is broader than `TaskCommandRequest` | multiple `sdk/xa-mass-public-contract` task request classes extend `UnknownFieldRequest` | P3 | JSDKH-6 |

## Dependencies

| Module | Dependency | Scope | Reason | Target |
| --- | --- | --- | --- | --- |
| `sdk/xa-mass-java-sdk` | `sdk/xa-mass-public-contract` | production | public HTTP wire DTOs shared with server | keep |
| `sdk/xa-mass-java-sdk` | Jackson | production | JSON encode/decode and API envelope handling | keep, but propagate configured mapper consistently |
| `sdk/xa-mass-java-sdk` | JDK `HttpClient` / `WebSocket` | production | pure JVM external SDK transport implementation | keep |
| `sdk/xa-mass-java-sdk` | Spring Boot starter test | test | local SDK tests | keep test-only |
| `sdk/xa-mass-java-sdk` | engine/server/base/transport/worker-pack/embedded SDK | none | forbidden by SDK boundary | keep forbidden by guard |

## Current Verification Baseline

```powershell
./mvnw -pl sdk/xa-mass-java-sdk test
```

fails when `xa-mass-public-contract` is not installed or built with the module.
Use the reactor-aware command instead:

```powershell
./mvnw -pl sdk/xa-mass-java-sdk -am test
```

Current observed result on 2026-06-02:

- `xa-mass-public-contract`: 4 tests passed
- `xa-mass-java-sdk`: 36 tests passed

## Decisions

- `xa-mass-java-sdk` remains a pure external Java SDK for a running server.
- Polling remains the stable first external worker session.
- WebSocket remains a Java SDK worker session, but its frame/protocol failures
  must not be reported as connection failures.
- Public-contract DTO mutability is not owned by `xa-mass-java-sdk` alone. Any
  change to `UnknownFieldRequest` or task request class shape must be handled as
  a public-contract decision and must update `sdk/README.md`,
  `integrations/README.md`, and public-contract docs if caller behavior changes.
