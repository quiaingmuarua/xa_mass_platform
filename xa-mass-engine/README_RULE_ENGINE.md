# Rule Engine Notes

This file records the active rule-matching surface in `xa-mass-engine`.

## Current Role

Matching evaluates `WorkerMatchContext` through QLExpress rules.

Current owner types:

- `com.xa.mass.engine.model.WorkerMatchContext`
- `com.xa.mass.engine.rules.RuleConfig`

Responsibilities:

- build one stable rule-evaluation context for one `worker + workerContext + task` candidate
- expose typed fields plus auxiliary attribute maps
- provide the default and project-specific rule set used by engine matching

## Default Rules

Current default rule set:

1. `basic_worker_check`

```ql
isWorkerAvailable == true && isWorkerLocked == false
```

2. `worker_context_status_check`

```ql
hasWorkerContext == false || isWorkerContextAllocatable == true
```

3. `routing_code_match`

```ql
taskHasRoutingRequirement == false || workerContextMatchesRoutingCode == true
```

4. `app_support_check`

```ql
supportsProject == true
```

5. `worker_load_check`

```ql
appCount < 10
```

## Context Keys

Worker keys:

- `workerId`
- `workerStatus`
- `workerGroupId`
- `workerAttributes`
- `agentVersion`
- `supportedProjects`
- `isWorkerAvailable`
- `isWorkerLocked`

Worker-context keys:

- `hasWorkerContext`
- `workerContextId`
- `workerContextStatus`
- `workerContextChannel`
- `workerContextAttributes`
- `isWorkerContextAllocatable`
- `isWorkerContextAvailable`
- `isWorkerContextUsable`
- `isWorkerContextReserved`
- `isWorkerContextOccupied`

Task keys:

- `taskId`
- `taskName`
- `taskProject`
- `taskSharedConfig`
- `routingCode`
- `taskHasRoutingRequirement`
- `taskStatus`
- `taskTargetNumber`
- `batchSize`
- `minRequiredWorkerCount`

Derived signals:

- `appCount`
- `supportsProject`
- `workerContextProjectMatchesTaskProject`
- `workerContextMatchesRoutingCode`

## Example Rules

Worker-context attribute routing:

```ql
workerContextAttributes['country'] == routingCode
```

Match the task routing hint against worker-context routing tags:

```ql
workerContextMatchesRoutingCode == true
```

Allow a stateless worker when the task has no routing requirement:

```ql
hasWorkerContext == false || isWorkerContextAllocatable == true
```

Project-specific example:

```ql
supportsProject == true &&
appCount <= 5 &&
agentVersion.startsWith('1.0') &&
(taskHasRoutingRequirement == false || workerContextMatchesRoutingCode == true)
```

## Boundaries

- `workerAttributes` and `workerContextAttributes` are auxiliary labels only
- lifecycle, lock, and online truth come from typed fields and managers
- `routingCode` is an optional convention resolved from `Task.sharedConfig["routingCode"]`
- `WorkerContext` is optional at platform level
- once a task declares routing requirements, a missing worker context does not satisfy that rule

## Change Rule

- prefer explicit context keys over flattened aliases
- prefer end-to-end routing tests over expression-only confidence
- if matching semantics change, update `RuleConfig`, `WorkerMatchContext`, and the mock E2E routing coverage together
