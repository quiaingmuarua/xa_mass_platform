# Match Throughput Note

Status: first measurement baseline.

Scope:

- Worker match assignment lane snapshots.
- Worker availability retry wakeup behavior.
- Stage-1 bounded candidate acquisition and Stage-2 rule/rank/reserve path.

Current evidence:

- `TaskAssignWorker` emits `ASSIGNMENT_QUEUE_SNAPSHOT` with lane, queue depth,
  scheduled retry count, retry delay, and assignment duration.
- `AssignmentRecord` captures rule evaluation count and total rule evaluation
  time for worker assignment attempts.
- `WorkerCandidateIndex` performs bounded source-bucket acquisition before
  Stage-2 and source-guard validation before worker materialization.

Decision:

- Do not add lane parallelism yet. The current bottleneck evidence is now
  observable, but the first priority is preserving `WorkerRegistry`
  reserve/confirm/release correctness and avoiding duplicate dispatch.
- Future throughput work should compare lane assignment duration, retry count,
  queue depth, runtime-ready pump wakeups, and reserve rejection reasons before
  changing concurrency.

Validation:

```bash
./mvnw -q -pl platform_infra/mass-runtime-api,platform_infra/mass-runtime-redis,xa-mass-engine,xa-mass-sdk -am -Dtest=WorkerRegistryContractTest,InMemoryWorkerRegistryTest,RedisWorkerRegistryTest,WorkerCandidateIndexTest,DefaultWorkerCandidateRankerTest,RuleBasedTaskWorkerMatchingStrategyTest,TaskAssignWorkerTest,TaskDispatchWakeupBridgeTest,EngineSchedulingCoreArchitectureGuardTest -Dsurefire.failIfNoSpecifiedTests=false test
```
