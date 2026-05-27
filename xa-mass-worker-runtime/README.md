# xa-mass-worker-runtime

Status: higher-level worker runtime owner module.

This module owns worker resource convergence logic that sits above
`mass-runtime-api` and below engine scheduling strategy.

Current scope:

- WorkerGroup declaration owner.
- AdapterNode and NodeGroupBinding relationship owner.
- Worker registration row to runtime slot projection owner.
- Worker state report bounded projection owner.

Boundaries:

- May depend on `mass-runtime-api`, `mass-storage-api`, and `xa-mass-base`.
- Must not depend on `xa-mass-engine` or transport adapter implementations.
- Does not evaluate task matching rules or ranking policy.
- Does not own task runtime, task dispatch binding, or result convergence.
