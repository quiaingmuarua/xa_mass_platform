# DB Storage Principles

Status: superseded. Current DB/control-plane placement rules are merged into
`doc/INFRA_TRUTH_LAYERS.md`.

This document exists to prevent repeated storage refactors. Treat it as the
default policy whenever someone proposes a new table, a new hot-path write, or
a PostgreSQL-specific requirement.

For the dense cross-layer placement matrix, read
[INFRA_TRUTH_LAYERS.md](./INFRA_TRUTH_LAYERS.md) first.

## Core Rule

The database is for **recoverable control-plane truth**, not for runtime event
streams.

A field or table belongs in DB only if at least one of these is true:

- the runtime cannot recover correct behavior after restart without it
- the value is stable control-plane configuration or registration truth
- the value is a bounded aggregate needed for operator-facing task truth

If queue replay, logs, trace, or runtime projection can recover it, do not put
it in the control-plane DB by default.

Practical translation:

- DB owns stable control-plane truth
- runtime owns queue/lease/counter churn
- trace/audit owns high-volume history and analytics

Do not use "there is nowhere else to put it" as justification for pushing
trace-shaped history into JDBC truth.

## What Belongs In DB

Current default scope:

- `Task` main truth
  - task identity
  - project
  - task source/workload intent carried by task truth
  - shared config carried by task truth
  - task status and terminal reason
  - bounded task aggregates already stored on the task model
- worker declaration truth
  - worker identity
  - explicit worker group / adapter-node membership
  - adapter / transport hint
  - static worker attributes
- worker group capability truth
  - supported projects
  - supported event codes
  - group-level capability metadata used to build candidate-source views
- rule definitions
- principal credential truth
  - principal identity and type
  - credential hash / key prefix
  - direct permissions, project scopes, and event scopes
  - stable principal attributes used for binding checks
- optional bounded task-level read models
  - lagging summary counters or summary snapshots are acceptable only when they
    stay task-level and are not treated as queue/lease truth

PostgreSQL follows the same control-plane boundary.

## What Does Not Belong In DB

Do not use the control-plane DB for:

- message-projection hot-path persistence
- attempt-projection hot-path persistence
- ready queue membership
- delayed/retry scheduling indexes
- active lease ownership or lease-token truth
- lane dispatch state
- backpressure state
- inflight retry-budget consumption
- heartbeat streams
- worker online/offline churn
- worker lock churn
- worker capacity/reservation churn or any replacement runtime resource churn
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

If the intended long-term home is trace / audit, prefer the smallest temporary
runtime/logging residue that preserves correctness. Do not make the temporary
placement authoritative.

## Current JDBC Boundary

The active `platform_infra/mass-storage-jdbc` JDBC path is intentionally narrow:

- JDBC persists task truth
- JDBC does not persist a worker declaration table in the current path; do not
  add worker runtime/history/churn tables under that name
- JDBC persists rule definitions
- JDBC persists principal credential truth
- runtime message/attempt detail stays process-local
- runtime worker online/lock/capacity/reservation state stays process-local
- server review/export materialization uses server-owned backing stores and
  must not be wired back into engine runtime assembly
- startup cleanup may repair runtime residue, but it does not make JDBC the
  owner of queue, lease, or inflight execution truth

This boundary is identical for `jdbc-h2` and `jdbc-postgres`.

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

## Dialect Rule

- H2 is a local/server verification dialect
- PostgreSQL is the intended durable control-plane dialect
- neither changes the DB boundary

Do not justify a schema addition with "we will need it for PostgreSQL later".
First prove it is recoverable control-plane truth.

## Non-Goals

Not in scope for the control-plane DB:

- durable transport queues
- event bus history
- analytical storage
- message warehouse / big-data pipelines

If added as separate systems, they must not redefine the control-plane DB
boundary.
