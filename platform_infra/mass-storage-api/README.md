# mass-storage-api

Status: shared storage-contract module.

This module owns shared storage contracts and storage-adjacent rule types that
must be referenced by engine, storage implementations, server, SDK, and test
shells without making `xa-mass-engine` the package root for those APIs.

Current scope:

- `TaskStorage`
- `TaskDetailStore` as a bounded compatibility-projection seam
- `WorkerStorage`
- `RuleStorage`
- rule storage value types used directly by `RuleStorage`

Contract split inside this module:

- `TaskStorage` is the control-plane task aggregate contract
- `TaskDetailStore` is not control-plane truth and not a public SDK/server read
  model; it is the bounded compatibility projection for task-message residue and
  attempt detail while the long-term trace/audit sink remains separate

How to read `TaskDetailStore` correctly:

- allowed: bounded per-task compatibility reads, residue repair, runtime
  validation helpers, focused test/demo inspection
- not allowed: treating `TaskMsg` / `TaskMsgAttempt` as engine truth, growing a
  public pagination/history API around them, or using them as the main model
  for server/SDK contracts

If a new caller wants large-scale message history, attempt timelines, or
cross-task analytics, that belongs in trace/audit/export ownership rather than
in this module's storage contract.

Non-goals for this slice:

- no queue/lease/runtime ownership
- no storage implementation wiring
- no JDBC or memory behavior
- no promotion of compatibility residue into canonical query truth
