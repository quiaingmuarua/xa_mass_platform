# Match Throughput Note

Status: instrumentation baseline; no throughput-changing implementation yet.

Scope:

- Worker match assignment lane snapshots.
- Worker availability retry wakeup behavior.
- Stage-1 bounded candidate acquisition and Stage-2 rule/rank/reserve path.

Current instrumentation:

- `TaskAssignWorker` emits `ASSIGNMENT_QUEUE_SNAPSHOT` with lane, queue depth,
  scheduled retry count, retry delay, and assignment duration.
- `AssignmentRecord` captures rule evaluation count and total rule evaluation
  time for worker assignment attempts.
- `WorkerCandidateIndex` performs bounded source-bucket acquisition before
  Stage-2 and source-guard validation before worker materialization.

Local validation record:

- Date: 2026-05-27.
- Command:

```bash
mvn -q -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-redis,xa-mass-engine,xa-mass-worker-runtime -am '-Dtest=WorkerRegistryContractTest,InMemoryWorkerRegistryTest,RedisWorkerRegistryTest,WorkerCandidateIndexTest,WorkerManagerTest,DefaultWorkerCandidateRankerTest,RuleBasedTaskWorkerMatchingStrategyTest,TaskWorkerAssignListenerTest,TaskAssignWorkerTest,TaskDispatchWakeupBridgeTest,EngineSchedulingCoreArchitectureGuardTest' test
```

- Result: passed.
- What this proves: instrumentation and correctness gates compile and execute
  together.
- What this does not prove: this is not a load benchmark and does not establish
  a measured lane-throughput ceiling.

Decision:

- Do not add lane parallelism yet. The current bottleneck evidence is now
  observable, but the first priority is preserving `WorkerRegistry`
  reserve/confirm/release correctness and avoiding duplicate dispatch.
- Future throughput work should compare lane assignment duration, retry count,
  queue depth, runtime-ready pump wakeups, and reserve rejection reasons before
  changing concurrency.

Next measurement record must include:

- workload size and worker count
- lane queue depth range
- assignment duration p50 / p95 / max
- retry count by reason
- runtime-ready pump wakeup count
- reserve rejection reasons
- bottleneck decision and whether lane parallelism remains deferred
