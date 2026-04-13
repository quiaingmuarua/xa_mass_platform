# xa-mass-mock

`xa-mass-mock` is the verified runnable entry module for the current repository mainline.

Use this module for end-to-end validation of:

- Spring Boot HTTP APIs
- the internal gateway WebSocket server
- mock device bootstrap and result write-back

Repository-level startup instructions in [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md) are the source of truth.

## Current Role

- real Spring Boot entrypoint: `com.xa.mass.mock.MockApplicationSpringBootApp`
- wires `api + starter + gateway + engine`
- default `dev` startup auto-starts mock WebSocket clients

## Port Model

Two ports are used on purpose:

| Property | Default | Purpose |
| --- | --- | --- |
| `server.port` | `8088` | Spring Boot HTTP port for `/status`, `/doc.html`, and task APIs |
| `mass.websocket.port` | `18088` | internal gateway WebSocket server port |

Mock clients connect through:

| Property | Default |
| --- | --- |
| `mock.client.uri` | `ws://localhost:${mass.websocket.port}/ws` |

## Verified Main Entry

Start from the repository root:

```bash
./mvnw -DskipTests compile
java -cp "xa-mass-mock/target/classes:xa-mass-starter/target/classes:xa-mass-api/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-base/target/classes:<runtime-classpath>" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

After startup:

- HTTP: `http://localhost:8088/status`
- HTTP: `http://localhost:8088/status/tasks`
- HTTP: `http://localhost:8088/doc.html`
- WebSocket: `ws://localhost:18088/ws`

## Effective Mock Client Startup

For the verified default `dev` path, mock clients are started by:

- `xa-mass-mock/src/main/java/com/xa/mass/mock/starter/WebSocketClientStarter.java`

Startup behavior:

- gated by `mock.client.auto-start=true`
- triggered by `ApplicationReadyEvent`
- idempotent startup protection through an internal `AtomicBoolean`
- there is no longer a separate client-only Spring Boot application or `/mock/status` monitor surface

## Key Config

| Property | Default | Meaning |
| --- | --- | --- |
| `server.port` | `8088` | HTTP port |
| `mass.websocket.port` | `18088` | gateway WebSocket port |
| `mock.client.auto-start` | `true` | auto-start mock clients in default `dev` path |
| `mock.client.uri` | `ws://localhost:${mass.websocket.port}/ws` | target gateway address |
| `mass.mock.data.devices` | `mock/mock_devices.json` | mock device data |
| `mass.mock.data.tasks` | `mock/mock_tasks.json` | mock task data |
| `mass.mock.data.rules` | `mock/mock_rules.json` | mock rule data |

## Regression Coverage

Focused verified regression command:

```bash
mvn --% -pl xa-mass-mock -am -Dtest=MassWebSocketClientImplTest,TaskApiIntegrationTest,WebSocketClientStarterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Covered areas:

- `TaskApiIntegrationTest`: create -> approve -> assign -> run -> complete
- `WebSocketClientStarterTest`: auto-start and idempotent startup behavior
- `MassWebSocketClientImplTest`: ignore `response=true` task frames and avoid echo loops
