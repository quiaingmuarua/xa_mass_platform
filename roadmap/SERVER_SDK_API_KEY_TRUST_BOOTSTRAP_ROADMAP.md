# Server SDK API-Key Trust Bootstrap Roadmap

Status: draft for implementation review.

## Summary

This roadmap hardens the server + SDK bootstrap path after the submitter
elimination and seed taxonomy decisions.

The problem is not that API keys are seedable. API keys are seedable because
external Java SDK task producers and external workers need credentials before
they can call the server. The problem is that independent concerns are still
easy to collapse into server profile labels. Memory runtime is a valid
deployment choice for immediate/RPC-style workloads; it is not automatically a
toy mode or an auth bypass mode. Fixture-header auth can make authorization
look healthy while session auth, CSRF, role, seed, and API-key checks fail
later.

Target outcome:

```text
control-plane seed/import
  -> operator credential / role / API-key bootstrap
  -> explicit auth trust mode readiness
  -> Java SDK task producer uses task API key
  -> Java SDK worker uses worker API key
  -> no fixture auth required for SDK or worker-api proof
```

This roadmap does not change scheduling, worker matching, runtime truth, or
task lifecycle semantics.

## Current Code Observations

- Server seed/import currently supports project/event catalog, rules,
  operator credentials, and API keys.
- API-key raw secrets are import material. Durable API-key truth is stored as
  credential hash, prefix, scopes, permissions, owner, status, expiry, and
  metadata.
- The checked-in sample scenario seed contains `devOnly=true` API-key raw
  secrets. Persistent/fail-closed import rejects dev-only raw secrets.
- The current `durable-local` Spring profile defaults to session operator auth,
  SQLite control-plane storage, and Redis runtime/transport; treat this as one
  assembled shape across separate
  `runtimeInfra`, `authTrustMode`, and `seedSource` axes, not as a different API
  contract.
- The current `memory-local` Spring profile defaults to explicit fixture-header
  operator auth implementation (`dev-header`), H2 control-plane storage, and
  memory runtime/transport; treat this as a local fixture assembly, not as SDK
  or worker-api trust proof.
- `ApiRouteAuthorizationCatalog` allows SDK credential bypass only on specific
  SDK routes such as task create/append/read, catalog read, API-key viewer
  session routes, and current API-key self routes.
- `/api/v1/tasks/{taskId}/commands` remains operator-auth-only; seal/approve
  is not a task-producer SDK action.
- Worker external routes under `/worker-api/v1/**` use worker API-key
  credential scenarios such as `WORKER_REGISTER`, `WORKER_POLL`,
  `WORKER_SUBMIT_RESULT`, `WORKER_REPORT_CAPABILITY`, and
  `WORKER_REPORT_STATE`.
- `ControlPlaneSeedImportIntegrationTest` already proves seed/import does not
  create task shells, workers, or WorkerGroups.
- Some controller and integration tests still use fixture-header
  implementation (`dev-header`), direct service fixtures, or MockMvc-style
  setup. Those tests are useful for narrow behavior, but they are not proof
  that the trusted-auth lane works.

## Owner Decision

API-key bootstrap is a `xa-mass-server` control-plane concern.

SDK and integrations consume API keys; they do not own credential lifecycle,
secret storage, operator approval, seed import, or auth-trust readiness.

WorkerGroup, AdapterNode, NodeGroupBinding, Worker, task shells, task items,
worker state, worker capability reports, runtime queues, leases, dispatch
gates, assignments, results, trace rows, and usage rows must not become seed
truth.

Do not use profile names as auth semantics. Treat environment assembly as four
independent axes:

| Axis | Values | Owns | Must not own |
| --- | --- | --- | --- |
| `runtimeInfra` | `memory`, `persistent` | storage/runtime backend selection such as memory/H2/SQLite/Redis | API contract, auth bypass, seedability, production readiness |
| `authTrustMode` | `fixture-header`, `session-fail-closed`, `disabled` | operator-console authentication mode | SDK or worker-api credential bypass |
| `seedSource` | `sample-dev-only`, `external-controlled`, `none` | catalog/rule/credential input source and raw-secret policy | runtime actor/workload truth |
| `testLane` | `support`, `boundary`, `trusted-auth` | proof strength and fixture budget | production behavior itself |

The existing Spring profiles assemble convenient defaults from these axes, but
the axes are the owner vocabulary. A local fixture is allowed
only when it is explicit as `fixture-header` or support coverage. It must never
be treated as SDK trust proof.

Memory infrastructure can be a legitimate runtime deployment mode for
immediate/RPC-style tasks where restart persistence is not required. It still
uses the same public API, API-key, operator auth, seedability, and SDK/worker
registration rules. Do not use `runtimeInfra=memory` as a reason to allow
fixture-header auth or skip API-key proof.

