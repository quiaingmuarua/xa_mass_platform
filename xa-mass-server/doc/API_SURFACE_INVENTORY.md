# Server API Surface Convergence Inventory

Status: current route-category owner truth for `xa-mass-server` API surfaces.

The implementation roadmap has been archived. Use this inventory, current
server code, and `xa-mass-server/doc/INTERNAL_API_REFERENCE.md` as current API
surface truth.

Current code snapshot date: 2026-06-04.

This inventory is the route-category owner truth used by server route boundary
guards. Keep route rows machine-readable: `Method` and `Route` must stay in the
first two table columns under `Route Inventory`.

## API-0 Required Output

The following decisions are required before API-1/API-2 execution:

- `GET /api/v1/tasks` remains `public-sdk-read` for now because `TaskClient.list`
  and the frontend task table both call it. It is bounded by query filters and
  list limits, and API-5 owns any later narrowing.
- Console diagnostics remain under `/api/v1/runtime/**` for now, but only as
  `console-diagnostics` with operator permission. API-3 bounded response
  payloads for session/worker/state/command list diagnostics. The current
  embedded diagnostics/query interfaces still read live owner lists before the
  server response window is applied; replacing those with paged owner reads is a
  future diagnostics-interface hardening item, not public API truth.
- Worker command list/get are current operator diagnostics and stay
  `console-diagnostics` until API-4 proves a durable command/history read model
  or removes them.
- `/internal/v1/debug/task-invocations:sync` is `internal-debug` and is
  operator-only. It is not public SDK ingress.

Deferred decisions:

- `GET /api/v1/tasks/{taskId}` remains `public-sdk-read` for now because
  `TaskClient.get` and frontend detail call it. API-5 owns whether to split a
  richer console detail from shell-only public detail.

## Route Inventory

