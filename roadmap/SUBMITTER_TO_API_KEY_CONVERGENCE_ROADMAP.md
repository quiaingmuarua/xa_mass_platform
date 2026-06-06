# Submitter To API-Key Elimination Roadmap

Status: draft for implementation review.

## Summary

`submitter` is a historical shortcut that currently combines too many concepts:

```text
submitter = raw credential + principal + user binding + permissions + project/event scopes
```

That was useful before the server API-key lifecycle existed. It is now the wrong
long-term abstraction. The production model should be:

```text
User
  -> API Key
      -> permissions / project scopes / event scopes
          -> PrincipalContext
              -> AuthorizationPolicy
                  -> task create / append / read
```

This roadmap does not keep submitter as a final compatibility layer. It removes
submitter as a public/runtime concept in phases, while keeping each phase
independently verifiable and avoiding a half-migrated auth system.

Final target:

1. No `SubmitterRegistration`.
2. No `SubmitterProfile`.
3. No `SubmitterOperations` / `SubmitterRegistry` production surface.
4. No `registerSubmitter(...)`, `authenticateSubmitter(...)`,
   `listSubmitters(...)`, `getSubmitter(...)`, or `hasSubmitter(...)`.
5. No `/api/v1/submitters/*`.
6. No `submitters` field in seed files.
7. API key is the only external task-submission credential model.

## Current Problem

Current SDK submitter shape owns:

- principal identity
- raw credential
- key prefix
- user binding
- permissions
- project scopes
- event scopes
- enabled state
- profile/read-model data

That creates a parallel credential truth beside the server-owned API-key system:

- `ApiKeyCredentialStore`
- `ApiKeyCredentialService`
- `ApiKeyApplicationStore`
- `ApiUsageLedgerService`
- `CredentialAuthProjectionWriter`
- `PrincipalContext`
- `AuthorizationPolicy`

The overlap is not just naming. It affects seed shape, test fixtures, SDK docs,
task API authorization, frontend submitter viewer vocabulary, and future
production auth design.

## Core Rules

1. API key is the formal external task-submission credential.
2. User owns or is granted API keys.
3. API-key permissions/scopes define authorization.
4. `PrincipalContext` is the runtime auth result.
5. Task authorization consumes `PrincipalContext`, not submitter resources.
6. Submitter is legacy vocabulary to delete, not a final compatibility concept.
7. Compatibility may exist only inside bounded migration phases.
8. Every phase must reduce submitter surface or prove API-key replacement.
9. Do not preserve submitter merely to avoid test churn.
10. Do not change engine scheduling semantics in this roadmap.

## Target Vocabulary

Use:

```text
User
API Key
API Key Credential
API Key Principal
Credential Scope
Credential Permission
Current API Key Principal
```

Remove:

```text
SubmitterRegistration
SubmitterProfile
SubmitterOperations
SubmitterRegistry
/api/v1/submitters/*
submitters seed field
```

## Non-Goals

This roadmap does not:

1. Redesign IAM roles/permissions.
2. Redesign worker credentials.
3. Redesign task authorization policy.
4. Migrate historical DB data.
5. Change task/worker scheduling.
6. Introduce billing/quota.
7. Store plaintext production credentials.
8. Preserve old submitter API as a final product surface.

## Phase Plan

### SAK-C0: Inventory And Freeze

Goal: freeze submitter and map all removal work.

Scope:

1. Inventory all usages of:
   - `SubmitterRegistration`
   - `SubmitterProfile`
   - `SubmitterOperations`
   - `SubmitterRegistry`
   - `registerSubmitter(...)`
   - `authenticateSubmitter(...)`
   - `listSubmitters(...)`
   - `getSubmitter(...)`
   - `/api/v1/submitters/*`
   - `submitters` seed fields
2. Classify each call site:
   - SDK public surface
   - server auth path
   - sample/dev bootstrap
   - frontend/read model
   - test fixture
3. Add docs/source comments marking submitter as frozen legacy.
4. Add guard note: no new production feature may depend on submitter.

Acceptance:

1. Full call-site inventory exists.
2. No behavior change.
3. New submitter feature additions are explicitly blocked by docs/guard.

### SAK-C1: Principal-First Server Convergence

Goal: remove submitter vocabulary from server authorization internals before
changing external APIs.

Scope:

