# Kernel Design Documents

Status: current document index for the clean-kernel mechanism workspace.

Cross-module Kernel/Server/Transport authority is defined by the repository
[architecture entrypoint](../../README.md). This index owns Kernel
document routing; [Scheduling](scheduling/README.md) owns the single current
scheduling status matrix.

Task scheduling is the current control-flow mainline. Worker resource and score
remain independent owner contracts used by that mainline; delivery routes,
Adapter connectivity, and execution evidence are not a second Worker lifecycle
system. DIRECT_CALL reuses Worker Delivery for a caller-selected target: its
Server waiter and Adapter-target FIFO are memory-only, while Worker Commands
use a non-overwriting offer to the existing delivery Hash. It creates no Task,
scheduling lane, persisted Worker mode, score check, or Result Routing truth.

The document families are grouped by design concern:

- [Kernel Application Assembly](kernel-application-assembly.md): zero-config
  scheduling application and resource-command boundaries, private Redis
  composition, Java production lifecycle, and standalone Python Oracle boundary.
- [Scheduling](scheduling/README.md): score axes, the cross-pacer Worker
  HOT_ACQUIRE lease protocol, assignment-dispatch pacers, outbound handoff, and
  result routing.
- Resource models:
  [Worker](resource-model/worker-resource-model.md) and
  [Task](resource-model/task-resource-model.md) metadata/query projection
  contracts.
- Runtime Redis shapes:
  [Redis Keyspace](runtime-redis/redis-keyspace.md),
  [Worker Runtime](runtime-redis/worker-runtime-redis-shape.md) and
  [Worker Result Runtime](runtime-redis/worker-result-runtime-redis-shape.md),
  plus the optional
  [Worker Serviceability Runtime](runtime-redis/worker-serviceability-runtime-redis-shape.md).
Python executable-spec code lives under `../executable_spec/`; these documents
and proofs are the semantic input for scoped `kernel_jvm` parity work.