The owner distinction is:

- `runtimeInfra=memory` is about durability and runtime backend cost.
- `authTrustMode=fixture-header` is a test/operator fixture.
- `authTrustMode=session-fail-closed` is the production trust requirement.
- `testLane=trusted-auth` can run on memory or persistent infra, as long as it
  uses real API-key credentials, operator session auth, CSRF, route
  authorization, and seed/import validation.

These axes must not change:

- public API contracts
- which resources are seedable
- whether SDK/worker-api calls require API-key credentials
- whether operator lifecycle commands require operator authorization

## Target Taxonomy

| Resource | Bootstrap path | Notes |
| --- | --- | --- |
| project/event catalog | seed/import or operator control-plane setup | Stable control-plane config. |
| rules/policy config | seed/import or operator control-plane setup | Resolved policy is runtime output, not seed data. |
| operator credentials | seed/import or operator control-plane setup | Password hash only. No plaintext password seed. |
| API keys | seed/import, operator API, or application approval | Raw secret is import/create material only. |
| task create/append/read | Java SDK or task API with task API key | Producer path. |
| seal/approve/pause/resume/cancel | operator/server-control API | Not ordinary task-producer SDK behavior. |
| WorkerGroup/AdapterNode/NodeGroupBinding/Worker | Java SDK or worker API with worker API key | Runtime actor registration evidence. |
| worker online/heartbeat/poll/result/report | Java SDK or worker API with worker API key | Runtime actor behavior. |
| runtime queues/leases/results/occupancy/assignment | runtime owners only | Forbidden seed. |
| audit/usage/trace/session/CSRF | emitted/generated only | Seed/import may emit audit; it must not seed audit rows. |

## Non-Goals

1. No submitter compatibility restoration.
2. No new dev-only bootstrap HTTP API.
3. No WorkerGroup seed support in this roadmap.
4. No task/worker runtime state seed.
5. No change to worker matching or scheduling policy.
6. No change that lets task API keys call operator lifecycle commands.
7. No public registration, password reset, OAuth, or full API-key application
   workflow redesign.

## Core Rules

1. API key is the only external task-producer and worker-api credential model.
2. SDK task producers must use task API keys for create, append, and read.
3. SDK/external workers must use worker API keys for worker registration,
   presence, poll, result, capability, state, and command ack paths.
4. Operator session routes must pass CSRF in session mode for mutating routes.
5. `fixture-header` is a local operator-console fixture only; it is not SDK
   proof and not worker-api proof.
6. Trusted-auth proof must exercise seeded operator credentials, seeded API
   keys, session login, CSRF, task API key calls, and worker API key calls.
7. A test that bypasses `ApiAuthInterceptor`, `ApiRouteAuthorizationCatalog`,
   `ApiAuthorizationService`, or credential stores is support coverage only,
   not trusted-auth proof.
8. Sample/dev raw secrets may be checked in only when marked dev-only and
   rejected by persistent/fail-closed seed import.
9. Production API-key seed files may exist, but they must be deployment-owned
   external inputs, not hard-coded product defaults.
10. Seed/import may initialize API keys, but task and worker behavior must
    still be performed through SDK/API calls.

## Test Classification

This roadmap must not turn every controller or service test into a trusted-auth
black-box test. That would make the suite slower and noisier without improving
the main trust proof.

Use three explicit test classes:

| Test class | Allowed fixtures | Counts as trusted-auth proof? | Purpose |
| --- | --- | --- | --- |
| Support coverage | fixture-header, direct service construction, MockMvc with narrow setup, in-memory stores | No | Cheap coverage for controller/service behavior and edge cases. |
| Boundary contract proof | real route catalog/interceptor/auth service, API-key credential stores, worker/task SDK clients, often memory infra | Partially | Proves route, DTO, auth, and SDK contracts without requiring persistent infra. |
| Trusted-auth proof | session auth, CSRF, seeded operator credential, seeded task/worker API keys, Spring context, memory or persistent infra | Yes | Proves startup/auth/seed/API-key paths that must not be hidden by local fixtures. |

Execution rule:

1. Do not rewrite all support tests.
2. Add or upgrade a small number of trusted-auth proofs for the main paths.
3. Rename or tag fixture-header/direct-fixture tests when their names imply
   trusted auth.
4. If a support test is the only proof for a production-sensitive route, add a
   boundary or trusted-auth proof instead of making every local test
   trusted-auth.
5. Keep trusted-auth proofs focused on representative high-value paths:
   operator API-key create, task API-key create/append/read, worker API-key
   register/operate, and seed/import safety.

## Phase Plan

### AKT-0: Inventory Current Trust Paths

