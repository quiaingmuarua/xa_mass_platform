# mass-storage-memory

Status: shared in-memory storage implementation module.

Current scope:

- `InMemoryTaskStorage`
- `InMemoryWorkerStorage`
- `InMemoryRuleStorage`
- `QLExpressRuleEvaluator`
- `InMemorySubmitterRegistry`

These classes provide in-memory control-plane storage implementations shared by
engine defaults, focused tests, and storage adapters that still need a
process-local compatibility projection.

Non-goals for this slice:

- no runtime queue/lease ownership
- no JDBC behavior
- no engine lifecycle or assignment ownership
