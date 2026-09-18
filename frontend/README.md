# XA Mass Console

Vue 3 console for Worker and Task runtime observation, a thin finite Task
file client, SMS business pages and public Demo. It is derived from Pure Admin Thin 6.2.0 and contains no login,
token, dynamic-permission, fake-user, or fabricated-user path.

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
a hosted XA Mass Runtime: platform Task and Direct Debug mutations remain disabled,
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
/sms
/sms/listeners
/sms/metrics
/messages
/messages/tasks/:taskId
/app-checks
/app-checks/tasks/:taskId
```

The same navigation is available in a drawer on narrow screens.
The sidebar also links to the current origin's `/scalar` and `/overview.htm`.
On Vercel, the SPA maps `/scalar` to the same static API Reference; on the Java
Server, its MVC Scalar route takes precedence and stays live. The OpenAPI
snapshot is committed at `frontend/public/reference/openapi.json`. The
dictionary JSON is available at
`/reference/platform-diagnostic-codes.json`. The overview source is
`frontend/public/overview.htm`; generated `dist` and dictionary content are not
committed.

## Build ownership

`pnpm build` writes the unified console to `dist`; `pnpm build:demo` writes
`dist-demo` for public hosting. There is one dependency graph and theme. The
console owns navigation and page layout; Runtime configuration and Store lifetime
belong only to the Runtime route container. SMS and Reference pages do not depend
on Runtime configuration or stores.

## SMS business pages

`src/sms/` owns the SMS pages, API client and page-local business state. The
[SMS Owner](../scenarios/sms-reception-jvm/README.md) retains business semantics.
The sidebar shows SCENARIOS / SMS only after one successful, validated
`GET /api/v1/sms/catalog` observation. The shared catalog request has a five-second
timeout and no periodic retry: 404 means disabled; other errors or invalid content
mean availability is unconfirmed, with an explicit retry. Navigation and the page
share the initial catalog. Backend run changes refresh the catalog and discard
an old selected listener. This observation does not register or enable SMS.

The three SMS routes are lazy loaded under the common layout. Page tabs retain
form and selection; leaving SMS aborts requests and stops polling, without
resubmitting business commands. SMS uses only same-origin `/api/v1/sms/*` calls.
The existing platform theme preference is used; no SMS theme store remains.
Runtime configuration errors do not prevent SMS or Reference pages from loading.

Public Mock Demo hides the SMS entry, makes no SMS requests, and explains that
SMS is unsupported on direct visits. An ordinary Server without SMS serves the
same console pages but their catalog check reports the feature as disabled.
The Boot executable owns the finite page forwards, including trailing slashes; unknown
API and asset paths remain errors. Scenario APIs, Groups and jobs remain gated by
`preview`, which enables SMS and Messages together. The shared assets do not enable those resources.

For SMS development, run the shared scenario launcher and set `VITE_RUNTIME_PROXY_TARGET`
to its Server origin (default `http://127.0.0.1:18500`) before starting the same
frontend Vite server. The same `/api` proxy carries platform and SMS requests.
Runtime and SMS Preview ZIPs both package this `dist`; the Server JAR contains no
separate SMS frontend.

## Messages business pages

### Task workspace in explicit Mock mode

`/messages` presents the `messages` Project's Tasks; `/messages/tasks/:taskId`
presents one Task and its bounded Result preview. Creation uses a business drawer
for the fixed message.send event, retaining the existing phone-file and country
validation. It does not expose generic event, Matching or refill configuration.
The Task list is newest-first, capped at 100 without pagination. Search and state
filters apply only to that loaded window; returning from detail preserves them,
the scroll position and focused row. Missing business metadata retains the Task,
and managed Tasks have a separate type marker.

The list and detail show observed enqueued send total and delivered-success count. Delivery
success counts each message once across DELIVERED, READ and REPLIED; SENT alone
is displayed as sent, not delivery success. Mock supplies whole-Task counts
independently of the 100-Result preview; unknown counts remain absent. The
create action says "创建并开始发送" and explains automatic approval after complete
Item submission. This UI has no manual review action or waiting-for-review sample.
New Tasks receive an automatic display name of
`msg-{senderCountry|ANY}-{recipientCountry}-{count}-{YYYYMMDD}-{HHmmss}`;
the timestamp is the local submission time. Country/recipient edits update the
name preview. This display name is not a Task ID or an idempotency key: the data
source still supplies the Task ID, and real IDs remain Server-owned.

The detail preview contains at most 100 produced Results, including failures,
with no pagination, export or whole-Task completion percentage. A Task's scheduling
state, observed execution result and later receipt are distinct. Terminal scheduling
does not stop receipts. Results are a bounded sample with no promised latest/file
order. Read failures retain known data; empty Results do not imply failure.

All new view access goes through `MessageTaskSource`, a frontend view boundary,
with explicit ApiMessageTaskSource and MockMessageTaskSource implementations.
API uses /api/v1/messages/tasks; Task identity is Server-owned. Mock labels its
pages and drawer and makes no platform, Messages, Lab or export requests. Samples and locally created
Tasks last for the console session; a full reload resets them. Closing creation
retains the draft; success clears it and opens detail. Submission is single-flight,
and an unconfirmed outcome cannot trigger an automatic retry.

The fixed samples include cross-country sends, ANY, missing metadata, failures,
empty and truncated Results. Refreshing `msg-follow-up` advances its receipt from
SENT to READ to REPLIED while its Task remains terminal; refreshing
`msg-read-error` fails on its second read and recovers on the third. These are
deterministic UI samples, with no timers or simulated background scheduler.

```powershell
cd frontend
pnpm dev:mock --host 127.0.0.1 --port 18501
```

Open `http://127.0.0.1:18501/messages` in Mock mode. API uses the same list,
drawer and `/messages/tasks/{taskId}` detail, including direct reload through the
Server page forward. The old Campaign and metrics pages have been removed.
API failure never switches data source. Catalog gates availability independently
of SMS; Mock hides SMS and never calls catalog.

API lists at most 100 Project Tasks with a truncation notice, loaded-only filters
and manual refresh. Name/configuration come from Task, creation time from its
Project directory, Task state from Task Score, and quantities from Item Score.
Send total is the current ZSET member count; sent=6..9, delivered=7..9, read=8..9,
replied=9, failed=5. Counts never derive from preview rows or a client cache.
The detail shows at most 100 produced Results, including failures and content
parse errors. It promises neither latest/file order nor a common snapshot with
Score counts. Task terminality does not stop observation of subsequent receipts.

API creation completes append and automatic approval before returning taskId.
Unconfirmed submission preserves the draft and offers a known Task link, without
automatic retry. Enter/refresh are the only Task reads; there is no background
statistics polling. Known data survives read errors. No pagination or export
control is shown; the platform export endpoint remains unchanged.

Recipient country and sender range are independent (CN and ANY defaults).
An optional sender phone adds an intersecting condition. UTF-8 files remain local,
at most 1 MiB/1000 numbers, with BOM and LF/CRLF/CR, original line errors and duplicate
rejection; finite Task files retain their separate 10000-line limit. Lab interprets
the submitted JSON body. Use [Scenario Preview](../distribution/server/PREVIEW.md)
with `VITE_RUNTIME_PROXY_TARGET=http://127.0.0.1:18500` for real business execution.

## App Checks Task workspace

`/app-checks` and `/app-checks/tasks/:taskId` share one API/Mock workspace for the
`app-checks` Project. Its independent Catalog probe controls navigation: 404 means
not enabled, while network/5xx failures allow explicit retry. Boot serves the exact
list/detail paths in both profiles; static delivery never enables a scenario.

The list displays at most 100 Tasks with loaded-only search, App/state filters and
preserved return position. Managed and missing-metadata Tasks remain visible.
Creation selects an App and a number country, imports UTF-8 text (1 MiB, at most
1000 numbers) or edits lines, then validates the simulation JSON. The pure phone
file utilities are shared with Messages; finite Task files keep their 10000 limit.
Range examples preserve delay. Display names use `check-{app}-{country}-{count}-`
plus local submission time; Server supplies Task IDs and salt. Creation appends and
automatically approves through the existing API. Unknown submissions retain the
frozen draft and known Task link across drawer close/reopen, without retry.

Details keep Task state, Item Score counts and Result content independent. Registered
and unregistered answers both mean successful execution. Failed results carry no
answer; content errors preserve the execution status. The table is a bounded preview
of at most 100 Results, without pagination, export or whole-Task business totals.
Reads happen on entry/manual refresh; errors preserve the known snapshot and late
responses cannot overwrite another Task.

Manual preview verification uses Web Crypto SHA-256 and BigInt, using saved salt,
actual returned Worker, number, simulation and Group. It compares the answer and
configured delay, with matched/mismatch/unavailable rows. Failures cannot be
recomputed without their original executing identity. Verification makes no API
calls, does not audit scheduling or attempts, and is discarded on a new snapshot.
Unsupported Web Crypto disables the action without remote fallback.

Explicit Mock provides the same flow, a fixed valid vector, intentional mismatches,
failed/invalid/empty/truncated/missing-data and read-error samples. New Tasks live
only in this console session, with no timer-driven fake execution. All Mock Catalog,
create/read/refresh/verify operations make zero service requests. API never falls
back to Mock. Browser acceptance exercises source and fresh Preview ZIP with real
App Workers; existing backend failure/late-result/restart proofs remain in place.

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

Worker and Task preview limits default to 100 and can be set to `1..1000`
before refreshing. The choice remains in the current browser session. A Worker
sample larger than 100 is observed in sequential batches of at most 100 per
status axis; Network and Scheduling still progress independently. WorkerGroup
Preview keeps its separate 100-Group request.

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

`Task Runtime Preview` reads the highest `1..1000` Task Score coordinates and
displays their Task and WorkerGroup descriptor projections in Owner order. It
has no total, cursor, paging, stable-window or completeness meaning. The page
shows only `Awaiting Review`, `Running Initial`, `Running Visible` and `Closed`;
it never receives raw Score. `Running Initial` is a fixed Kernel sorting
coordinate rather than a wall-clock deadline, and `Running Visible` does not
prove a Task is executing. Search filters only the current browser window and
does not issue another API request. Descriptor gaps remain visible and are never
repaired or inferred by the browser.

In API mode, each readable `PARK_WHEN_IDLE` Task with a
WorkerGroup descriptor exposes a single-Item `Task Call Debug` action. The
debug composer
accepts an advisory Event Name, a JSON Object Payload, and an Item-level
`workerSelector: {executorName, input}`. For `worker.any`, input must be `{}`; the Group must explicitly enable any/worker.any
and a Task must supply that shared Pool. `workerId` takes one string without Pool
demand; `worker.country` takes a country list or `{}` within its enabled Country Pool.
The composer shows an explicit sample and never infers a function from Task supply. The browser validates the
envelope and JSON bounds: non-null root, at most 100 members per container,
container depth 8 and 64 KiB of serialized input. Matching owns local parameter
semantics, normalization and Group enablement. Calls go
through Kernel scheduling; the browser does not query the index or infer
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

Lazy business pages import product-specific Element Plus components themselves.
Messages console tests register only the production shell's component set and
submit through the visible button, so global test registration cannot hide a
missing production form or pagination component.

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
