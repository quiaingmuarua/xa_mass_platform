# XA Mass Runtime Viewer

Vue 3 frontend for the read-only Worker and configured Task Runtime views plus
the Task Batch Lab. It is derived from Pure Admin Thin 6.2.0 but deliberately
contains no login, token, dynamic-permission, fake-user, or example-business
path.

## Requirements

- Node 22.19.x
- pnpm 11.9.0
- `server_jvm` on `127.0.0.1:18082` for API mode

## Local run

```text
pnpm install --frozen-lockfile
copy .env.example .env.local
pnpm dev --host 127.0.0.1
```

The default data source is the real API. Vite proxies relative `/api` requests
to `VITE_RUNTIME_PROXY_TARGET`; Server CORS is not enabled. WorkerGroup and
long-lived Task coordinates come from the Server's configured resource
directory, not a frontend environment list.

Routes:

```text
/runtime/workers
/runtime/tasks
/runtime/task-batches
```

The sidebar also links to Server-hosted reference pages:

```text
/scalar
/overview.htm
```

`overview.htm` is maintained as the static source at
`frontend/public/overview.htm` and is copied to the frontend build root. It is
the human-facing projection of the repository
[architecture entrypoint](../README.md), not an independent mechanism
truth source. Generated `frontend/dist/overview.htm` is never committed.

The Task Batch page is available only in API mode with the real
`scenario-workers` Profile. It selects a configured WorkerGroup and advisory
EventCode, accepts one Payload key and `.txt` file, uploads it, runs the batch,
and offers the published JSONL as a manual download. The table is
browser-session memory only. Server input and output files remain under the
Lab directory after a page refresh.

Use the fixed Mock data only when explicitly requested:

```text
pnpm dev:mock
```

Mock mode never activates as a fallback after a real API failure. It does not
simulate batch execution and never calls the Task Batch API.

## Worker status mock

The Worker page presents Adapter Network and Kernel Scheduling as two separate
observation axes. Both axes are currently deterministic frontend Mock data,
even when Worker descriptors come from the real Runtime View API. The page
marks every status surface with `MOCK` and never derives either axis from a
Worker descriptor.

Network and Scheduling observations load independently after a Worker sample
appears. They can be refreshed without resampling Workers, and one failed axis
does not turn into a state on the other axis. A failed refresh keeps the last
successful value only as `Stale`. There is no combined online state and no
connection control:

```text
connected != bound != schedulable != executing
```

Future backend integration should add an HTTP implementation of
`WorkerStatusDataSource`; the Store and page consume only semantic states and
must never parse raw Worker Score values.

## Verification

```text
pnpm lint
pnpm typecheck
pnpm test
pnpm build
```

The Worker page remains an unstable, incomplete sample. Its Mock Scheduling
axis may label a semantic lease projection, but it does not expose raw Score or
prove execution. The Task page shows only Profile-configured descriptors.
Neither page infers Worker totals, a combined online state, Task
approval/running state, transport sessions, history, or complete matching
results. The Task Batch page mutates only through the public Lab API: it does
not create or expose Tasks, Worker identity, or scheduling truth.
