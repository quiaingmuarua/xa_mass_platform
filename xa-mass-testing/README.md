# xa-mass-testing

Status: current testing owner README.

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
| `perf smoke bundle` | `scripts/run-perf-smokes.sh` | current workspace perf smoke fast path for workload mix plus delayed interactive retry wakeup | `target/perf-reports/` |
| `perf smoke: workload mix` | `com.xa.mass.testing.perf.TaskWorkloadMixSmokeRunner` | interactive assignment latency under bulk background pressure; lane split smoke | `target/perf-reports/` |
| `perf smoke: interactive retry wakeup` | `com.xa.mass.testing.perf.TaskInteractiveRetryWakeupSmokeRunner` | delayed interactive retry visibility and redispatch observability under bulk background pressure; report shows whether retry overlapped active bulk | `target/perf-reports/` |
| `SDK transport harness` | `scripts/run-sdk-transport-load.sh` | embedded runtime composition across polling / websocket / socket | `target/concurrency-reports/` |
| `chaos: websocket disconnect` | `com.xa.mass.testing.chaos.SdkWebSocketDisconnectChaosRunner` | disconnect, reconnect, delayed result after reconnect | `target/chaos-reports/` |
| `chaos: lease-expiry redispatch` | `com.xa.mass.testing.chaos.SdkWebSocketLeaseExpiryRedispatchChaosRunner` | disconnect without result, watchdog expiry, retry reset, takeover by another worker | `target/chaos-reports/` |
| `chaos: late stale result after lease expiry` | `com.xa.mass.testing.chaos.SdkWebSocketLateResultAfterLeaseExpiryChaosRunner` | original worker disconnects, lease expires, takeover succeeds, then the stale worker reconnects and replays a late result that must not overwrite the final message/task state | `target/chaos-reports/` |
| `chaos: polling lease-expiry redispatch` | `com.xa.mass.testing.chaos.SdkPollingLeaseExpiryRedispatchChaosRunner` | polling worker claims work, stalls without a result, goes offline, watchdog expiry resets the logical message, and another polling worker takes over to finish successfully | `target/chaos-reports/` |
| `chaos: polling all messages failed` | `com.xa.mass.testing.chaos.SdkPollingAllMessagesFailedChaosRunner` | polling worker always submits failure with no retries; all messages converge to FAILED and the task closes with ALL_MESSAGES_FAILED | `target/chaos-reports/` |
| `chaos: polling mixed results` | `com.xa.mass.testing.chaos.SdkPollingMixedResultsChaosRunner` | multi-message task where some messages succeed and some fail (driven by per-message `shouldFail` input flag); task closes with MIXED_MESSAGE_RESULTS | `target/chaos-reports/` |
| `chaos: polling message retry exhausted` ★ | `com.xa.mass.testing.chaos.SdkPollingMessageRetryExhaustedChaosRunner` | polling worker always fails; each message has `maxRetryCount=2` and burns 3 total attempts before `RETRY_EXHAUSTED` finalization; task closes with ALL_MESSAGES_FAILED; `TASK_WORK_RETRY_RESET` events verified in trace | `target/chaos-reports/` |
| `chaos smoke bundle (CI gate)` ★ | `scripts/run-chaos-smokes.sh` | fast CI gate running the three ★-marked probes; exits non-zero if any probe fails; wired into `.github/workflows/maven.yml` `chaos-smokes` job | `target/chaos-reports/` |

## Commands

When a runner depends on current `xa-mass-engine` / `xa-mass-sdk` changes, refresh sibling artifacts first:

```bash
./mvnw -pl xa-mass-testing -am -DskipTests install
```

Fastest current-workspace perf smoke bundle:

```bash
xa-mass-testing/scripts/run-perf-smokes.sh
```

This script first refreshes sibling module artifacts with `-pl xa-mass-testing -am -Dmaven.test.skip=true install`, then runs the smoke mains through a direct runtime classpath. Use it when `xa-mass-engine` changed in the current workspace and you want one reliable perf-smoke entrypoint without being blocked by unrelated test-compilation drift in sibling modules.

