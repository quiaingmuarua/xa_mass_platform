# DB Storage Principles

Status: active boundary for XA Mass control-plane persistence.

This document exists to prevent repeated storage refactors. Treat it as the
default policy whenever someone proposes a new table, a new hot-path write, or
an early PostgreSQL requirement.

## Core Rule

The database is for **recoverable control-plane truth**, not for runtime event
streams.

A field or table belongs in DB only if at least one of these is true:

- the runtime cannot recover correct behavior after restart without it
- the value is stable control-plane configuration or registration truth
- the value is a bounded aggregate needed for operator-facing task truth

If queue replay, logs, trace, or runtime projection can recover it, do not put
it in the control-plane DB by default.

## Three Truth Layers

Treat storage decisions through these three layers:

1. control-plane storage truth
   - durable `Task` shell truth
   - durable worker / worker-context registration truth
   - durable rule / principal / submitter truth
2. runtime hot-path truth
   - ready queue membership
   - delayed visibility / retry timing
   - lease ownership and expiry
   - worker lock / occupancy churn
   - runtime counters and backpressure state
3. async audit / trace truth
   - high-volume task-message detail
   - attempt history and callback timelines
   - downstream analytics and failure analysis

The control-plane DB owns only the first layer.

The runtime layer belongs in queue/lease/counter modules such as
`TaskWorkRuntime`, whether the implementation is memory or Redis.

The audit/trace layer should be exported asynchronously. It must not redefine
the control-plane DB into a hot-path event store.

## What Belongs In DB

Current default scope:

- `Task` main truth
  - task identity
  - project
  - task source/workload intent carried by task truth
  - eventCode and shared config carried by task truth
  - task status and terminal reason
  - bounded task aggregates already stored on the task model
- worker registration truth
  - worker identity
  - supported projects and supported event codes
  - adapter / transport hint
  - static worker attributes
- worker-context registration truth
  - worker-context identity
  - owning worker identity
  - project/routing tags
  - static attributes and expiry configuration
- rule definitions
- principal credential truth
  - principal identity and type
  - credential hash / key prefix
  - direct permissions, project scopes, and event scopes
  - stable principal attributes used for binding checks
- optional bounded task-level read models
  - lagging summary counters or summary snapshots are acceptable only when they
    stay task-level and are not treated as queue/lease truth

This is the intended long-term role for PostgreSQL as well.

## What Does Not Belong In DB

Do not use the control-plane DB for:

- `TaskMsg` hot-path persistence
- `TaskMsgAttempt` hot-path persistence
- ready queue membership
- delayed/retry scheduling indexes
- active lease ownership or lease-token truth
- lane dispatch state
- backpressure state
- inflight retry-budget consumption
- heartbeat streams
- worker online/offline churn
- worker lock churn
- worker-context occupancy churn
- poll / dispatch / callback event streams
- manual debug/control side-channel history
- cross-task failure analytics
- large-scale message history or attempt timelines

These belong in some combination of:

- queue replay
- in-memory runtime projection
- runtime queue/lease implementations
- logs
- trace / audit sinks
- metrics

## Current JDBC Boundary

The active `platform_infra/mass-storage-jdbc` JDBC path is intentionally narrow:

- JDBC persists task truth
- JDBC persists worker/worker-context registration truth
- JDBC persists rule definitions
- JDBC persists principal credential truth
- runtime message/attempt detail stays process-local
- runtime worker online/lock/context occupancy state stays process-local
- startup cleanup may repair runtime residue, but it does not make JDBC the
  owner of queue, lease, or inflight execution truth

This is true for both `jdbc-h2` and future `jdbc-postgres`.

## H2 And PostgreSQL Policy

- H2 is a local/server verification dialect
- PostgreSQL is the intended durable control-plane dialect
- neither H2 nor PostgreSQL should be used as a substitute for runtime queues,
  trace, or analytics

Do not justify a schema addition with "we will need it for PostgreSQL later".
First prove it is part of recoverable control-plane truth.

## Before Adding A Table Or Hot Write

Answer these questions first:

1. Is this value required after process restart to restore correct behavior?
2. Is this stable control-plane truth rather than a runtime event stream?
3. Can queue replay, logs, or trace recover this instead?
4. Will this become a high-frequency write path under crawler/bulk workloads?
5. If PostgreSQL were unavailable, would the runtime still function from queue
   and in-memory projection alone?

If the answer to 3 is yes, or the answer to 4 is yes, default to **not using
DB**.

## Non-Goals

Not in scope for the control-plane DB:

- durable transport queues
- event bus history
- analytical storage
- message warehouse / big-data pipelines

Those may exist later, but they are separate systems and must not redefine the
control-plane DB boundary.
