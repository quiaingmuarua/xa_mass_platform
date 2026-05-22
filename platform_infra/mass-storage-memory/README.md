# mass-storage-memory

Status: shared in-memory storage implementation module.

This module is partly mainline default implementation and partly current
convergence residue. Agents must keep those two ideas separate when summarizing
it for a handoff.

Current scope:

- `InMemoryTaskStorage`
- `InMemoryWorkerStorage`
- `InMemoryRuleStorage`
- `QLExpressRuleEvaluator`

These classes provide in-memory task/rule storage plus the current in-memory
worker runtime registry used by engine defaults, focused tests, and storage
adapters that still need a process-local compatibility projection.

Current code truth:

- `InMemoryTaskStorage` is the real in-memory task control-plane default used
  by SDK/server embedding and focused tests
- `InMemoryWorkerStorage` is the current in-memory worker runtime registry; it
  maintains primary worker identity and secondary group indexes in memory and
  must not be treated as a DB row-store shape
- `InMemoryRuleStorage` and `QLExpressRuleEvaluator` currently live here, so
  rule-evaluator ownership is infra-local in code today
- SDK auth helpers such as `InMemorySubmitterRegistry` no longer live here; do
  not reintroduce SDK-surface packaging into this module
- other modules should treat the remaining rule helper placement as current
  fact to work with, not as a reason to expand this module further into SDK or
  engine ownership

Read with:

- [../README.md](../README.md)
- [../mass-storage-jdbc/README.md](../mass-storage-jdbc/README.md)
- [../../doc/DB_STORAGE_PRINCIPLES.md](../../doc/DB_STORAGE_PRINCIPLES.md)

Non-goals for this slice:

- no runtime queue/lease ownership
- no JDBC behavior
- no engine lifecycle or assignment ownership
- no transport protocol ownership
