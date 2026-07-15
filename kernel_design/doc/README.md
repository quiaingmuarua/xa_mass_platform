# Kernel Design Documents

Status: current document index for the isolated new-kernel workspace.

The document families are grouped by design concern:

- [Scheduling](scheduling/README.md): score axes, assignment-dispatch pacers,
  outbound handoff, and result routing.
- [Resource Models](resource-model/worker-resource-model.md): Worker and Task
  metadata/query projection contracts.
- [Runtime Redis Shapes](runtime-redis/worker-runtime-redis-shape.md): Redis
  key and owner-shape notes adopted or evaluated by the executable spec.

Python executable-spec code lives under `../executable_spec/`; these documents do
not create a second implementation path or current Java implementation truth.
