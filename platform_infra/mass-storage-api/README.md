# mass-storage-api

Status: shared storage-contract module.

This module owns shared storage contracts and storage-adjacent rule types that
must be referenced by engine, storage implementations, server, SDK, and test
shells without making `xa-mass-engine` the package root for those APIs.

Current scope:

- `TaskStorage`
- `WorkerStorage`
- `RuleStorage`
- rule storage value types used directly by `RuleStorage`

Non-goals for this slice:

- no queue/lease/runtime ownership
- no storage implementation wiring
- no JDBC or memory behavior
