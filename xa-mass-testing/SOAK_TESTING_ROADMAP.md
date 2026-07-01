# Soak Testing Roadmap

Status: current module-local soak roadmap.

`xa-mass-testing` owns soak testing as a manual or scheduled distributed-readiness lane. Soak does not replace engine scheduling correctness tests, server Boot-shell E2E, or transport chaos probes.

## Summary

The first soak lane is polling-worker scheduling pressure:

```text
task/item submit
  -> eventCode/group worker matching
  -> polling worker pull
  -> result submit
  -> runtime visible result rows
  -> terminal convergence
  -> trace validate/stats
```

The goal is to catch long-running hidden drift:

- active leases not draining
- visible result rows missing
- result `afterSeq` checkpoint regressions
- duplicate result message ids
- success/failed work counters drifting from the configured failure profile
- task terminal reasons drifting under all-success, mixed-result, or all-failed profiles
- late worker join not helping pending polling work converge
- runtime counters and worker metrics diverging
- trace sink drops or invalid canonical trace rows

## Current Lane

Runner:

```text
com.xa.mass.testing.soak.SdkPollingSchedulingSoakRunner
```

Artifacts:

```text
target/soak-reports/<runId>.json
target/soak-traces/<runId>/
```

Proof surface:

- task-runtime stats
- active lease drain
- SDK `readTaskResults(...)` sequential windows
- worker receive/result metrics
- worker lifecycle profile, including initial workers and late-join workers
- expected success/failed counts from `failureEveryNth`
- expected task terminal reason distribution
- transport delivery diagnostics
- structured `proof` bundle
- `JsonlExecutionEventSink` output
- `TraceOperatorService.validate(...)`
- `TraceOperatorService.stats(...)`

Compatibility projection is not a pass/fail truth for soak.

## Defaults

The default runner profile is intentionally small enough for local manual use:

```text
mass.soak.durationSeconds=120
mass.soak.workerCount=16
mass.soak.initialWorkerCount=16
mass.soak.lateWorkerStartAfterMillis=0
mass.soak.requireLateWorkerWork=false
mass.soak.groupCount=2
mass.soak.eventCodeCount=2
mass.soak.submitRatePerSecond=20
mass.soak.messagesPerTask=8
mass.soak.pollBatchSize=4
mass.soak.emptyPollBackoffMillis=20
mass.soak.processingDelayMillis=5
mass.soak.processingJitterMillis=0
mass.soak.failureEveryNth=0
mass.soak.drainTimeoutSeconds=60
mass.soak.trace=true
mass.soak.traceQueueCapacity=65536
mass.soak.traceRotateAfterLines=100000
mass.soak.forceExit=true
```

## Acceptance

A successful polling soak requires:

- `proof.runtimeInvariants.ok == true`
- `tasksSubmitted == tasksTerminal`
- every task terminal reason matches the configured success/failure profile
- `workItemsSubmitted == resultsVisible`
- runtime success/failed counts match the configured success/failure profile
- if `requireLateWorkerWork=true`, late-join workers receive work and submit results
- every terminal task has strictly increasing result `seq`
- every result page has `nextAfterSeq == last item seq`
- result windows have no duplicate `messageId`
- final result page has `hasMore=false`
- `activeLeasesAtEnd == 0`
- trace validation passes
- trace dropped count is `0`

The report `proof` bundle is the stable diagnostic entry point. It contains:

- `runtimeInvariants`
- `resultSequentialRead`
- `workerMetrics`
- `workerLifecycle`
- `deliveryDiagnostics`
- `trace`
- `failureSamples`

`proof.runtimeInvariants` is the structured pass/fail proof for the acceptance
checks. It must report stable issue codes instead of only throwing assertion
text, so manual and scheduled soak runs can identify whether a failure came
from runtime truth, result visibility, trace evidence, late-worker
participation, or worker execution failures.

When `mass.soak.requireLateWorkerWork=true` and trace is enabled, the runner
records the first actual task seen by a late worker in
`proof.workerLifecycle.lateWorkerProofTaskId` plus
`proof.workerLifecycle.lateWorkerProofWorkerId`, then runs the
`late-worker-backfill` trace analyzer and writes the result under
`proof.trace.analyses`. Analyzer failure is reported through
`TRACE_ANALYSIS_FAILED` in `proof.runtimeInvariants`.

## Future Extensions

Keep each extension as a separate lane/profile:

- scheduled mixed-result profile with `mass.soak.failureEveryNth > 1`
- scheduled all-failed profile with `mass.soak.failureEveryNth=1`
- scheduled late-worker-join profile with `initialWorkerCount < workerCount`
- Redis-backed runtime/result soak
- WebSocket/socket transport soak
- disconnect/reconnect churn during soak
- lease-expiry and stale result replay during soak
- archive streaming consumption under high visible-result counts
- 30m / 2h / overnight scheduled profiles
