# Java External SDK Realtime Decision

Status: archived superseded JSDK-6 decision record for
[`JAVA_EXTERNAL_SDK_ROADMAP.md`](./2026-05-28_JAVA_EXTERNAL_SDK_ROADMAP.md).

Superseded by the active
[`JAVA_EXTERNAL_SDK_REALTIME_PROTOCOL_ROADMAP.md`](../../JAVA_EXTERNAL_SDK_REALTIME_PROTOCOL_ROADMAP.md),
which delivered the first WebSocket worker session slice.

## Decision

Do not add public Java realtime worker sessions to `xa-mass-java-sdk` yet.

Keep the Java SDK's worker runtime on the stable polling contract for now.
WebSocket and socket remain active validation adapters, but their current frame
shapes are adapter-local compatibility seams rather than a stable public Java
SDK protocol.

## Current Evidence

Current public external-worker truth is still:

- WorkerGroup declaration and worker registration through `/worker-api/v1/**`.
- polling online/heartbeat/poll/submit-result/report-state/report-capability
  as the stable worker data-plane API.
- realtime online presence through adapter connection, not through registration.

Current realtime adapter facts:

- WebSocket uses a workerId-led connection and adapter-local JSON frames.
- Socket uses line-delimited JSON with explicit `hello` and `heartbeat` frame
  recognition.
- both adapters encode canonical task dispatch frames with fields such as
  `taskId`, `messageId`, `workerId`, `project`, `eventCode`, `input`, and
  `sharedConfig`.
- both adapters decode canonical task result frames with `taskId`,
  `messageId`, boolean `success`, optional `detail`, optional `errorCode`, and
  object `output`.
- realtime worker command frames use `type=worker.command` in sample clients,
  but command acknowledgement currently goes back through embedded
  `WorkerControlOperations` in worker-pack, not through a public Java realtime
  SDK wire contract.

The important gap is not task result shape. The task dispatch/result frame
shape is already close across WebSocket and socket. The gap is public session
lifecycle:

- connection handshake differs by adapter.
- reconnect and presence semantics are adapter-owned.
- command delivery and acknowledgement are not documented as a stable
  external wire protocol.
- worker-pack fault behavior is sample-runtime local and must not be moved into
  the public Java SDK.

## Consequence

`xa-mass-java-sdk` should not grow:

- `mass.workerSessions().realtime()`
- WebSocket/socket frame codecs
- generic adapter guessing over `adapterId`
- worker-pack fault/command runtime behavior

until a later realtime contract slice defines:

- endpoint and handshake rules for each adapter.
- task dispatch frame schema.
- task result frame schema.
- worker command frame schema.
- command acknowledgement path.
- reconnect, offline, and duplicate-result behavior.
- black-box parity proof against at least one Java realtime sample.

## Next Valid Step

If realtime SDK support becomes a priority, follow the dedicated
[`JAVA_EXTERNAL_SDK_REALTIME_PROTOCOL_ROADMAP.md`](../../JAVA_EXTERNAL_SDK_REALTIME_PROTOCOL_ROADMAP.md)
before implementation.

That roadmap should choose one of these explicitly:

- WebSocket-first public Java realtime client.
- socket-first public Java realtime client.
- a small transport-neutral `RealtimeWorkerSession` over per-adapter endpoint
  implementations.

Do not implement any of those choices as part of the current external SDK
roadmap without that protocol contract.
