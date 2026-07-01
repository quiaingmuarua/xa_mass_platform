# mass-task-runtime-redis

Status: first Redis task-runtime adapter.

## Role

- implements `xa-mass-task-runtime` public ports using Redis runtime state
- keeps Redis key names, frame encoding, Lua scripts, and codec details private
  to this module
- proves the same public-port contract as `mass-task-runtime-memory`, including
  progress snapshots used by engine progress/terminal policy after cutover
- proves runtime owner reconnect over the same Redis namespace: an active lease
  can be rediscovered, expired, retried, claimed as attempt 2, and finalized
  after the first `RedisTaskRuntime` owner is closed
- proves runtime owner recovery after a Redis network partition through a
  test-local TCP proxy: accepted and claimed Redis truth survives the partition,
  then a new runtime owner repairs the expired lease and finalizes attempt 2

## Boundary

- depends on `xa-mass-task-runtime`, Lettuce, and Gson
- does not depend on old `mass-runtime-api`, engine, transport, starter SDK, or
  server modules
- append, claim, and result apply use Lua for atomic multi-key state
  transitions
- scheduler discovery remains task-level: ready backlog frames do not create
  per-item scheduler lane entries
