# Platform Infra

Status: shared platform infrastructure module family.

Current phase-1 scope:

- `mass-runtime-api`
- `mass-runtime-memory`

These modules host platform-level runtime semantics and implementations that are
shared by engine, transport, server, and test shells. They do not own business
workflow, task strategy, or transport-specific protocol behavior.

Current truth for this conservative first slice:

- `mass-runtime-api` owns the shared `TaskWorkRuntime` contract and related value types
- `mass-runtime-memory` owns the current in-memory runtime implementation and its focused tests
- `xa-mass-engine` still defaults `TaskManager` constructors to `InMemoryTaskWorkRuntime`; runtime implementation assembly has not yet been lifted fully into sdk/server bootstrap
- Redis runtime and storage extraction are intentionally out of scope for this phase
