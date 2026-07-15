# Kernel Design Documents

Status: current document index for the isolated new-kernel workspace.

The document families are grouped by design concern:

- [Kernel Application Assembly](kernel-application-assembly.md): zero-config
  scheduling application and resource-command boundaries, private Redis
  composition, and CLI/FastAPI hosts.
- [Scheduling](scheduling/README.md): score axes, assignment-dispatch pacers,
  outbound handoff, and result routing.
- [Resource Models](resource-model/worker-resource-model.md): Worker and Task
  metadata/query projection contracts.
- Runtime Redis shapes:
  [Worker Runtime](runtime-redis/worker-runtime-redis-shape.md) and
  [Result Routing](runtime-redis/result-routing-runtime-redis-shape.md).
Python executable-spec code lives under `../executable_spec/`; these documents do
not create a second implementation path or current Java implementation truth.
