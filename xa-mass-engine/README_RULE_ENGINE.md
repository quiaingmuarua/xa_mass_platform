# Rule Engine Notes

This document describes the active rule-matching surface in `xa-mass-engine`.

## Purpose

The engine no longer hard-codes task-to-worker matching around a single worker-country assumption.
The current mainline evaluates `WorkerMatchContext` through QLExpress rules and treats routing country as a task input that should normally be satisfied by worker-context/account-facing signals.

## Active Components

### `WorkerMatchContext`

Location: `com.xa.mass.engine.model.WorkerMatchContext`

Responsibilities:

- Build a stable rule-evaluation context for one `worker + workerContext + task` candidate
- Expose strong-typed mainline fields and auxiliary attribute maps together
- Keep routing-country diagnostics explicit instead of hiding them in ad hoc worker filters

### `RuleConfig`

Location: `com.xa.mass.engine.rules.RuleConfig`

Responsibilities:

- Provide default, advanced, project-specific, and loose matching rules
- Keep the default routing-country rule aligned with current semantics

## Default Rules

Current default rule set:

1. `basic_worker_check`

```ql
isWorkerAvailable == true && isWorkerLocked == false
```

2. `worker_context_status_check`

```ql
isWorkerContextAllocatable == true && isWorkerContextAvailable == true
```

3. `routing_country_match`

```ql
workerContextAttributeCountryMatchesRoutingCountry == true || workerContextChannelMatchesRoutingCountry == true
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

### Worker

- `workerId`
- `workerStatus`
- `workerGroupId`
- `workerAttributes`
- `agentVersion`
- `supportedProjects`
- `isWorkerAvailable`
- `isWorkerLocked`

### WorkerContext

- `workerContextId`
- `workerContextStatus`
- `workerContextChannel`
- `workerContextAttributes`
- `isWorkerContextAllocatable`
- `isWorkerContextAvailable`

### Task

- `taskId`
- `taskName`
- `taskProject`
- `taskRoutingCountryCode`
- `taskStatus`
- `taskTargetNumber`
- `batchSize`
- `minRequiredWorkerCount`

### Derived Signals

- `appCount`
- `supportsProject`
- `workerGroupIdEqualsRoutingCountry`
- `workerContextChannelMatchesRoutingCountry`
- `workerContextAttributeCountryMatchesRoutingCountry`

## Example Rules

Worker-context attribute routing:

```ql
workerContextAttributes['country'] == taskRoutingCountryCode
```

Fallback to worker-context channel:

```ql
workerContextChannelMatchesRoutingCountry == true
```

Project-specific example:

```ql
supportsProject == true &&
appCount <= 5 &&
agentVersion.startsWith('1.0') &&
(workerContextAttributeCountryMatchesRoutingCountry == true || workerContextChannelMatchesRoutingCountry == true)
```

## Boundaries

- `workerAttributes` and `workerContextAttributes` are auxiliary labels only
- Lifecycle, lock, and online truth must continue to come from strong-typed fields and managers
- `taskRoutingCountryCode` is a routing hint, not a claim that the task itself owns country truth
- Worker `workerGroupId` can still appear in diagnostics, but it is no longer the mainline country truth source

## Guidance

- Prefer explicit context keys over flattened aliases
- Prefer end-to-end tests for routing behavior over isolated expression-only confidence
- If matching semantics change, update `RuleConfig`, `WorkerMatchContext`, and the mock E2E routing coverage together
