# xa-mass-worker-runtime

Status: higher-level worker runtime owner module.

This module owns worker resource convergence logic that sits above
`mass-runtime-api` and below engine scheduling strategy.

Current scope:

- WorkerGroup declaration owner.
- AdapterNode and NodeGroupBinding relationship owner.
- Worker registration row to runtime slot projection owner.
- Worker state report bounded projection owner.
- Worker admission, occupancy, and exclusive lease owner.
- Task-local warm candidate hint storage.
- Worker capability composition and registry snapshot read model.
- Stage-1 worker candidate index and source guard.
- Stage-1 candidate source orchestration and warm/cold merge.

Boundaries:

- May depend on `mass-runtime-api`, `mass-storage-api`, and `xa-mass-base`.
- Must not depend on `xa-mass-engine` or transport adapter implementations.
- Does not evaluate task matching rules or ranking policy.
- Does not decide whether warm candidates are useful; engine strategy writes
  useful assignment evidence into this module after Stage-2.
- Does not own task runtime, task dispatch binding, or result convergence.