Goal: make existing bypasses and proof gaps visible before modifying behavior.

Scope:

1. Inventory tests that use fixture-header as the only auth proof for operator
   mutations.
2. Inventory tests that create task/worker state through embedded service calls
   instead of SDK/API calls.
3. Inventory sample and scenario launcher API-key assumptions.
4. Inventory seed/import paths that create API keys or operator credentials.
5. Inventory routes that use `SDK_CREDENTIAL_BYPASS`,
   `SDK_OR_OPERATOR_ROUTE`, and `OPERATOR_AUTH_ONLY`.
6. Classify each relevant test as support coverage, boundary contract proof, or
   trusted-auth proof.

Acceptance:

1. A short inventory table exists in this roadmap or a sibling inventory.
2. Every bypass is classified as allowed support fixture, migration residue, or
   must-fix gap.
3. No requirement exists to convert all fixture-header or MockMvc tests to
   trusted-auth tests.
4. No behavior change.

### AKT-1: API-Key Seed Contract Proof

Goal: prove API-key seed is allowed only as credential bootstrap and does not
seed runtime actor or workload truth.

Scope:

1. Strengthen seed/import tests for task API key and worker API key seed.
2. Prove raw secret is accepted as import material but not exposed by list/get
   credential read models.
3. Prove persistent/fail-closed import rejects `devOnly=true` raw secrets.
4. Prove persistent/fail-closed import accepts deployment-owned non-dev API-key
   seed input.
5. Keep existing proof that seed/import creates no task shells, workers, or
   WorkerGroups.

Acceptance:

1. Task API key seeded by import can authenticate task create/append/read.
2. Worker API key seeded by import can authenticate worker registration.
3. Seed/import does not create task, worker, WorkerGroup, runtime queue,
   result, usage, trace, session, or CSRF truth.
4. Read APIs never return raw API-key secret after import/create.

### AKT-2: Trusted-Auth Operator Auth And CSRF Proof

Goal: stop permission/CSRF regressions from hiding behind fixture tests.

Scope:

1. Add a trusted-auth server context proof with
   `mass.auth.operator.mode=session`.
2. Seed or prepare an active operator credential.
3. Login through `/api/v1/auth/login`.
4. Use `/api/v1/auth/me` to obtain the current permission snapshot and CSRF.
5. Create an API key through the operator route using cookie + CSRF.
6. Prove missing CSRF fails for mutating operator routes in session mode.
7. Prove fixture-header identity headers are rejected in session mode.

Acceptance:

1. Operator API-key create works in trusted-auth session mode with correct
   permission and CSRF.
2. The same route fails without CSRF.
3. The same route fails for an operator lacking `api-key:approve`.
4. The proof fails if default seeded roles drift and no convergence guard fixes
   them.

### AKT-3: SDK Task Producer Trusted-Auth Proof

Goal: prove Java SDK task producers do not depend on fixture auth.

Scope:

1. Start a trusted-auth server test context with explicit seed/import.
2. Use `sdk/xa-mass-java-sdk` or `integrations/xa-mass-scenario-launcher` task
   producer path.
3. Authenticate with a task API key.
4. Create a task, append items, and read task/results through SDK/API.
5. Keep seal/approve under operator/server-control proof only.

Acceptance:

1. Task API key can create and append only within its scopes.
2. Scope violation fails with a clear API-key authorization error.
3. Task API key cannot call `/api/v1/tasks/{taskId}/commands`.
4. Test does not use fixture-header or embedded service shortcuts for the
   producer path.

### AKT-4: SDK Worker Registration Trusted-Auth Proof

Goal: prove Java SDK/external worker registration does not depend on local
fixture shortcuts.

Scope:

1. Use a seeded worker API key.
2. Register WorkerGroup, AdapterNode, NodeGroupBinding, and Worker through
   worker API or Java SDK.
3. Mark worker online and report state/capability through worker API or SDK.
4. Prove task dispatch can reach the registered worker when a task exists.
5. Prove worker key binding prevents a credential for worker A from operating
   worker B.

Acceptance:

1. Worker API key can register and operate the bound worker path.
2. Worker API key cannot operate an unbound worker identity.
3. Worker registration observation rows may be emitted, but they do not restore
   runtime workers on restart.
4. No WorkerGroup, AdapterNode, NodeGroupBinding, or Worker is created by seed.

### AKT-5: Fixture-Header Convergence

Goal: keep local fixtures convenient without letting fixture auth become
alternate trust.

Scope:

1. Review all fixture-header usage in tests and samples.
2. Keep fixture-header only for operator-console support tests where the
   subject is not auth/session behavior.
3. Convert SDK and worker-api proof tests to API-key credentials.
4. Add guards that any non-support trust lane, including memory-backed
   deployments, cannot enable fixture-header without a deliberately unsafe
   override.