1. Server task create/read/append paths should talk in terms of
   `PrincipalContext`, API key, credential, and authorization scenario.
2. Rename server-only helpers/tests from submitter to API-key principal where
   they do not need legacy SDK types.
3. Keep current behavior, but ensure task authorization no longer requires
   submitter resource records.
4. Keep `/api/v1/submitters/me` temporarily only because no replacement endpoint
   exists yet.

Acceptance:

1. Task authorization uses `PrincipalContext` language.
2. Existing behavior remains green.
3. Submitter usage count in server production code is lower than before.

### SAK-C2: API-Key Seed Shape

Goal: replace sample `submitters` seed with API-key seed.

Target sample seed shape:

```json
{
  "apiKeys": [
    {
      "principalId": "public-probe-runner",
      "createdForUserId": "ops-admin",
      "rawSecret": "public-probe-key",
      "permissions": ["task:create", "task:view"],
      "projectScopes": ["publicProbe"],
      "eventScopes": ["probe.http.status"],
      "attributes": {
        "label": "Public Probe Runner"
      }
    }
  ]
}
```

Scope:

1. Add API-key seed support to control-plane seed importer or scenario launcher.
2. Migrate sample seed files from `submitters` to `apiKeys`.
3. Keep `submitters` parsing only for one transition phase if needed, but mark
   it deprecated and test that new sample uses `apiKeys`.
4. Make production docs explicit: checked-in sample secrets are local/dev only.

Acceptance:

1. Clean local quick-start can create sample API keys.
2. Sample tasks use API-key credentials.
3. No new seed file requires `submitters`.

### SAK-M1: API-Key Auth Proof Becomes Mainline

Goal: prove API-key lifecycle covers all external task submission flows.

Scope:

1. Create API key through server lifecycle or seed.
2. Use that API key to:
   - create task
   - append task items
   - seal/approve where allowed
   - read owned task
   - read results
3. Prove denial for:
   - out-of-project task create
   - out-of-event append
   - task owned by another principal
   - missing permission
4. Prefer API-key lifecycle tests over submitter tests.

Acceptance:

1. Main server E2E task credential proof uses API-key lifecycle.
2. Submitter registry is not required for mainline task API proof.
3. Failure messages still identify authorization reason clearly.

### SAK-M2: SDK API-Key Resource Surface

Goal: replace SDK submitter resource operations with API-key resource
operations.

Scope:

1. Add SDK-facing API-key registration/read operations if missing.
2. Cover use cases currently served by:
   - `registerSubmitter(...)`
   - `authenticateSubmitter(...)`
   - `listSubmitters(...)`
   - `getSubmitter(...)`
   - `hasSubmitter(...)`
3. New read model must not expose raw credentials.
4. Keep task SDK credential usage based on `X-Mass-Api-Key` / bearer token.
5. Do not introduce a pass-through submitter alias under a new name.

Acceptance:

1. SDK samples can use API-key vocabulary only.
2. Existing submitter SDK tests have API-key equivalents.
3. New API-key SDK tests cover multiple keys per user and bounded scopes.

### SAK-M3: Current Credential Principal Endpoint

Goal: replace `/api/v1/submitters/me`.

Scope:

1. Add one clear endpoint:

```text
GET /api/v1/api-keys/me
```

2. Response should include:
   - principal id/type
   - user id
   - key id or key prefix when available
   - permissions
   - project scopes
   - event scopes
   - attributes
3. Update frontend submitter viewer to API-key credential terminology.
4. Keep `/api/v1/submitters/me` only until the frontend and tests move.

Acceptance:

1. Frontend no longer needs `/api/v1/submitters/me`.
2. API-key current-principal endpoint is covered by tests.
3. `/api/v1/submitters/me` has no first-party caller after this phase.

### SAK-M4: Remove Submitter From Samples And Tests

Goal: migrate samples/tests before deleting public types.

Scope:

1. Java scenario launcher uses `apiKeys`, not `submitters`.
2. Node launcher uses API-key seed/registration path.
3. Server E2E helper names use API-key terminology.
4. SDK README examples use API-key operations.
5. Tests that only existed to preserve submitter compatibility are deleted or
   rewritten as API-key tests.

Acceptance:

1. No sample config contains `submitters`.
2. No current first-party sample calls `registerSubmitter(...)`.
3. Test count does not grow by duplicating old and new credential paths.

