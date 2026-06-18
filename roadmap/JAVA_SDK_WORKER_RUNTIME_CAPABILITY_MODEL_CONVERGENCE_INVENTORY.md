# Java SDK Worker Runtime Capability Model Inventory

Status: current inventory for
`JAVA_SDK_WORKER_RUNTIME_CAPABILITY_MODEL_CONVERGENCE_ROADMAP.md`.

## Current Public Worker Model

- `WorkerRuntimeDefinition` is the Java SDK worker ability owner:
  `workerId`, `workerGroupId`, attributes, and event handlers.
- `WorkerSpec.polling(definition)` and `WorkerSpec.realtime(definition)` are
  explicit registration projections from the definition.
- `WorkerRuntimes.polling(definition)` and
  `WorkerRuntimes.webSocket(definition)` build protocol runtimes over the same
  definition.
- `WorkerRuntime` is the public managed worker runtime shell:
  `workerId`, `workerGroupId`, `transportHint`, `reporter`, `start`,
  `isRunning`, and `close`.
- `WorkerRuntimeReporter` is the explicit worker-local evidence/report owner.
  It publishes handler evidence from `WorkerRuntimeDefinition` and runtime
  evidence through `WorkerClient`.
- `WorkerRuntimeContext` is the package-private common runtime derivation
  owner for polling and WebSocket runtimes. It derives worker identity,
  immutable attributes/handlers, listener, executor, dispatch processor, and
  reporter from `WorkerRuntimeDefinition` plus runtime-common options.
- `WorkerRuntimeOptions` is package-private runtime-common wiring only:
  listener and executor. It does not contain polling interval, endpoint,
  reconnect, queue, or other protocol-specific settings.
- `PollingWorkerProtocolDriver` owns polling worker-api exchange:
  `online`, `heartbeat`, `poll`, `submitResult`, and `offline`.
- `WebSocketWorkerProtocolDriver` owns WebSocket connect URI construction,
  connector selection, and dispatch/result frame codec.
- `WorkerSessionSpec` has been removed and is not a compatibility alias.
- Handler-facing invocation is `WorkerInvocation(resultCorrelationRef,
  eventCode, input, sharedConfig)`.
- Handler output is `WorkerResult(success, resultCode, result)`.
- Result publication is `WorkerResultSubmission(resultCorrelationRef, success,
  resultCode, result)`.

## Current Runtime Behavior

- `PollingWorkerRuntime.start()` does not register the worker. It manages
  session presence (`online`), heartbeat, polling, handler dispatch, result
  submission, and `offline` on close.
- `WebSocketWorkerRuntime.start()` does not register the worker. It manages
  WebSocket connect/reconnect, frame intake, handler dispatch, queued result
  sending, and close.
- `WorkerRuntimeStartupStep.REGISTER_WORKER` has been removed; registration
  failures belong to the explicit registration API.
- Managed runtimes do not publish handler/runtime evidence during `start()`.
  `WorkerRuntimeReporter` publishes `reportHandlerEvidence(...)` and
  `reportRuntimeEvidence(...)` explicitly when callers ask for it.
- Polling heartbeat is now scheduled through package-private
  `WorkerRuntimeMaintenanceLoop`.
- Polling and WebSocket runtimes now read common worker/runtime facts from
  `WorkerRuntimeContext`; protocol-specific options remain on their concrete
  builders and drivers.

## Remaining Runtime Coupling

- Polling and WebSocket classes still own protocol loops and lifecycle
  orchestration directly.
- Polling still owns poll backoff and result submit inside the concrete
  runtime.
- WebSocket still owns reconnect policy, queued result handling, result sender
  loop, and lifecycle callbacks inside the concrete runtime.
- `WorkerRuntimeListener` is a broad diagnostic sink, but failure reporting is
  one public `WorkerRuntimeFailureEvent` model. Dedicated heartbeat, frame,
  poll, connection, dispatch, queued-result, and startup failure records have
  been removed.
- Worker command intake/ack remains deferred. The deleted Java SDK command DTOs
  must not be reintroduced before a worker-control owner decision.

## Superseded Context

- `EXTERNAL_WORKER_CAPABILITY_EVIDENCE_API_CONVERGENCE_ROADMAP.md` is
  superseded historical context. Current code uses
  `reportHandlerEvidence(...)` and `reportRuntimeEvidence(...)`.
- Java SDK public model cleanup has landed. Old `WorkerDispatchItem`,
  `DispatchContext`, `ResultCorrelationRef`, `WorkerEventHandlers`,
  `WorkerEventInvocation`, `WorkerResultSink`, and task-shaped handler context
  must stay removed.
