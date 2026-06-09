# Server API Contract Health Lane Inventory

Status: initial current-code inventory for
`SERVER_API_CONTRACT_HEALTH_LANE_ROADMAP.md`.

## Scope

This inventory records the current contract-health surfaces that must be
cross-checked when the platform moves toward product-ready server + SDK first
operation.

It does not replace route-level API docs. Server/OpenAPI remains the API
contract owner, SDK/public-contract modules own typed external caller surfaces,
and frontend consumes those contracts through adapters.

## Contract Surfaces

| Surface | Current Owner | Current Role | Classification | Health Target |
| --- | --- | --- | --- | --- |
| `/v3/api-docs` | `xa-mass-server` | Live OpenAPI JSON export. | generated contract source | Primary route/shape snapshot source. |
| `/doc.html` | `xa-mass-server` | Live Knife4j browser docs. | generated docs UI | Local/operator review surface; not frontend API truth. |
| `xa-mass-server/doc/INTERNAL_API_REFERENCE.md` | `xa-mass-server` | Narrative/internal API reference. | owner docs | Must not describe target state as current behavior. |
| `doc/FRONTEND_BACKEND_CONTRACT.md` | cross-owner | Server/frontend API change rules and ownership. | global contract guard | Route/DTO/auth changes must update adapters/types/tests. |
| `sdk/xa-mass-public-contract` | `sdk` | Shared public HTTP Controller DTO/constants only. | public wire DTO owner | Prevent DTO double-write between server and SDK. |
| `sdk/xa-mass-java-sdk` | `sdk` | External Java task/worker typed client/session SDK. | external SDK surface | Typed clients must match server public routes and auth. |
| `integrations/xa-mass-scenario-launcher` | `integrations` | SDK adopter for task producer and worker registration/session proof. | contract proof harness | Proves external task and worker APIs against a real server. |
| `frontend/src/api/*.real.ts` | `frontend` | Real backend adapters. | frontend consumer | Must track server route/DTO/auth changes when consumed. |
| `ServerApiFailureLoggingFilter` / `http.server.requests` | `xa-mass-server` | Failure lane and endpoint metrics. | observability support | Helps find contract health failures; not API truth. |

## Representative Route Families

| Route Family | Owner | Expected Caller | Contract Risk | Health Target |
| --- | --- | --- | --- | --- |
| `POST /api/v1/auth/login` | server | operator/session helper/frontend | cookie + CSRF + auth-mode drift | Session login smoke and docs snapshot. |
| `GET /api/v1/auth/config` | server | frontend/helper | auth-mode discovery drift | Stable frontend/helper discovery. |
| `POST /api/v1/api-keys` | server | operator/helper/frontend | `api-key:approve`, CSRF, rawSecret one-time response | Contract smoke with session cookie and CSRF. |
| `GET /api/v1/api-keys:current` | server | SDK/API-key helper | SDK credential bypass and cache validation | Contract smoke for API-key cache validation. |
| `POST /api/v1/tasks` | server + SDK | task producer SDK | task create DTO/auth/project scope drift | SDK typed task create proof. |
| `POST /api/v1/tasks/{taskId}/items` | server + SDK | task producer SDK | append eventCode scope and item payload drift | SDK task handle append proof. |
| `/worker-api/v1/worker-groups` | server + SDK | external worker SDK | event binding/project scope drift | SDK worker group declaration proof. |
| `/worker-api/v1/adapter-nodes` | server + SDK | external worker SDK | adapter/node registration drift | SDK adapter registration proof. |
| `/worker-api/v1/node-group-bindings` | server + SDK | external worker SDK | binding contract drift | SDK binding proof. |
| `/worker-api/v1/workers` | server + SDK | external worker SDK | worker credential binding drift | SDK worker registration proof. |
| `/worker-api/v1/workers/{workerId}:poll` | server + SDK | external worker session | poll auth/runtime dispatch drift | SDK session proof. |
| `/worker-api/v1/workers/{workerId}:submit-result` | server + SDK | external worker session | result submit contract drift | SDK result submit proof. |

## Related Current Owners And Active Roadmaps

| Owner Or Roadmap | Relation |
| --- | --- |
| `xa-mass-testing/README.md` platform confidence smoke | Prerequisite local readiness: clean DB, env init, task/worker credentials, scenario launcher proof. |
| `SERVER_FRONTEND_STATIC_API_DOCS_ROADMAP.md` | Owns static docs snapshot mechanics and frontend API reference presentation. |
| `xa-mass-server/README.md` API observability section | Current API failure lane and endpoint metrics used to diagnose contract failures. |
| `SUBMITTER_TO_API_KEY_CONVERGENCE_ROADMAP.md` | Owns API-key credential model convergence and current API-key routes. |
| `integrations/xa-mass-scenario-launcher/README.md` | Current task config entry used by contract-health scenario runs. |

## Current Gaps

1. There is no single API contract health lane that ties server OpenAPI,
   Java SDK typed clients, scenario-launcher proof, frontend adapters, and
   observability signals together.
2. Local scenario readiness currently covers task credentials more explicitly
   than worker credentials; worker SDK registration can still fail for
   credential/prepared-state reasons.
3. Generated OpenAPI/static docs are not yet part of an automated contract
   health proof.
4. SDK typed route ownership is guarded, but no consolidated parity check maps
   SDK typed methods to the expected server route family and auth mode.
5. Frontend adapters follow `FRONTEND_BACKEND_CONTRACT.md`, but there is no
   contract-health smoke that confirms consumed routes still match live server
   docs and auth behavior.
6. API failure logs and endpoint metrics exist as support signals, but they are
   not yet tied to an API contract health report or acceptance lane.

## Decisions To Close In ACH-0

1. Exact route family list for first health lane.
2. Whether first health proof requires local scenario worker registration, or
   whether worker runtime/session proof can be a named deferred dependency on
   local readiness.
3. OpenAPI snapshot source and comparison rule:
   - generated JSON snapshot
   - static docs output
   - route family allowlist/denylist
4. SDK parity rule:
   - source scan of typed route literals
   - integration smoke through `MassPlatform`
   - both
5. Frontend participation:
   - adapter/type drift scan only
   - or focused frontend adapter tests for consumed routes
6. Observability participation:
   - contract-health run checks `SERVER_API_FAILURE` count
   - or only records failure lane as diagnostic support

## Hard Boundary

- API contract health proves route/DTO/auth/SDK/frontend consistency; it does
  not own local DB reset, runtime scheduling correctness, or trace analytics.
- OpenAPI/static docs are generated proof artifacts, not hand-written route
  truth.
- Scenario launcher is a proof harness and SDK adopter, not SDK API owner.