5. Add docs warning that memory/local convenience is not an alternate public
   API or auth contract.
6. Do not rewrite low-level controller/service support tests unless their
   current fixture hides the specific behavior being tested.
7. Rename or tag support tests that currently read as trusted-auth proofs
   but still use fixture-header/direct fixtures.

Acceptance:

1. Main SDK task/worker integration proof does not use fixture-header.
2. Fixture-header tests are explicitly named as operator fixture tests.
3. Trusted-auth proof covers at least one mutating operator route, one task
   API-key route, and one worker API-key route.
4. No sample or scenario launcher documentation claims fixture auth is a normal
   SDK credential path.
5. Existing support coverage remains cheap; this phase does not require a
   broad suite-wide migration to trusted-auth startup.
6. Memory-backed trusted-auth proof is allowed and valuable; it still uses API
   keys and session-fail-closed auth rather than fixture-header auth.

### AKT-6: Documentation And Guard Residue

Goal: make the trust boundary durable for future agents.

Scope:

1. Update `doc/SDK_INTEGRATIONS_BOUNDARY_GUARD.md` if implementation changes
   tighten or rename taxonomy rules.
2. Update `xa-mass-server/doc/INTERNAL_API_REFERENCE.md` for auth/CSRF/API-key
   current routes and task command authorization boundaries.
3. Update `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md` to show task API
   key and worker API key paths without fixture auth.
4. Update `integrations/README.md` and scenario launcher README if launcher
   expectations change.
5. Add or tighten source guards for checked-in dev-only raw secrets,
   persistent/fail-closed seed defaults, and forbidden task/worker seed truth.

Acceptance:

1. Docs say API key seed is credential bootstrap, not runtime truth.
2. Docs say runtime infra, auth trust mode, and seed source are separate axes;
   they do not change seedability or public API contract.
3. Guards fail if persistent/fail-closed startup defaults to checked-in dev
   scenario secrets.
4. Guards fail if seed/import starts creating tasks, workers, or WorkerGroups.

## Test Plan

Preferred proof order:

1. Seed importer unit tests for API-key raw secret and devOnly behavior.
2. Server context tests for trusted-auth session auth and CSRF.
3. Java SDK task producer black-box proof against server HTTP.
4. Java SDK worker registration black-box proof against server HTTP.
5. Scenario launcher proof only after the lower-level SDK/auth proofs are
   stable.

Minimum commands will depend on the touched slice. Candidate commands:

```bash
./mvnw -q -pl xa-mass-server -am \
  -Dtest=ControlPlaneSeedImporterTest,ControlPlaneSeedImportIntegrationTest test

./mvnw -q -pl xa-mass-server -am \
  -Dtest=ApiKeyControllerTest,AuthControllerTest test

./mvnw -q -pl integrations/xa-mass-scenario-launcher -am test
```

For profile or startup wiring changes, add a Spring context or Boot-shell proof
with the relevant profile active. Direct constructor tests are not enough.

Do not use the trusted-auth test lane as the default for all tests. It should
be a small, high-signal lane that proves the trust chain end to end.
Support tests remain valid when they are clearly scoped and not cited as
trusted-auth proof.

## Risks

Risk: tests become slower if every route test uses trusted-auth startup.

Mitigation: keep narrow unit/controller tests, but label them support coverage.
Only the main trust proof must be trusted-auth.

Risk: memory runtime is treated as a dev-only toy path.

Mitigation: document `runtimeInfra=memory` as a valid deployment choice when
restart persistence is not required, and keep auth/seed/API-key rules identical
across memory and persistent infra.

Risk: API-key seed is mistaken for a general resource seed.

Mitigation: keep the seed taxonomy guard and explicit tests proving seed/import
does not create task, worker, or runtime truth.

Risk: scenario launcher becomes a privileged bootstrap tool.

Mitigation: launcher uses SDK/API calls only and assumes control-plane catalog,
rules, and API keys already exist.

Risk: task API key accidentally gains operator command power.

Mitigation: prove task command route remains operator/server-control only.

## Final Target

After this roadmap:

```text
trusted-auth lane
  -> seed/import prepares operator credential and API-key bootstrap
  -> operator session + CSRF protects operator mutations
  -> task API key proves task producer path
  -> worker API key proves worker registration/runtime path
  -> seed/import does not create task/worker/runtime truth
  -> can run on memory or persistent infra

support lane
  -> may use memory infra and fixture-header for narrow operator-console tests
  -> does not define alternate SDK or worker-api trust
```

The resulting proof should catch the class of failure where CI is green through
local fixtures while a trusted-auth server cannot create API keys, cannot pass
CSRF, or cannot authenticate SDK/worker-api calls.
