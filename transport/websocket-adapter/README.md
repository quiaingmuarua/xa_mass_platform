# xa-mass-transport-websocket

## Role

- WebSocket server and session management
- inbound dispatch and downstream message publish
- connection context and protocol handling
- Java package namespace remains `com.xa.mass.gateway.*`

## Current Status

- verified as part of full startup through `xa-mass-dev-app`
- not independently validated as a standalone runnable app
- participates in the verified happy-path task publish/result write-back flow

## Start Here

- `src/main/java/com/xa/mass/gateway/server/WebSocketServerImpl.java`
- `src/main/java/com/xa/mass/gateway/dispatcher/WebSocketMessageDispatcher.java`

## Boundaries

- do not assume this module has its own verified Boot entry
- when debugging task result write-back, inspect gateway handlers together with `TaskManager`
- use these documents before trusting module-local assumptions:
  - [`../AGENTS.md`](../AGENTS.md)
  - [`../doc/AGENT_BASELINE.md`](../doc/AGENT_BASELINE.md)
  - [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md)
