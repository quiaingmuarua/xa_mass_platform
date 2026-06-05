# mass-storage-api

Status: shared storage-contract module.

This module owns persistence/control-plane storage contracts. Kernel-facing
task ports and worker-matching rule value contracts live in
`xa-mass-kernel-spi`; storage contracts may depend on those value types, but
engine production must not depend on this module.

Current scope:

- `TaskShellStore`
- `TaskShellLifecycleQuery`
- `RuleStorage`
- `CatalogMetadataStore`

Contract split inside this module:

- `TaskShellStore` is the control-plane task shell contract. It stores stable
  task shell truth and must not grow runtime queue, dispatch, lease, heartbeat,
  history, or analytics ownership.
- `TaskShellLifecycleQuery` is a current-shell lifecycle query for policies
  such as max-runtime deadline termination. It is not dispatch-admission or
  ready-queue truth.
- `RuleStorage` persists rule definitions using the shared
  `xa-mass-kernel-spi` rule value contracts. Rule CRUD remains storage/control
  plane; matching consumes rule sets through engine matching-time providers.
- `CatalogMetadataStore` persists project/event catalog metadata. Event codes
  stay globally unique capability identities; project-event bindings are stored
  separately from event definitions and from worker topology.

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
- no worker topology, worker presence, or WorkerGroup runtime ownership
- no review/export materialization ownership
