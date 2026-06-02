# mass-runtime-api

Status: current shared runtime contract module.

## Role

- owns the `TaskWorkRuntime` abstraction
- owns queue/lease/result/counter value types used by runtime hot paths
- owns the shared `WorkerRegistry` / `WorkerSlot` contract plus worker
  registry primitives used by memory and Redis worker registry implementations
- provides a shared boundary for engine, transport runtime, server bootstrap, and test harnesses

## What Belongs Here

- ready-work enqueue and claim contracts
- active lease truth
- runtime result-apply outcomes
- runtime counters and bounded runtime stats
- worker registry slot, reserve, gate-source, route-bucket SPI, and candidate
  sampling contracts

## What Does Not Belong Here

- task lifecycle policy
- worker resource, report, candidate, admission, control, or scheduling-evidence
  contracts owned by `xa-mass-worker-runtime`
- worker matching logic
- worker capability truth or scheduling policy
- transport-specific payload/frame protocols
- JDBC or control-plane persistence concerns

## Current Truth

- this module was extracted conservatively from `xa-mass-engine`
- engine consumes this API directly and now requires runtime injection at `TaskManager` construction time
- sdk/server bootstrap currently provide the default in-memory runtime implementation
- `mass-runtime-memory` and `mass-runtime-redis` both implement this contract;
  the embedded default path remains in-memory, while compose/local distributed
  verification uses Redis
- `mass-runtime-memory` provides the JVM `InMemoryWorkerRegistry`
  implementation while the shared worker registry contract lives here
- high-level worker-plane contracts live in
  [../../xa-mass-worker-runtime/CONTRACTS.md](../../xa-mass-worker-runtime/CONTRACTS.md)