| Method | Route | Controller | Category | Auth Mode | Target Owner | Value / Performance | Current Callers | Target |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| GET | /api/v1/auth/config | AuthController | console-diagnostics | public auth config | server auth/mode discovery | keep; non-sensitive mode flags only | frontend | keep |
| POST | /api/v1/auth/login | AuthController | operator-command | public credential exchange | server auth/session | keep; bounded login; sets HttpOnly session cookie and returns CSRF token | frontend | keep |
| GET | /api/v1/auth/me | AuthController | console-diagnostics | operator auth-only | server auth/session | keep; bounded principal read | frontend | keep |
| POST | /api/v1/auth/logout | AuthController | operator-command | operator auth-only + CSRF in session mode | server auth/session | keep; bounded operator session command | frontend | keep |
| GET | /api/v1/submitters/me | CurrentSubmitterController | public-sdk-read | SDK credential bypass or TASK_VIEW | submitter credential owner | keep; bounded current-principal read | frontend, SDK users | keep |
| GET | /api/v1/submitters/me/usage | ApiUsageController | public-sdk-read | SDK credential bypass only | API usage owner | keep; bounded current credential usage | frontend | keep |
| POST | /api/v1/submitter-sessions | SubmitterViewerSessionController | public-sdk-ingress | SDK credential bypass only | submitter viewer session owner | keep; bounded session create | frontend | keep |
| GET | /api/v1/submitter-sessions/me | SubmitterViewerSessionController | public-sdk-read | SDK credential bypass only | submitter viewer session owner | keep; bounded session read | frontend | keep |
| POST | /api/v1/submitter-sessions:logout | SubmitterViewerSessionController | operator-command | SDK credential bypass only | submitter viewer session owner | keep; bounded logout command | frontend | keep |
| GET | /api/v1/projects | ProjectApiController | public-sdk-read | SDK-or-operator route | control-plane catalog | keep; bounded metadata list | frontend, SDK users | keep |
| GET | /api/v1/projects/{projectCode} | ProjectApiController | public-sdk-read | SDK-or-operator route | control-plane catalog | keep; bounded metadata read | frontend, SDK users | keep |
| GET | /api/v1/projects/{projectCode}/events | ProjectApiController | public-sdk-read | SDK-or-operator route | control-plane catalog | keep; bounded metadata read | frontend, SDK users | keep |
| GET | /api/v1/projects/{projectCode}/submitters | ProjectApiController | public-sdk-read | SDK-or-operator route | control-plane catalog | keep; bounded metadata read | frontend | keep |
| GET | /api/v1/catalog/events | CatalogController | public-sdk-read | SDK credential bypass | control-plane catalog | keep; bounded catalog read | frontend, SDK users | keep |
| GET | /api/v1/catalog/events/{eventCode} | CatalogController | public-sdk-read | SDK credential bypass | control-plane catalog | keep; bounded catalog read | frontend, SDK users | keep |
| GET | /api/v1/catalog/event-capabilities | CatalogController | public-sdk-read | SDK credential bypass | control-plane catalog | keep; bounded capability read | frontend, SDK users | keep |
| GET | /api/v1/catalog/worker-capabilities | CatalogController | console-diagnostics | SDK credential bypass | capability diagnostics | bound; joins declaration/runtime/transport facts | frontend | keep as diagnostics |
| GET | /api/v1/catalog/worker-group-capabilities | CatalogController | public-sdk-read | SDK credential bypass | WorkerGroup capability owner | keep; bounded capability read | frontend, SDK users | keep |
| GET | /api/v1/tasks | TaskApiController | public-sdk-read | SDK credential bypass or TASK_VIEW | task shell owner | bounded list window and filters | TaskClient, frontend | keep; future split needs caller decision |
| POST | /api/v1/tasks | TaskApiController | public-sdk-ingress | SDK credential bypass or TASK_CREATE | task shell owner | keep; shell create intent | TaskClient, frontend | keep; response uses shell-only task object |
| GET | /api/v1/tasks/{taskId} | TaskApiController | public-sdk-read | SDK credential bypass or TASK_VIEW | task shell + current-state composite read | keep source-labeled detail; no item payload snapshots | TaskClient, frontend | keep; future console-detail split needs caller decision |
| PATCH | /api/v1/tasks/{taskId} | TaskApiController | operator-command | TASK_EDIT | task shell owner | keep only bounded pre-dispatch definition patch | TaskClient advanced path | keep bounded |
| POST | /api/v1/tasks/{taskId}/items | TaskApiController | public-sdk-ingress | SDK credential bypass or TASK_EDIT | task item ingest owner | keep; bounded item ingress | TaskClient, frontend | keep |
| POST | /api/v1/tasks/{taskId}/items:sync | TaskApiController | public-sdk-ingress | SDK credential bypass or TASK_EDIT | task item ingest/result wait owner | resolved event contract fixed; keep bounded sync ingest | TaskClient | keep |
| POST | /api/v1/tasks/{taskId}/commands | TaskApiController | operator-command | operator-only | task lifecycle command owner | keep; command intent, not state CRUD | frontend | keep operator-only |
| GET | /api/v1/tasks/{taskId}/results | TaskApiController | public-sdk-read | SDK credential bypass or TASK_VIEW | task result runtime owner | keep; bounded result window | TaskClient | keep |
| GET | /api/v1/tasks/{taskId}/results/archive | TaskApiController | public-sdk-read | SDK credential bypass or TASK_VIEW | task result runtime/archive owner | keep; bounded manifest | TaskClient | keep |
| GET | /api/v1/tasks/{taskId}/results/archive/content | TaskApiController | public-sdk-read | SDK credential bypass or TASK_VIEW | task result runtime/archive owner | keep; streamed archive content | TaskClient | keep |
| POST | /api/v1/tasks/{taskId}/items/{messageId}/stages/{stageName}/evidence | TaskApiController | public-sdk-ingress | SDK credential bypass or TASK_EDIT | stage evidence owner | keep; bounded evidence write | server callers, SDK users | keep |
| GET | /api/v1/tasks/{taskId}/items/{messageId}/stages | TaskApiController | public-sdk-read | SDK credential bypass or TASK_VIEW | stage evidence owner | keep; bounded projection read | server callers | keep |
| GET | /api/v1/tasks/{taskId}/items/{messageId}/stages/{stageName} | TaskApiController | public-sdk-read | SDK credential bypass or TASK_VIEW | stage evidence owner | keep; bounded projection read | server callers | keep |
| GET | /internal/v1/review/tasks/{taskId} | InternalTaskReviewController | console-diagnostics | TASK_VIEW | server review/export owner | keep; bounded review read model | frontend | keep internal |
| GET | /internal/v1/review/tasks/{taskId}/seed-export | InternalTaskReviewController | console-diagnostics | TASK_VIEW | server review/export owner | keep; bounded export | frontend | keep internal |
| GET | /internal/v1/review/tasks/{taskId}/result-export | InternalTaskReviewController | console-diagnostics | TASK_VIEW | server review/export owner | keep; bounded export | frontend | keep internal |
| POST | /internal/v1/debug/task-invocations:sync | InternalDebugTaskInvocationController | internal-debug | operator-only | internal debug owner | internalize; creates/seals/approves/waits | frontend debug action | keep operator-only |
| GET | /api/v1/runtime/config/projects | GlobalConfigController | console-diagnostics | WORKER_VIEW | console config diagnostics | keep; bounded config list | frontend | keep console-only |
| GET | /api/v1/runtime/queues | QueueController | console-diagnostics | WORKER_VIEW | transport diagnostics | aggregate/detail map; prefer `/metrics` for cheap polling | server docs/tests | keep diagnostics |
| GET | /api/v1/runtime/queues/metrics | QueueController | console-diagnostics | WORKER_VIEW | transport diagnostics | keep; prefer aggregate metrics | server docs/tests | keep diagnostics |
| GET | /api/v1/runtime/sessions | SessionController | console-diagnostics | WORKER_VIEW | transport diagnostics | response-bound by `limit` default 200 max 500; underlying live list still scans current sessions | server docs/tests | keep diagnostics |
| GET | /api/v1/runtime/sessions:stats | SessionController | console-diagnostics | WORKER_VIEW | transport diagnostics | keep; aggregate stats | server docs/tests | keep diagnostics |
| GET | /api/v1/runtime/workers | WorkerApiController | console-diagnostics | WORKER_VIEW | worker/resource diagnostics | response-bound by `limit` default 200 max 500; joins worker/runtime/transport state | frontend | keep diagnostics |
| GET | /api/v1/runtime/workers/{workerId}/state | WorkerApiController | console-diagnostics | WORKER_VIEW | worker state diagnostics | keep; bounded read projection | tests, console candidate | keep diagnostics |
| GET | /api/v1/runtime/workers/states | WorkerApiController | console-diagnostics | WORKER_VIEW | worker state diagnostics | response-bound by `limit` default 200 max 500 | server docs/tests | keep diagnostics |
| POST | /api/v1/runtime/workers/{workerId}/commands | WorkerApiController | operator-command | WORKER_EDIT | worker command owner | keep if command intent is product-valued | server E2E | keep operator command |
| GET | /api/v1/runtime/workers/{workerId}/commands | WorkerApiController | console-diagnostics | WORKER_VIEW | worker command diagnostics | response-bound by `limit` default 200 max 500; keep for current operator workflow | server docs/tests | keep diagnostics |
| GET | /api/v1/runtime/workers/commands/{commandId} | WorkerApiController | console-diagnostics | WORKER_VIEW | worker command diagnostics | keep for current operator workflow | server E2E | keep diagnostics |
| GET | /api/v1/admin/rules | RuleApiController | console-diagnostics | RULE_VIEW | scheduling/rule diagnostics | keep; route owner is admin not runtime | server route, frontend drift target | retarget frontend |
| GET | /api/v1/admin/rules/meta | RuleApiController | console-diagnostics | RULE_VIEW | scheduling/rule diagnostics | keep; route owner is admin not runtime | server route, frontend drift target | retarget frontend |
| GET | /api/v1/users | IdentityAccessController | console-diagnostics | USER_VIEW | IAM owner | keep; operator IAM read | frontend | keep |
| POST | /api/v1/users | IdentityAccessController | operator-command | USER_EDIT | IAM owner | keep; operator IAM command | frontend | keep |
| GET | /api/v1/users/{userId} | IdentityAccessController | console-diagnostics | USER_VIEW | IAM owner | keep; operator IAM read | frontend | keep |
| PATCH | /api/v1/users/{userId} | IdentityAccessController | operator-command | USER_EDIT | IAM owner | keep; bounded IAM patch | frontend | keep |
| POST | /api/v1/users/{userId}/roles/{roleId} | IdentityAccessController | operator-command | USER_EDIT | IAM owner | keep; role binding command | frontend | keep |
| DELETE | /api/v1/users/{userId}/roles/{roleId} | IdentityAccessController | operator-command | USER_EDIT | IAM owner | keep; role binding command | frontend | keep |
| GET | /api/v1/roles | IdentityAccessController | console-diagnostics | ROLE_VIEW | IAM owner | keep; operator IAM read | frontend | keep |
| POST | /api/v1/roles | IdentityAccessController | operator-command | ROLE_EDIT | IAM owner | keep; role create command | frontend | keep |
| GET | /api/v1/roles/{roleId} | IdentityAccessController | console-diagnostics | ROLE_VIEW | IAM owner | keep; operator IAM read | frontend | keep |
| PATCH | /api/v1/roles/{roleId} | IdentityAccessController | operator-command | ROLE_EDIT | IAM owner | keep; bounded role patch | frontend | keep |
| GET | /api/v1/permissions | IdentityAccessController | console-diagnostics | ROLE_VIEW | IAM owner | keep; operator IAM read | frontend | keep |
| GET | /api/v1/api-keys | ApiKeyController | console-diagnostics | API_KEY_VIEW | API key owner | keep; operator read | frontend | keep |
| POST | /api/v1/api-keys | ApiKeyController | operator-command | API_KEY_APPROVE | API key owner | keep; operator create | frontend | keep |
| GET | /api/v1/api-keys/{keyId} | ApiKeyController | console-diagnostics | API_KEY_VIEW | API key owner | keep; operator read | frontend | keep |
| POST | /api/v1/api-keys/{keyId}:revoke | ApiKeyController | operator-command | API_KEY_REVOKE | API key owner | keep; revoke command | frontend | keep |
| GET | /api/v1/api-keys/{keyId}/usage | ApiUsageController | console-diagnostics | API_USAGE_VIEW | API usage owner | keep; bounded usage read | frontend | keep |
| GET | /api/v1/api-key-applications | ApiKeyApplicationController | console-diagnostics | API_KEY_VIEW | API key owner | keep; operator read | frontend | keep |
| POST | /api/v1/api-key-applications | ApiKeyApplicationController | public-sdk-ingress | API_KEY_APPLY | API key owner | keep; application ingress | frontend | keep |
| GET | /api/v1/api-key-applications/{applicationId} | ApiKeyApplicationController | console-diagnostics | API_KEY_VIEW | API key owner | keep; operator read | frontend | keep |
| POST | /api/v1/api-key-applications/{applicationId}:approve | ApiKeyApplicationController | operator-command | API_KEY_APPROVE | API key owner | keep; approval command | frontend | keep |
| POST | /api/v1/api-key-applications/{applicationId}:reject | ApiKeyApplicationController | operator-command | API_KEY_APPROVE | API key owner | keep; rejection command | frontend | keep |
| POST | /worker-api/v1/adapter-nodes | ExternalWorkerApiController | public-sdk-ingress | worker credential | external worker data-plane | keep; external registration | Java SDK, scenario launcher | keep |
| POST | /worker-api/v1/worker-groups | ExternalWorkerApiController | public-sdk-ingress | worker credential | external worker data-plane | keep; capability declaration | Java SDK, scenario launcher | keep |
| POST | /worker-api/v1/node-group-bindings | ExternalWorkerApiController | public-sdk-ingress | worker credential | external worker data-plane | keep; topology binding | Java SDK, scenario launcher | keep |
| POST | /worker-api/v1/workers | ExternalWorkerApiController | public-sdk-ingress | worker credential | external worker data-plane | keep; worker registration | Java SDK, worker sessions | keep |
| POST | /worker-api/v1/workers/{workerId}:online | ExternalWorkerApiController | public-sdk-ingress | worker credential | external worker data-plane | keep; worker presence | Java SDK worker sessions | keep |
| POST | /worker-api/v1/workers/{workerId}:heartbeat | ExternalWorkerApiController | public-sdk-ingress | worker credential | external worker data-plane | keep; worker presence | Java SDK worker sessions | keep |
| POST | /worker-api/v1/workers/{workerId}:offline | ExternalWorkerApiController | public-sdk-ingress | worker credential | external worker data-plane | keep; worker presence | Java SDK worker sessions | keep |
| POST | /worker-api/v1/workers/{workerId}:poll | ExternalWorkerApiController | public-sdk-ingress | worker credential | external worker data-plane | keep; external polling | Java SDK worker sessions | keep |
| POST | /worker-api/v1/workers/{workerId}:submit-result | ExternalWorkerApiController | public-sdk-ingress | worker credential | external worker data-plane | keep; result submit | Java SDK worker sessions | keep |
| POST | /worker-api/v1/workers/{workerId}/commands:poll | ExternalWorkerApiController | public-sdk-ingress | worker credential | external worker data-plane | keep; command delivery poll | Java SDK | keep |
| POST | /worker-api/v1/workers/{workerId}:report-capability | ExternalWorkerApiController | public-sdk-ingress | worker credential | external worker data-plane | keep; worker self-report | Java SDK worker sessions | keep |
| POST | /worker-api/v1/workers/{workerId}:report-state | ExternalWorkerApiController | public-sdk-ingress | worker credential | external worker data-plane | keep; worker self-report | Java SDK worker sessions | keep |
| POST | /worker-api/v1/workers/{workerId}/commands/{commandId}:ack | ExternalWorkerApiController | public-sdk-ingress | worker credential | external worker data-plane | keep; command ack | Java SDK | keep |