For the interactive retry wakeup smoke inside the bundle, the script also pins a more stable engine retry-delay JVM property by default:

- `xa.mass.engine.interactiveWorkRetryDelayMillis=200`
- `mass.retrywakeup.smoke.minRetryDispatchDelayMillis=80`

Override them with environment variables:

- `XA_MASS_INTERACTIVE_RETRY_DELAY_MILLIS`
- `MASS_RETRYWAKEUP_SMOKE_MIN_DELAY_MILLIS`

Perf load model:

```bash
./mvnw -pl xa-mass-testing -am -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskFlowLoadModelRunner -Dmass.load.workloadClass=INTERACTIVE
```

Mixed workload perf smoke:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskWorkloadMixSmokeRunner
```

Interactive retry wakeup perf smoke:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.perf.TaskInteractiveRetryWakeupSmokeRunner
```

SDK transport harness:

```bash
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=polling -Dmass.sdk.load.workloadClass=BULK
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=websocket -Dmass.sdk.load.workloadClass=INTERACTIVE
xa-mass-testing/scripts/run-sdk-transport-load.sh -Dmass.sdk.load.transport=socket -Dmass.sdk.load.workloadClass=BULK
```

WebSocket disconnect chaos:

```bash
./mvnw -pl xa-mass-testing -am -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkWebSocketDisconnectChaosRunner
```

Lease-expiry redispatch chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.chaos.taskMessageLeaseSeconds=2 -Dmass.sdk.chaos.timeoutSeconds=30 compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkWebSocketLeaseExpiryRedispatchChaosRunner
```

Late stale result after lease expiry chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.chaos.taskMessageLeaseSeconds=2 -Dmass.sdk.chaos.timeoutSeconds=30 compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkWebSocketLateResultAfterLeaseExpiryChaosRunner
```

All messages failed chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkPollingAllMessagesFailedChaosRunner
```

Mixed results chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkPollingMixedResultsChaosRunner
```

Polling lease-expiry redispatch chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true -Dmass.sdk.chaos.taskMessageLeaseSeconds=2 -Dmass.sdk.chaos.timeoutSeconds=30 compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkPollingLeaseExpiryRedispatchChaosRunner
```

Per-message retry exhaustion chaos:

```bash
cd xa-mass-testing
..\mvnw.cmd -Dexec.classpathScope=compile -Dmaven.test.skip=true compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.xa.mass.testing.chaos.SdkPollingMessageRetryExhaustedChaosRunner
```

Chaos smoke bundle (all three CI-gated probes):

```bash
xa-mass-testing/scripts/run-chaos-smokes.sh
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

## Current Chaos Focus

Current chaos probes cover seven distinct scenario branches:

- disconnect, reconnect, then submit the delayed result on the original worker
- disconnect without a result, let lease expiry trigger redispatch, and finish on a different worker
- disconnect without a result, let lease expiry trigger redispatch and terminal success, then reconnect the original worker and replay a stale late result that must not mutate the already-final logical message
- polling worker claims work, stalls without a result, goes offline, and a second polling worker takes over after lease expiry
- all messages fail with no retries; task closes with `ALL_MESSAGES_FAILED`
- multi-message task with partial success and partial failure; task closes with `MIXED_MESSAGE_RESULTS`
- per-message retry budget exhaustion (`maxRetryCount=2`, 3 total attempts per message); messages finalize with `RETRY_EXHAUSTED`; task closes with `ALL_MESSAGES_FAILED`; `TASK_WORK_RETRY_RESET` events verified in trace

The three polling probes (all-messages-failed, mixed-results, retry-exhausted) are wired to the `chaos-smokes` CI job and gate every PR. All seven probes capture `ExecutionEvent` objects via `CapturingExecutionEventSink` and write a `trace.byType` summary in the report JSON.

These probes stay at the SDK embedded-runtime layer. Matching Boot-shell HTTP behavior should be verified separately under `xa-mass-server` E2E.
