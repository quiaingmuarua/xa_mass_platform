# Kernel Design Documents

Status: current document index for the clean-kernel mechanism workspace.

The document families are grouped by design concern:

- [Kernel Application Assembly](kernel-application-assembly.md): zero-config
  scheduling application and resource-command boundaries, private Redis
  composition, and CLI/FastAPI hosts.
- [Scheduling](scheduling/README.md): score axes, the cross-pacer Worker
  HOT_ACQUIRE lease protocol, assignment-dispatch pacers, outbound handoff, and
  result routing.
- Resource models:
  [Worker](resource-model/worker-resource-model.md) and
  [Task](resource-model/task-resource-model.md) metadata/query projection
  contracts.
- Runtime Redis shapes:
  [Worker Runtime](runtime-redis/worker-runtime-redis-shape.md) and
  [Seed Result Runtime](runtime-redis/seed-result-runtime-redis-shape.md).
Python executable-spec code lives under `../executable_spec/`; these documents
and proofs are the semantic input for scoped `kernel_jvm` parity work.
