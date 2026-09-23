# Task Initialization

Status: current Java production Task Initialization boundary.

## Purpose

Initialization is the one observable start-condition check between approval
and ordinary RUNNING scheduling:

```text
PRE_REVIEW
-> approve with fixed RUNNING INITIAL coordinate
-> due ACTIVE Item exists
-> exact promotion to RUNNING NORMAL
```

It is not a lifecycle band, admission framework, Worker allocation phase, or
capacity owner.

## Input

The Dispatch Main Scheduler asks the Task Score Owner to filter the INITIAL
subset from one bounded `taskId -> opaque score` scan:

```text
band       = RUNNING_VISIBLE
timeMillis = 10_000
suffix     = 99 - priority
```

The Task Score Owner executes one descending Redis range read from the latest
due score down to zero, limited to 100, then filters INITIAL without another
Redis call. INITIAL does not load a Descriptor. Consequently, 100 due NORMAL
scores can fill the page and defer INITIAL. This is normal
bounded initialization policy under the supported small active-Task model;
INITIAL is not yet part of the NORMAL assignment working set.

## Policy

`TaskInitializationPolicy` is the complete fixed initialization entry:

```text
TaskInitializationPolicy.initialize(initial taskId -> opaque score)
  -> one hasDueActiveItems(all task ids)
  -> retain each ready id with its original opaque score
  -> one promoteObservedInitialTasks(ready exact scores)
```

Batch promotion uses one Redis TIME-derived target and resets every successful
Task to the same ordinary RUNNING coordinate with suffix zero. Its Lua receives
the target and INITIAL range as arguments; it only performs range checks and
exact CAS. Each Task transition is independently `TRANSITIONED`, `STALE`, or
`INVALID`; one stale member does not block the others. Redis discovery retries
unchanged INITIAL Tasks in a later round.

## Concurrency

The opaque Task score is not a lease. Close, pause, or any concurrent
score transition wins through exact CAS. A busy Initialization Producer retains
no memory queue or hint; a future INITIAL scan is the liveness path.

## Non-Goals

- No Task/System admission policy registry.
- No capacity transaction; approve composes a RUNNING count precheck with an
  independent exact INITIAL transition.
- No priority-bucket recheck; priority is already encoded in the suffix of the
  fixed INITIAL slot.
- No Worker lease, Candidate Cache, Serviceability probe, or Item claim.
- No business-profile branching in Kernel policy.
