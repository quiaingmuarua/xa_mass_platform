# xa-mass-dev-app

`xa-mass-dev-app` is the verified runnable entry module for the current repository mainline.

Use this module for end-to-end validation of:

- Spring Boot HTTP APIs
- the internal gateway WebSocket server
- mock worker bootstrap and result write-back

Repository-level startup instructions in [`../doc/VERIFIED_RUNBOOK.md`](../doc/VERIFIED_RUNBOOK.md) are the source of truth.

## Current Role

- real Spring Boot entrypoint: `com.xa.mass.mock.MockApplicationSpringBootApp`
- starts runtime through `xa-mass-sdk`, exposes the current HTTP/status shell through `xa-mass-api`, and still wires engine-side manager/rule beans directly for dev and E2E validation
- starts platform runtime through `xa-mass-sdk`; runtime still composes gateway and engine internally
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
java -cp "xa-mass-dev-app/target/classes:xa-mass-sdk/target/classes:xa-mass-api/target/classes:xa-mass-engine/target/classes:xa-mass-gateway/target/classes:xa-mass-core/target/classes:<runtime-classpath>" \
  com.xa.mass.mock.MockApplicationSpringBootApp
```

After startup:

- HTTP: `http://localhost:8088/status`
- HTTP: `http://localhost:8088/status/tasks`
- HTTP: `http://localhost:8088/doc.html`
- WebSocket: `ws://localhost:18088/ws`

## Effective Mock Client Startup

For the verified default `dev` path, mock clients are started by:

- `xa-mass-dev-app/src/main/java/com/xa/mass/mock/starter/WebSocketClientStarter.java`

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
| `mock.client.task-result-status` | `SUCCESS` | force mock result frames to `SUCCESS` or `FAILED` |
| `mass.mock.data.workers` | `mock/mock_workers.json` | mock worker data |
| `mass.mock.data.worker-contexts` | `mock/mock_worker_contexts.json` | explicit mock worker-context data |
| `mass.mock.data.tasks` | `mock/mock_tasks.json` | mock task data |
| `mass.mock.data.rules` | `mock/mock_rules.json` | explicit mock worker-match rules; non-empty config overrides the current defaults |

Mock-data loading order:

- workers
- explicit worker contexts
- fallback worker-context seeding only for workers that still have no worker context
- rules: non-empty config replaces the current default rules; empty config is treated as no override
- tasks

## Regression Coverage

Mainline stance:

- end-to-end integration coverage is the primary acceptance gate for runtime behavior
- unit tests remain important support coverage, but they are not the main proof for task lifecycle correctness
- integration suites are grouped by domain under `src/test/java/com/xa/mass/mock/e2e`
- shared HTTP/task polling helpers now live in `src/test/java/com/xa/mass/mock/e2e/support/AbstractMockE2eTest`

Focused verified regression command:

```bash
mvn --% -pl xa-mass-dev-app -am -Dtest=MassWebSocketClientImplTest,TaskApiIntegrationTest,TaskApiFailureResultIntegrationTest,TaskApiLifecycleGuardsIntegrationTest,WebSocketClientStarterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Covered areas:

- `e2e/lifecycle`: create -> approve -> assign -> run -> complete, pause/resume guards, pause-completion, terminate-running, resume-and-complete
- `e2e/results`: failed-result terminal closure, mixed results, callback replay idempotency
- `e2e/assignment`: delayed worker availability and multi-task assignment behavior
- `e2e/audit`: `stateValidation` exposure and terminal metadata consistency through the real HTTP path
- `WebSocketClientStarterTest`: auto-start and idempotent startup behavior
- `MassWebSocketClientImplTest`: ignore `response=true` task frames, avoid echo loops, and allow configurable `SUCCESS` / `FAILED` result payloads


