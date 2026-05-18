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

- `TaskWorkRuntime` stats
- active lease drain
- SDK `readTaskResults(...)` sequential windows
- worker receive/result metrics
- transport delivery diagnostics
- `JsonlExecutionEventSink` output
- `TraceOperatorService.validate(...)`
- `TraceOperatorService.stats(...)`

Compatibility projection is not a pass/fail truth for soak.

## Defaults

The default runner profile is intentionally small enough for local manual use:

```text
mass.soak.durationSeconds=120
mass.soak.workerCount=16
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

- `tasksSubmitted == tasksTerminal`
- `workItemsSubmitted == resultsVisible`
- every terminal task has strictly increasing result `seq`
- every result page has `nextAfterSeq == last item seq`
- result windows have no duplicate `messageId`
- final result page has `hasMore=false`
- `activeLeasesAtEnd == 0`
- trace validation passes
- trace dropped count is `0`

## Future Extensions

Keep each extension as a separate lane/profile:

- Redis-backed runtime/result soak
- WebSocket/socket transport soak
- disconnect/reconnect churn during soak
- lease-expiry and stale result replay during soak
- archive streaming consumption under high visible-result counts
- 30m / 2h / overnight scheduled profiles
