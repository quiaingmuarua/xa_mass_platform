# mass-task-runtime-memory

Status: first task-runtime memory adapter.

## Role

- implements `xa-mass-task-runtime` public ports for focused contract proof and
  local isolated use
- keeps memory collection shape private to this module
- proves append, task-level scheduler discovery, claim, result finality, active
  lease repair, progress snapshots, bounded final reads, and discard through
  the same public ports planned for Redis

## Boundary

- depends on `xa-mass-task-runtime`
- does not depend on old `mass-runtime-api`, engine, transport, starter SDK,
  server, Spring runtime beans, Redis clients, or physical keyspace contracts
- must not become the semantic owner; it is only one physical implementation
  of the task-runtime public contracts
