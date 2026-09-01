# XA Mass Runtime Viewer

Vue 3 frontend for Worker and Task runtime observation plus a thin finite Task
file client. It is derived from Pure Admin Thin 6.2.0 and contains no login,
token, dynamic-permission, fake-user, or example-business path.

## Requirements and local run

- Node 22.19 or newer, below Node 25
- pnpm 11.9 or newer, below pnpm 12

The checked `packageManager` and CI still execute pnpm 11.9.0; `engines` only
describes the supported local and hosting range.

- `server_jvm` on `127.0.0.1:18082` for API mode

```text
pnpm install --frozen-lockfile
copy .env.example .env.local
pnpm dev --host 127.0.0.1
```

The default data source is the real API. Vite proxies relative `/api` requests
to `VITE_RUNTIME_PROXY_TARGET`; Server CORS is not enabled. Explicit Mock mode
is available through `pnpm dev:mock` and never activates as a fallback.

The Code Dictionary is a current-build projection, so generate it before a
local Vite session. The OpenAPI Reference is a committed build-time projection;
regenerate it whenever the public Server API changes:

```powershell
.\gradlew.bat :distribution:server:generatePlatformDiagnosticCodes
.\gradlew.bat :server_jvm:exportOpenApiSnapshot
Set-Location frontend
corepack pnpm dev
```

Vite serves only the exact
`/reference/platform-diagnostic-codes.json` path from that build output. It
returns `404` when the projection is missing and never exposes the surrounding
Gradle build directory. Mock mode does not fabricate a dictionary.

The public Vercel deployment is built with `pnpm build:demo` and therefore uses
that explicit Mock mode. It is a current UI and architecture demonstration, not
a hosted XA Mass Runtime: mutating Task and Direct Debug actions remain disabled,
and no request is proxied to a local or remote Server. Real Runtime data is
served only by the frontend bundled with the Server Runtime on the same origin.
The public `/api-reference` (and Vercel-only `/scalar` alias) renders the
committed `/reference/openapi.json` snapshot with request execution disabled.
The live Server continues to own `/scalar` and `/v3/api-docs`.

Routes:

```text
/runtime/workers
/runtime/tasks
/api-reference
/reference/error-codes
```

The sidebar also links to the current origin's `/scalar` and `/overview.htm`.
On Vercel, the SPA maps `/scalar` to the same static API Reference; on the Java
Server, its MVC Scalar route takes precedence and stays live. The OpenAPI
snapshot is committed at `frontend/public/reference/openapi.json`. The
dictionary JSON is available at
`/reference/platform-diagnostic-codes.json`. The overview source is
`frontend/public/overview.htm`; generated `dist` and dictionary content are not
committed.

## API Reference

The API Reference uses the official Scalar Vue component in a top-level lazy
route, outside the Runtime Viewer layout. It loads only
`/reference/openapi.json`, hides request execution and developer tools, and
does not load with the Worker Runtime first screen. The snapshot is a checked
documentation projection rather than Runtime truth: use a running Server's
`/scalar` when the current live schema or request debugger is required.

## Diagnostic Code Dictionary

The page validates schema v1 with Zod and then searches the current Server,
Netty Adapter and Worker Core enum projection by code, symbol, meaning or
owner. Owner namespaces remain independent, so the same number may appear on
multiple rows. This reference does not bind an API operation to a code and is
not a cross-version compatibility promise. Loading, missing-file,
schema-incompatible and empty-search states remain explicit; no Pinia or
Runtime truth is created.

## Worker observation

The Worker page first obtains a bounded, unstable WorkerGroup preview and then
loads Workers only for the selected Group. Adapter Network and Kernel Worker
Score remain separate observation axes:

```text
connected != bound != schedulable != executing
```

Network values are `connected`, `disconnected`, or `unknown`. Scheduling values
are bounded projections such as `hot-score-overdue`, `held-hot`, `paused`,
`recovery`, `cold`, or `missing`; the browser never receives raw Score. Each
axis refreshes independently and preserves only its own last successful value
as stale evidence. Neither Group nor Worker preview promises totals,
completeness, stable ordering, history, or complete matching.

