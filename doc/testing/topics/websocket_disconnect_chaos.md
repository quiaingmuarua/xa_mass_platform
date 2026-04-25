# WebSocket Disconnect Chaos

Use this topic when the runtime risk is not a pure callback race, but a transport churn problem:

- a WebSocket worker disconnects after receiving a dispatch
- the worker reconnects later and still submits the delayed result
- later tasks must still dispatch successfully after the reconnect

## Start Here

- runner: `xa-mass-testing/src/main/java/com/xa/mass/testing/chaos/SdkWebSocketDisconnectChaosRunner.java`
- lane: `chaos`
- runtime style: SDK embedded runtime with real WebSocket worker registration

## Verified Command

Run from repo root:

```bash
./mvnw -pl xa-mass-testing -am -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkWebSocketDisconnectChaosRunner
```

Useful knobs:

```text
-Dmass.sdk.chaos.messagesPerTask=1
-Dmass.sdk.chaos.processingDelayMillis=25
-Dmass.sdk.chaos.reconnectDelayMillis=800
-Dmass.sdk.chaos.assignmentRetryDelayMillis=100
-Dmass.sdk.chaos.leaseWatchdogIntervalSeconds=1
-Dmass.sdk.chaos.timeoutSeconds=25
```

Artifact:

- `xa-mass-testing/target/chaos-reports/*.json`

## What This Probe Proves

- SDK-registered WebSocket workers can disconnect during a real task-execution window
- the runtime marks that worker offline
- the same worker can reconnect and submit the delayed result successfully
- a steady worker can keep completing work while the chaos worker is offline
- the disconnected worker can accept later tasks again after reconnect

## What It Does Not Prove

- lease expiry followed by redispatch to a new attempt; use [lease_expiry_redispatch_chaos.md](./lease_expiry_redispatch_chaos.md) for that path
- gateway/server restart recovery
- queue jitter, dropped outbound delivery, or multi-worker reconnect storms

Those are separate chaos expansions, not the current verified surface.
