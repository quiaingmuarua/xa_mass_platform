# mass-runtime-api

Status: current shared runtime contract module.

## Role

- owns the `TaskWorkRuntime` abstraction
- owns queue/lease/result/counter value types used by runtime hot paths
- provides a shared boundary for engine, transport runtime, server bootstrap, and test harnesses

## What Belongs Here

- ready-work enqueue and claim contracts
- active lease truth
- runtime result-apply outcomes
- runtime counters and bounded runtime stats

## What Does Not Belong Here

- task lifecycle policy
- worker matching logic
- transport-specific payload/frame protocols
- JDBC or control-plane persistence concerns

## Current Phase-1 Truth

- this module was extracted conservatively from `xa-mass-engine`
- engine consumes this API directly and now requires runtime injection at `TaskManager` construction time
- sdk/server bootstrap currently provide the default in-memory runtime implementation
- this module is the correct place for future Redis runtime implementations to target
