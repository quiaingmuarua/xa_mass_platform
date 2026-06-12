# Transport Worker Pull Dispatch View Convergence Roadmap

Status: implemented in current convergence slice; keep until verification and
residue review are complete, then archive with the transport owner records.

Date: 2026-06-12

## Summary

`TaskDispatchItem` mixed four unrelated facts:

- worker execution view: task id, message id, event code, input, shared config
- result correlation context: attempt id/no, retry count, batch id
- worker wire compatibility: task name, project, user id, worker id
- transport metadata/cache: route key, packet payload, packet projection

The target shape removes that hybrid model from transport core and public pull
contracts. Polling workers receive a narrow `PulledTaskDispatch`. Transport
runtime keeps envelope/store semantics. Adapter final-hop code remains the
only place allowed to assemble protocol-specific frames or packets.

## Owner Decisions

- `PulledTaskDispatch` is the polling worker DTO. It is not a transport
  metadata carrier.
- `TaskPullResult` returns `List<PulledTaskDispatch>` through `getItems()`.
- `TaskPullChannel.pollTaskMessages(...)` returns `List<PulledTaskDispatch>`.
- `TransportDeliveryService` exposes envelope poll/drain only; it does not
  create worker-facing dispatch views.
- `PollingWorkerAdapter` owns the pull-boundary projection from
  `TransportDispatchEnvelope` / `TransportPacket` into `PulledTaskDispatch`.
- `PullWorkerSession.submitResult(...)` uses session/path route identity and
  pulled attempt context. It must not read route or worker identity from a
  pulled item.

## Scope

In scope:

- transport API pull DTO and result/channel contract
- polling adapter projection
- embedded SDK pull/session APIs
- server external worker poll response shape
- `xa-mass-testing` polling runners
- focused tests, architecture guard, and current owner docs

Out of scope:

- changing WebSocket/socket worker frame compatibility fields
- changing engine assignment, retry, worker lifecycle, or route-owner semantics
- changing Redis delivery queue sharding/bucketing
- turning `PulledTaskDispatch` into a generic transport packet wrapper

## Implemented Shape

```text
DeliveryCommand
  = selectedWorkerId + TaskDispatchContent + TaskDispatchExecutionContext

TransportDispatchEnvelope
  = deliveryId + selectedWorkerId + final-hop TransportPacket + createdAt

PulledTaskDispatch
  = taskId + messageId + eventCode + input + sharedConfig
    + attemptId + attemptNo + retryCount + batchId

TaskPullResult
  = status + List<PulledTaskDispatch>
```

Forbidden on `PulledTaskDispatch`:

- route key
- transport payload
- transport packet
- worker id
- task name, project, user id
- adapter, lane, node, session, endpoint, or connection fields

## Acceptance

- Production code no longer imports
  `com.xa.mass.transport.model.TaskDispatchItem`.
- `TaskDispatchItem` production class is removed.
- `TaskPullResult#getDispatchViews()` is removed.
- `TransportDeliveryService` no longer exposes `pollDispatchViews`,
  `toDispatchView`, or `toDispatchViews`.
- Polling selected-worker proof still passes with shared route/queue workers.
- SDK/server/testing polling callers compile against `PulledTaskDispatch`.
- Owner docs and proof registry describe the new boundary.

## Verification

Primary commands:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server,xa-mass-testing -am -DskipTests compile
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter,sdk/xa-mass-embedded-sdk,xa-mass-server -am -DskipTests test-compile
./mvnw -q -pl xa-mass-testing -am -DskipTests compile
```

Focused test commands:

```bash
./mvnw -q -pl transport/transport_api,transport/transport_runtime,transport/polling-adapter,transport/socket-adapter,transport/websocket-adapter -am test -Dtest=PulledTaskDispatchTest,TaskPullResultTest,TransportDispatchEnvelopeTest,DispatchOutcomeTest,TransportPacketFactoryTest,TransportDeliveryServiceTest,InMemoryTransportDeliveryStoreTest,TransportDeliveryPollResultTest,PollingWorkerAdapterTest,SocketTaskDispatchChannelTest,WebSocketTaskDispatchChannelTest,TransportConvergenceArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl sdk/xa-mass-embedded-sdk,xa-mass-server -am test -Dtest=PullWorkerSessionTest,MassSdkTest,ExternalWorkerApiControllerTest,CrawlerPullWorkerSdkRegistrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Residue scan:

```bash
rg -n "TaskDispatchItem|getDispatchViews|pollDispatchViews|toDispatchView|toDispatchViews|fromDispatchView\\(|TransportDispatchEnvelopeFactory" transport sdk xa-mass-server xa-mass-testing -g "*.java" -g "!**/TransportConvergenceArchitectureGuardTest.java"
```
