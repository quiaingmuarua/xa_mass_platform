# mass-storage-memory

Status: shared in-memory storage implementation module.

This module is partly mainline default implementation and partly current
convergence residue. Agents must keep those two ideas separate when summarizing
it for a handoff.

Current scope:

- `InMemoryTaskShellStore`
- `InMemoryWorkerDeclarationStore`
- `InMemoryRuleStorage`

These classes provide in-memory task/rule storage plus the current in-memory
worker declaration store used by engine defaults, focused tests, and storage
adapters that still need a process-local compatibility projection.

Current code truth:

- `InMemoryTaskShellStore` is the real in-memory task shell/control-plane
  default used by SDK/server embedding and focused tests
- `InMemoryWorkerDeclarationStore` is the current in-memory adapter for the
  `xa-mass-worker-runtime` worker declaration port; it maintains primary
  worker identity and secondary group indexes in memory and must not be treated
  as worker runtime scheduling truth
- `InMemoryRuleStorage` is definition storage only; rule-evaluator ownership
  belongs to engine rule-runtime assembly
- SDK auth helpers such as `InMemorySubmitterRegistry` no longer live here; do
  not reintroduce SDK-surface packaging into this module
- other modules should not reintroduce rule evaluator lifecycle into this
  storage implementation module

Read with:

- [../README.md](../README.md)
- [../mass-storage-jdbc/README.md](../mass-storage-jdbc/README.md)
- [../../doc/INFRA_TRUTH_LAYERS.md](../../doc/INFRA_TRUTH_LAYERS.md)

Non-goals for this slice:

- no runtime queue/lease ownership
- no JDBC behavior
- no engine lifecycle or assignment ownership
- no transport protocol ownership
