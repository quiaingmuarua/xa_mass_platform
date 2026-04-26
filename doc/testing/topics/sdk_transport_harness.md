# SDK Transport Harness

## 1. Scope

This topic covers the fast embedded-runtime harness in `xa-mass-testing` that exercises
real SDK registration plus real worker transport scheduling without booting the full
`xa-mass-dev-app` Spring Boot shell.

Use it when you want:

- real `MassSdkApplication` startup
- real worker registration through SDK contracts
- real polling or WebSocket worker scheduling
- quicker iteration than Boot-shell E2E when HTTP/controller behavior is not the subject

Do not use it as the only acceptance evidence when the touched path includes:

- HTTP/controller contract changes
- control-console routing
- Boot auto-configuration / Spring context wiring

## 2. Core Code Path

- [SdkTransportLoadRunner.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-testing/src/main/java/com/xa/mass/testing/concurrency/SdkTransportLoadRunner.java)
- [MassSdkApplication.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-sdk/src/main/java/com/xa/mass/sdk/MassSdkApplication.java)
- [MassApplication.java](D:/code_project/geekrun/xa_mass_platform/xa-mass-sdk/src/main/java/com/xa/mass/starter/MassApplication.java)

The harness validates that the SDK surface is already enough to:

- start embedded runtime composition
- register workers and worker contexts
- drive polling worker sessions
- drive registered WebSocket workers through the realtime adapter

## 3. Commands

Polling worker mode:

```bash
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=polling
```

WebSocket worker mode:

```bash
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=websocket
```

Useful scale knobs:

```bash
-Dmass.sdk.load.tasks=16
-Dmass.sdk.load.messagesPerTask=32
-Dmass.sdk.load.workers=8
-Dmass.sdk.load.batchSize=4
-Dmass.sdk.load.workerProcessingThreads=2
-Dmass.sdk.load.retryFailureEveryNth=11
```

Artifact:

- JSON report under `xa-mass-testing/target/concurrency-reports/`

## 4. How To Read Results

Look at these first:

- `runtime.transport`
- `tasks.terminalReasons`
- `messages.successMessages`
- `workerMetrics.receivedDispatchItems`
- `workerMetrics.maxConcurrentProcessing`

Use the harness to answer:

- does SDK registration still compose into a runnable transport-aware runtime
- does polling behave differently from WebSocket under the same scheduling pressure
- did a transport_runtime change break real scheduling without needing the full Boot shell

## 5. Position In The Test System

This is a support lane, not a core acceptance replacement.

Use it:

- before broad Boot-shell E2E when you need a faster transport-aware probe
- alongside engine `concurrency` when the issue spans engine plus runtime transport composition

Escalate to Boot-shell E2E when the user-facing app shell or HTTP API contract is part of the risk.
