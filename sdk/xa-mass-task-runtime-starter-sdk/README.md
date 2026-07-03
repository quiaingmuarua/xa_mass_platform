# xa-mass-task-runtime-starter-sdk

Status: first task-runtime starter SDK module.

## Role

- owns task-runtime backend bootstrap for memory or Redis implementations
- owns task-runtime loop-host thread lifecycle for migrated responsibilities
- exposes a host-facing `TaskRuntimeHandle` that delegates to public
  `xa-mass-task-runtime` ports
- exposes the approved task lifecycle command handle and standalone
  `TaskReadViewPort` read-view contract for embedded/server assembly; the
  starter hosts these surfaces but does not own runtime lifecycle truth
- current `xa-mass-engine-starter` assembly consumes this handle for
  memory/Redis backend bootstrap with no starter-owned production loops
- current server profile assembly selects the memory or Redis backend through
  the embedded SDK engine options, not by importing task-runtime ports or Redis
  keyspace internals, and not by injecting legacy `TaskWorkRuntime` /
  `TaskResultRuntime` stores into the engine serving path
- current representative server E2E coverage proves memory-local and Redis
  external polling worker paths through the starter-backed serving lane,
  including Redis backend paths driven by external Node polling, WebSocket, and
  Socket worker processes; Redis Node polling, WebSocket, and Socket
  lease-expiry redispatch now cover dropped-result failure edges; Redis Node
  polling, WebSocket, and Socket late-result replay cover split-process stale
  replay edges after lease expiry and retry finality; Redis task-runtime owner
  reconnect is covered over the same namespace. Redis process
  kill/partition/failover and infra-fault edges remain separate proof gaps

## Boundary

- does not own task item lifecycle, result finality, Redis key layout, engine
  scheduling, worker selection, transport delivery, or server HTTP contracts
- does not expose raw runtime port sets to SDK/server callers; those callers use
  task command/read-view surfaces while engine-starter assembly may keep the raw
  handle for internal wiring
- does not start production engine assignment, dispatch, result, or repair loops
  until the old loop closure plan for that exact responsibility is implemented
- uses task-runtime ports only; backend implementations live in
  `platform_infra/mass-task-runtime-memory` and
  `platform_infra/mass-task-runtime-redis`