## Frontend Caller Drift

| Caller | Current Route | Current Server Route | Target |
| --- | --- | --- | --- |
| `frontend/src/api/rules.real.ts` | `/api/v1/runtime/rules` | `/api/v1/admin/rules` | fixed; caller now uses admin rule route |
| `frontend/src/api/rules.real.ts` | `/api/v1/runtime/rules/meta` | `/api/v1/admin/rules/meta` | fixed; caller now uses admin rule route |

## Duplicate Or High-Risk Shapes

| Shape | Current Owner | Classification | Target |
| --- | --- | --- | --- |
| `POST /api/v1/runtime/workers/{workerId}/capability-reports` | removed from `WorkerApiController` | duplicate worker self-report write | use `/worker-api/v1/workers/{workerId}:report-capability` |
| `POST /api/v1/runtime/workers/{workerId}/state-reports` | removed from `WorkerApiController` | duplicate worker self-report write | use `/worker-api/v1/workers/{workerId}:report-state` |
| `POST /api/v1/runtime/workers/{workerId}/commands/{commandId}/ack` | removed from `WorkerApiController` | duplicate worker data-plane ack | use `/worker-api/v1/workers/{workerId}/commands/{commandId}:ack` |
| `/internal/v1/debug/task-invocations:sync` SDK auth bypass | `ApiRouteAuthorizationCatalog` | fixed by operator-only route catalog and controller-side SDK credential rejection | keep guarded |
| `/api/v1/runtime/**` list/detail snapshots | runtime diagnostics controllers | live runtime read cost risk | kept as operator/console diagnostics only; list responses are windowed, while owner SDK pagination remains deferred |

## Model Shape Notes

- `TaskApiContracts` is the current task public contract owner. Task create
  returns `ApiTaskShell`; task list/detail currently return source-labeled
  `ApiTask` composite rows. Do not add more all-purpose task fields without
  source classification and caller evidence.
- Runtime diagnostics may use response maps for now. They are diagnostics, not
  durable history or public domain objects. Any future unbounded scan removal
  belongs in the diagnostics owner interface rather than public route expansion.
- Worker report request DTOs under server runtime routes are duplicate ingress
  DTOs relative to the public worker contract and should not be expanded before
  API-4.
