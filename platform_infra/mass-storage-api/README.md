# mass-storage-api

Status: shared storage-contract module.

This module owns shared storage contracts and storage-adjacent rule types that
must be referenced by engine, storage implementations, server, SDK, and test
shells without making `xa-mass-engine` the package root for those APIs.

Current scope:

- `TaskShellStore`
- `TaskShellLifecycleQuery`
- `RuleStorage`
- rule storage value types used directly by `RuleStorage`

Contract split inside this module:

- `TaskShellStore` is the control-plane task shell contract. It stores stable
  task shell truth and must not grow runtime queue, dispatch, lease, heartbeat,
  history, or analytics ownership.
- `TaskShellLifecycleQuery` is a current-shell lifecycle query for policies
  such as max-runtime deadline termination. It is not dispatch-admission or
  ready-queue truth.

Worker declaration contracts are owned by `xa-mass-worker-runtime`.
`mass-storage-api` must not reintroduce `WorkerDeclarationStore` or
`WorkerDeclarationRecord`; storage implementations that persist workers should
implement the worker-runtime declaration port as adapters.

If a new caller wants large-scale message history, attempt timelines, or
cross-task analytics, that belongs in trace/audit/export ownership or a
server-local review materialization pipeline rather than in this module's
storage contract.

Non-goals for this slice:

- no queue/lease/runtime ownership
- no storage implementation wiring
- no JDBC or memory behavior
- no review/export materialization ownership
