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

## Optional Client-Only Bootstrap

`WebSocketClientSpringBootApp` is now optional and non-web.

| Entry | Runtime role | HTTP port | WebSocket role |
| --- | --- | --- | --- |
| `WebSocketClientSpringBootApp` | client-only mock device bootstrap | none | outbound client only |

Notes:

- it no longer starts a separate Spring Web server
- it should not be treated as part of the mainline verified path
- it exists only for isolated client bootstrap scenarios
