# mass-runtime-memory

Status: current in-memory worker-runtime SPI implementation module.

## Role

- provides the in-memory `WorkerRegistry` slot/index/admission implementation used by the current embedded default path
- hosts focused worker registry contract tests for candidate buckets, heartbeat cleanup, reserve, dispatch gates, and occupancy

## Current Truth

- old `InMemoryTaskWorkRuntime` / `InMemoryTaskResultRuntime` implementations
  have been removed after the task-runtime serving-lane cutover
- task-runtime memory implementation now lives in
  `../mass-task-runtime-memory`
- `InMemoryWorkerRegistry` is the current embedded default worker runtime implementation
- sdk `EngineConfig`, server bootstrap, and explicit tests currently construct
  this implementation for worker-runtime SPI where memory is selected
- this module is an implementation module; engine policy and task lifecycle ownership remain outside this module

## Boundary

- keep memory worker-runtime behavior aligned with the shared contract in `mass-runtime-api`
- do not grow business policy or transport protocol logic here
- `mass-runtime-redis` is the sibling Redis-backed worker-runtime
  implementation module; keep both aligned to the shared worker SPI contract
