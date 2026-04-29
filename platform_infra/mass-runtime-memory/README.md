# mass-runtime-memory

Status: current in-memory runtime implementation module.

## Role

- provides the in-memory `TaskWorkRuntime` implementation used by the current embedded default path
- hosts focused runtime tests for queue/lease/retry/backpressure semantics

## Current Truth

- `InMemoryTaskWorkRuntime` is the only shipped runtime implementation in this phase
- `xa-mass-engine` still constructs this implementation by default in `TaskManager` when no runtime is injected
- this module is an implementation module; engine policy and task lifecycle ownership remain outside this module

## Near-Term Boundary

- keep memory-runtime behavior aligned with the shared contract in `mass-runtime-api`
- do not grow business policy or transport protocol logic here
- future `mass-runtime-redis` should be a sibling implementation module, not a forked engine-local path