The Worker table and detail drawer expose a single-target `Direct Debug` action
in API mode. Its searchable Event selector opens the current WorkerGroup Event
catalog while still accepting a custom full Event Name; the catalog remains an
input suggestion rather than an authorization list. Requests and responses are
shown as a compact chat and the latest 20 calls per Worker live only in the
current Pinia/browser memory. Closing the drawer or changing routes preserves
that diagnostic history, while a browser refresh clears it; no browser or
Server storage is used. Direct Debug remains best-effort, bypasses Kernel
scheduling, creates no TaskItem or Worker lease, and never proves that a Worker
is schedulable or executing. Mock mode disables this mutating action and does
not fabricate a response.

## Task page

`Task Runtime Preview` reads the highest `1..100` Task Score coordinates and
displays their Task and WorkerGroup descriptor projections in Owner order. It
has no total, cursor, paging, stable-window or completeness meaning. The page
shows only `Awaiting Review`, `Running Initial`, `Running Visible` and `Closed`;
it never receives raw Score. `Running Initial` is a fixed Kernel sorting
coordinate rather than a wall-clock deadline, and `Running Visible` does not
prove a Task is executing. Search filters only the current browser window and
does not issue another API request. Descriptor gaps remain visible and are never
repaired or inferred by the browser.

In API mode, each readable `ON_DEMAND_ITEM_RULE + PARK_WHEN_IDLE` Task with a
WorkerGroup descriptor exposes a single-Item `Task Call Debug` action. The
debug composer
accepts an advisory Event Name, a JSON Object Payload, and an Item-level JSON
Object `allocationRule`, then calls the existing managed Task endpoint. Calls
go through Kernel scheduling; the browser does not interpret the rule or infer
which Worker matched. Each Task retains at most 20 diagnostic exchanges in the
current Pinia/browser memory. A `not_observed` response means submission was
accepted without a Result in the bounded wait window, so the user may manually
load that Message ID later; there is no automatic polling. Both `items:call`
and manual `results:load` may instead return `failed`, which is shown as a
terminal Item Result without payload or failure reason. Browser refresh clears
this history, and Mock mode never fabricates Task Call results.
Both endpoints decode the direct Message-ID-keyed Result Map; manual
`results:load` sends the direct Message-ID array. Finite Task append likewise
sends a direct Item array and reads the direct outcome Map.

`Finite Task Workbench` is a drawer layered over the preview and is available
only in API mode:

1. Validate a local UTF-8 `.txt` file (non-empty, at most 1 MiB and 10,000
   lines).
2. Lazily load the bounded WorkerGroup Preview, then select a Group, advisory
   Event Name, and Payload key.
3. Create one ordinary finite Task through `POST /api/v1/tasks`.
4. Convert each line into one standard TaskItem and append chunks of at most
   100 Items. The direct Message-ID-keyed response uses shared action outcomes:
   accepted Items are `applied`, and a locally rejected Item carries
   `rejected + code/message`.
5. Require explicit approval before calling the Task approve endpoint.
6. Export successful Results manually through
   `POST /api/v1/tasks/{taskId}/results:export`; `400/12010` is shown as not
   ready and never triggers automatic polling. The request has no terminal
   wait budget or JSON body.

Create/append, approve and successful export each request a fresh Task Runtime
Preview, but failure to refresh never rolls back the completed write. The
browser records only confirmed stages: `Created`, `Items Appended`,
`Approved`, and `Export Ready`. It never simulates `RUNNING` or `TERMINAL` from
elapsed time. Append failure stops the flow before approval. Ordinary finite
Task records live only in the current browser session, so a refresh cannot
rediscover them until a future Task list/query API exists. Mock mode disables
the mutating flow and sends no Task request.

## Verification

```text
pnpm lint
pnpm typecheck
pnpm test
pnpm build
pnpm build:demo
```

The frontend owns no Task, Worker identity, scheduling, result, or file-storage
truth. It only composes public Runtime API calls and downloads the response
stream selected by the user.