### SAK-D1: Delete Server Submitter Endpoint

Goal: remove public HTTP submitter surface.

Scope:

1. Delete `/api/v1/submitters/me`.
2. Delete or rename `CurrentSubmitterController`.
3. Remove submitter-viewer backend API calls.
4. Ensure API failure logging and route catalog no longer mention submitters
   except archived docs.

Acceptance:

1. `/api/v1/api-keys/me` is the only current credential-principal endpoint.
2. Route catalog has no `/api/v1/submitters/*`.
3. Frontend still supports API-key credential viewer through new naming.

### SAK-D2: Delete SDK Submitter Surface

Goal: remove submitter from SDK public resource API.

Scope:

1. Delete `SubmitterRegistration`.
2. Delete `SubmitterProfile`.
3. Delete `SubmitterOperations`.
4. Delete `SubmitterRegistry` if no longer needed internally.
5. Remove `registerSubmitter(...)`, `authenticateSubmitter(...)`,
   `listSubmitters(...)`, `getSubmitter(...)`, and `hasSubmitter(...)`.
6. Delete submitter-specific docs and tests.

Acceptance:

1. `rg "SubmitterRegistration|SubmitterProfile|SubmitterOperations|SubmitterRegistry|registerSubmitter|authenticateSubmitter|listSubmitters|getSubmitter|hasSubmitter"` returns no mainline source hits except archived docs, if any.
2. SDK API-key operations cover the removed use cases.
3. CI is green without submitter compatibility tests.

### SAK-D3: Delete Seed And Storage Residue

Goal: remove submitter seed and duplicate credential truth.

Scope:

1. Delete `submitters` from control-plane seed schema.
2. Delete submitter seed parsing from `ControlPlaneSeedImporter`.
3. Delete sample submitter docs.
4. Remove any remaining submitter registry fallback from server production
   assembly.
5. Keep only API-key lifecycle/projection as task credential truth.

Acceptance:

1. Seed import supports API keys and no longer supports submitters.
2. Server production path has one credential truth.
3. Quick-start still works with sample API keys.

## Built-In Data Guidance

Recommended baseline built-ins:

- permission names
- system roles
- bootstrap operator users
- minimal matching rules

Recommended sample/dev built-ins:

- sample projects
- sample events
- sample API keys
- sample operator credential for local session login

Not recommended as production built-ins:

- real API-key secrets
- submitter identities
- real tenant/project/event truth
- worker/device/account resources
- historical task data

## Test Plan

API-key contract tests:

- API-key credential authenticates to `PrincipalContext`
- API-key scopes enforce task create/read/append
- disabled/revoked/expired API key is rejected
- API-key read model never exposes raw secret after creation

Server integration tests:

- create API key -> create task -> append item -> read result
- out-of-project denied
- out-of-event denied
- missing permission denied
- `/api/v1/api-keys/me` returns current credential principal

Seed tests:

- sample API-key seed imports
- sample quick-start uses API-key credentials
- `submitters` seed field is rejected or absent after deletion phase

Architecture guards:

- task authorization consumes `PrincipalContext`, not submitter records
- server API-key lifecycle does not use `SubmitterRegistration`
- no production controller maps `/api/v1/submitters/*` after SAK-D1
- no mainline SDK type contains `Submitter` after SAK-D2

## Risks

Risk: SDK samples break during migration.

Mitigation: add API-key SDK replacement first, then move samples, then delete
submitter.

Risk: two credential truths drift during transition.

Mitigation: each phase must reduce submitter usage; compatibility fallback is
temporary and removed by SAK-D3.

Risk: sample API-key seed looks production-ready.

Mitigation: checked-in sample secrets remain explicitly local/dev only; prod
requires environment-owned seed or existing DB state.

Risk: test churn hides auth regressions.

Mitigation: do not duplicate old and new happy-path tests. Replace submitter
tests with stronger API-key lifecycle and denial tests.

## Final Target

Final shape:

```text
User
  -> API Key lifecycle
      -> CredentialAuthProjection
          -> AuthProvider
              -> PrincipalContext
                  -> AuthorizationPolicy
                      -> task create / append / read
```

Removed shape:

```text
SubmitterRegistration
SubmitterProfile
SubmitterOperations
SubmitterRegistry
/api/v1/submitters/*
submitters seed field
```

