# Lease Expiry Redispatch Chaos

Use this topic when the runtime risk is not just reconnect churn, but ownership handoff after a worker disappears mid-flight:

- a WebSocket worker receives a real dispatch
- that worker disconnects without submitting a result
- the attempt lease expires under `LeaseExpireWatchdog`
- the logical `TaskMsg` resets to `INIT` because retry budget remains
- dispatch moves to a different online worker and converges successfully

## Start Here

- runner: `xa-mass-testing/src/main/java/com/xa/mass/testing/chaos/SdkWebSocketLeaseExpiryRedispatchChaosRunner.java`
- lane: `chaos`
- runtime style: SDK embedded runtime with real WebSocket worker registration

## Verified Command

Run from `xa-mass-testing/`:

```bash
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.chaos.timeoutSeconds=30 org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkWebSocketLeaseExpiryRedispatchChaosRunner
```

Useful knobs:

```text
-Dmass.sdk.chaos.processingDelayMillis=25
-Dmass.sdk.chaos.assignmentRetryDelayMillis=100
-Dmass.sdk.chaos.leaseWatchdogIntervalSeconds=1
-Dmass.sdk.chaos.forcedLeaseBackdateSeconds=2
-Dmass.sdk.chaos.timeoutSeconds=30
```

Artifact:

- `xa-mass-testing/target/chaos-reports/sdk-websocket-lease-expiry-redispatch-chaos-*.json`

Verified artifact on 2026-04-25:

- `xa-mass-testing/target/chaos-reports/sdk-websocket-lease-expiry-redispatch-chaos-20260425-173651.json`

## What This Probe Proves

- the first dispatch can land on a real WebSocket worker that later drops offline
- the runtime observes that worker offline before any result arrives
- watchdog expiry closes the first concrete `TaskMsgAttempt` as `EXPIRED` with `LEASE_EXPIRED`
- retry budget on the logical message is exercised, so the message resets instead of becoming logically final
- a second worker can take over the retried logical message and complete it successfully
- final convergence lands on `TaskMsg=SUCCESS` and `Task.terminalReason=ALL_MESSAGES_SUCCEEDED`

## Important Scope Note

To keep this probe fast enough for routine acceptance, it forces the first persisted attempt lease timestamp into the past instead of waiting for the default five-minute lease window. The dispatch, disconnect, watchdog scan, redispatch, and result-ingest paths are still real runtime behavior.
