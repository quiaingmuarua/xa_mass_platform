# xa-mass-testing

`xa-mass-testing` owns perf, SDK embedded-runtime harnesses, and chaos probes.

Use this module when the question is:

- did a hot path get slower
- did SDK transport composition drift
- does the runtime recover under disconnect or lease-expiry churn

Do not use it as a replacement for Boot-shell E2E when the change also touches
HTTP/API shell behavior or Spring wiring.

## Runner Map

| Surface | Main runner / entry | Primary risk | Artifact |
| --- | --- | --- | --- |
| `perf` | `com.xa.mass.testing.perf.TaskFlowLoadModelRunner` | callback cost, progress recompute, release cost, storage scan pressure | `target/perf-reports/` |
| `SDK transport harness` | `scripts/run-sdk-transport-load.sh` | embedded runtime composition across polling / websocket / socket | `target/concurrency-reports/` |
| `chaos: websocket disconnect` | `com.xa.mass.testing.chaos.SdkWebSocketDisconnectChaosRunner` | disconnect, reconnect, delayed result after reconnect | `target/chaos-reports/` |
| `chaos: lease-expiry redispatch` | `com.xa.mass.testing.chaos.SdkWebSocketLeaseExpiryRedispatchChaosRunner` | disconnect without result, watchdog expiry, retry reset, takeover by another worker | `target/chaos-reports/` |

## Commands

Perf load model:

```bash
./mvnw -pl xa-mass-testing -am -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskFlowLoadModelRunner -Dmass.load.workloadClass=INTERACTIVE
```

SDK transport harness:

```bash
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=polling -Dmass.sdk.load.workloadClass=BULK
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=websocket -Dmass.sdk.load.workloadClass=INTERACTIVE
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=socket -Dmass.sdk.load.workloadClass=BULK
```

WebSocket disconnect chaos:

```bash
./mvnw -pl xa-mass-testing -am -Dexec.classpathScope=compile -Dmaven.test.skip=true org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkWebSocketDisconnectChaosRunner
```

Lease-expiry redispatch chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.chaos.taskMessageLeaseSeconds=2 -Dmass.sdk.chaos.timeoutSeconds=30 org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkWebSocketLeaseExpiryRedispatchChaosRunner
```

## Reading Rule

Start from the runner class, then read the matching report artifact.

Look at these first:

- perf: `wallClock.totalMillis`, release cost, storage probe counts
- SDK transport harness: `runtime.transport`, `tasks.terminalReasons`, `workerMetrics`
- chaos: worker online/offline transitions, attempt/lease outcome, final terminal reason

Use repo-level docs only for system-level policy:

- [../doc/TESTING_BASELINE.md](../doc/TESTING_BASELINE.md)
- [../doc/VERIFIED_RUNBOOK.md](../doc/VERIFIED_RUNBOOK.md)
- [../transport/AGENTS.md](../transport/AGENTS.md)
