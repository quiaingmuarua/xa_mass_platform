# mass-runtime-memory

Status: current in-memory runtime implementation module.

## Role

- provides the in-memory `TaskWorkRuntime` implementation used by the current embedded default path
- hosts focused runtime tests for queue/lease/retry/backpressure semantics

## Current Truth

- `InMemoryTaskWorkRuntime` is the current default verified embedded runtime implementation
- sdk `EngineConfig`, server bootstrap, and explicit tests currently construct this implementation as the default embedded runtime
- this module is an implementation module; engine policy and task lifecycle ownership remain outside this module

## Boundary

- keep memory-runtime behavior aligned with the shared contract in `mass-runtime-api`
- do not grow business policy or transport protocol logic here
- `mass-runtime-redis` is the sibling opt-in implementation module; keep both aligned to the shared runtime contract
