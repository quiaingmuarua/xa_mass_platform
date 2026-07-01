# mass-runtime-api

Status: current shared worker-runtime SPI module.

## Role

- owns the shared `WorkerRegistry` semantic contract plus current worker
  registry primitives used by memory and Redis worker registry implementations
- owns the worker-runtime score-band slot SPI used by memory and Redis
  implementations
- provides a shared low-level worker-runtime boundary for engine, worker
  runtime, server bootstrap, and test harnesses

## What Belongs Here

- worker registry metadata lookup, worker-id admission/gate operations,
  candidate acquisition, and candidate sampling contracts
- current slot/group-scoped registry primitives required by memory and Redis
  implementation parity while worker-runtime callers converge on semantic
  methods

## What Does Not Belong Here

- task lifecycle policy
- task item scheduling/runtime contracts; those belong to
  `../../xa-mass-task-runtime`
- task ready queues, active leases, result finality, progress, and task-runtime
  Redis keyspace
- worker resource, report, candidate, admission, control, or scheduling-evidence
  contracts owned by `xa-mass-worker-runtime`
- worker matching logic
- worker capability truth or scheduling policy
- transport-specific payload/frame protocols
- JDBC or control-plane persistence concerns

## Current Truth

- old `TaskWorkRuntime` / `TaskResultRuntime` APIs and contract tests have been
  removed from this module after the task-runtime serving-lane cutover
- task-runtime public ports now live in `../../xa-mass-task-runtime`
- `mass-runtime-memory` and `mass-runtime-redis` implement the worker SPI in
  this module
- `mass-runtime-memory` provides the JVM `InMemoryWorkerRegistry`
  implementation while the shared worker registry contract lives here
- high-level worker-plane contracts live in
  [../../xa-mass-worker-runtime/CONTRACTS.md](../../xa-mass-worker-runtime/CONTRACTS.md)
