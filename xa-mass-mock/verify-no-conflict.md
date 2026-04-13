# Port and Process Separation Notes

This file documents the current intended separation after convergence cleanup.

## Verified Mainline

`MockApplicationSpringBootApp` is the only verified mainline entry.

| Entry | Runtime role | HTTP port | WebSocket port |
| --- | --- | --- | --- |
| `MockApplicationSpringBootApp` | API + gateway + engine + mock client bootstrap | `8088` | `18088` |

Notes:

- `server.port=8088` serves the Spring Boot HTTP endpoints.
- `mass.websocket.port=18088` serves the internal gateway WebSocket server.
- mock clients connect outbound to `ws://localhost:18088/ws`.

## Legacy Client-Only Path

The former client-only Spring Boot bootstrap and its `/mock/status` monitoring endpoints have been removed.

Current expectation:

- mock clients are started only through the verified mainline runtime
- there is no second HTTP process to monitor mock clients separately
