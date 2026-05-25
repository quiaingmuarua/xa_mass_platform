# Identity And Access Roadmap

Last updated: 2026-05-25

Status: direction roadmap for `xa-mass-server`.

## Summary

This roadmap defines the server-owned identity and access-control plane. It
also owns API-key application, approval, credential lifecycle, and API-key usage
audit because those are access-control concerns in the first product slice.

It is not an engine roadmap.

Target shape:

```text
User
  -> built-in or external-login operator / applicant identity

Role
  -> permission bundle for users

Permission
  -> stable platform action string

ApiKeyApplication
  -> request / review workflow

ApiKeyCredential
  -> scoped programmatic principal credential

ApiUsageLedger
  -> API-key usage audit, not billing

SubmitterViewerSession
  -> API-key backed scoped viewer session for own tasks/results

ExternalIdentityLink
  -> later Google/GitHub login mapping

DevOperatorSession
  -> dev-only permission validation shell
```

Fixed placement:

```text
identity and API-key lifecycle:
  server control-plane storage

authorization decision bridge:
  PrincipalContext + AuthorizationPolicy

API-key usage audit:
  server control-plane audit/usage owner

task execution / worker matching / result convergence:
  unchanged engine/runtime truth
```

Credit, billing, worker earnings, and quota enforcement are intentionally out
of scope for this roadmap. They should be handled by a later accounting
roadmap after access control is stable.

## Core Rules

1. API keys are scoped programmatic credentials, not user login sessions.
2. API keys do not inherit all user roles by default.
3. Roles bundle permissions for users; API keys carry explicit approved scopes.
4. Raw API-key material is shown once and never persisted.
5. API-key usage audit records API ingress, not worker callbacks or result repair.
6. There is no public/open user registration flow in this roadmap.
7. Early users are built-in or admin-managed; mid-term login may use Google/GitHub providers.
8. External login providers must converge to `PrincipalContext`.
9. Engine, transport, and runtime modules must not import user, role, permission, API-key, or usage-ledger stores.
10. Permission checks stay at server / SDK authorization boundaries, not inside scheduling policy.
11. SDK changes are allowed only when they reuse or tighten existing auth contracts such as `PrincipalContext`, `AuthorizationPolicy`, `SubmitterRegistration`, and `AuthProvider`.
12. Dev operator login is a local permission-validation shell, not a production login product.
13. API-key viewer sessions are submitter-scoped sessions, not operator sessions.

## Goals

1. Add a thin server-owned user / role / permission model.
2. Productize API-key application, approval, revocation, and scoped authentication.
3. Extend the existing submitter credential path instead of creating a parallel registry.
4. Make API key the primary SDK-first credential for task submission and result reads.
5. Allow API-key backed submitter viewers to inspect their own tasks, results, archives, and usage.
6. Add API-key usage audit for task-facing submitter calls.
7. Keep route authorization centralized through `ApiRouteAuthorizationCatalog`.
8. Preserve `PrincipalContext + AuthorizationPolicy` as the cross-surface authorization bridge.
9. Give server and SDK auth surfaces a stable permission catalog to build against.
10. Keep identity and authorization outside engine scheduling, transport delivery, and result convergence.

## Non-Goals

This roadmap does not:

1. Replace engine scheduling, lease, result, or archive ownership.
2. Introduce a full external IAM framework as the first owner.
3. Add public user sign-up, invitation, password reset, or self-service account recovery.
4. Implement credit accounts, billing, worker earnings, or quota enforcement.
5. Make worker ownership part of permission enforcement.
6. Meter worker callback, dispatch attempt, retry, or repair as API-key usage.
7. Change engine, transport, runtime, worker matching, or result kernel behavior.
8. Add frontend pages or frontend permission routing in this roadmap.
9. Split `xa-mass-server` into a separate IAM service.
10. Add a compatibility layer for retired internal auth paths.
11. Expose raw credential material after creation.
12. Add password login, password reset, email verification, or account recovery.
13. Make dev operator impersonation available in production profiles.
14. Let an API key create or upgrade into an operator session.

## Existing Baseline

Current mainline already has:

```text
DefaultOperatorPrincipalDirectory
ApiAuthInterceptor
ApiRouteAuthorizationCatalog
ApiAuthorizationService
PrincipalContext
AuthorizationPolicy

SubmitterRegistration.credential(...)
AuthProvider.authenticate(...)
X-Mass-Api-Key / Authorization: Bearer
/api/v1/submitters/me
project scopes
event scopes
permission scopes
task ownership stamp
```

New API-key work must extend this owner path. Do not create a second
independent credential registry.

## Target Model

### UserRecord

```java
record UserRecord(
    String userId,
    String displayName,
    String email,
    UserStatus status,
    Map<String, String> attributes,
    Instant createdAt,
    Instant updatedAt
) {}
```

Statuses:

```text
ACTIVE
DISABLED
DELETED
```

First version may keep authentication simple through existing dev/header auth,
but user records should become the stable console owner identity.

Early user source:

```text
built-in bootstrap users:
  first operational users for local/dev/small deployments

admin-managed users:
  users created or disabled by an operator, not public self-registration

external-login users:
  later Google/GitHub login identities mapped into existing UserRecord entries
```

### RoleRecord

```java
record RoleRecord(
    String roleId,
    String name,
    String description,
    Set<String> permissions,
    boolean systemRole,
    Instant updatedAt
) {}
```

Default roles:

```text
OPS_ADMIN
OPS_VIEWER
API_KEY_REVIEWER
```

### UserRoleBindingRecord

```java
record UserRoleBindingRecord(
    String userId,
    String roleId,
    String grantedBy,
    Instant grantedAt
) {}
```

Do not hide role bindings inside `UserRecord.roles`. Bindings need grant
metadata for audit and future revocation.

### ExternalIdentityLinkRecord

```java
record ExternalIdentityLinkRecord(
    String provider,
    String subject,
    String userId,
    String email,
    Instant linkedAt,
    Instant lastLoginAt
) {}
```

First-version implementation does not need Google/GitHub login, but the model
should leave room for it. The provider identity authenticates the human user;
server authorization still resolves to `UserRecord -> Role -> Permission ->
PrincipalContext`.

### Permission Catalog

Permission names are stable platform action strings:

```text
task:view
task:create
task:edit
task:govern
task:control

worker:view
worker:edit

api-key:view
api-key:apply
api-key:approve
api-key:revoke

api-usage:view

user:view
user:edit
role:view
role:edit
audit:view
```

Rules:

```text
1. permission names are server/API contract
2. roles are mutable bundles over stable permission names
3. API keys receive explicit approved permissions and scopes
4. engine must not import role or permission stores
5. SDK/server authorization surfaces should consume permission names from the catalog
```

### ApiKeyApplicationRecord

```java
record ApiKeyApplicationRecord(
    String applicationId,
    String applicantUserId,
    String applicantName,
    String requestedPrincipalId,
    String requestedUserId,
    List<String> requestedProjectScopes,
    List<String> requestedEventScopes,
    List<String> requestedPermissions,
    String purpose,
    ApiKeyApplicationStatus status,
    String reviewReason,
    String reviewedBy,
    Instant createdAt,
    Instant reviewedAt
) {}
```

Statuses:

```text
PENDING
APPROVED
REJECTED
CANCELLED
```

### ApiKeyCredentialRecord

```java
record ApiKeyCredentialRecord(
    String keyId,
    String principalId,
    String createdForUserId,
    String keyPrefix,
    String credentialHash,
    List<String> projectScopes,
    List<String> eventScopes,
    List<String> permissions,
    ApiKeyCredentialStatus status,
    String applicationId,
    String createdBy,
    Instant createdAt,
    Instant revokedAt,
    String revokedBy,
    String revokeReason
) {}
```

Statuses:

```text
ACTIVE
REVOKED
DISABLED
EXPIRED
```

Rules:

```text
1. raw secret is returned only by approve/create response
2. reads expose keyId + keyPrefix, never credentialHash or raw secret
3. authentication uses hash lookup
4. keyPrefix is display/debug only
5. revocation is immediate for subsequent authentication
```

Relationship rules:

```text
createdForUserId:
  user who requested or owns the key

principalId:
  programmatic principal authenticated by the key

permissions:
  explicit approved key scopes, not automatic user-role inheritance
```

### ApiUsageLedgerRecord

```java
record ApiUsageLedgerRecord(
    String usageId,
    String keyId,
    String principalId,
    String userId,
    String project,
    String eventCode,
    String operation,
    String taskId,
    String messageId,
    String requestId,
    long units,
    ApiUsageStatus status,
    Instant createdAt
) {}
```

First-version operations:

```text
TASK_CREATE
TASK_ITEM_APPEND
TASK_ITEM_SYNC_APPEND
TASK_RESULT_READ
TASK_ARCHIVE_DOWNLOAD
```

Statuses:

```text
ACCEPTED
REJECTED
FAILED_AFTER_ACCEPT
```

Usage ledger is audit material for API-key calls. It is not credit accounting.

### SubmitterViewerSessionRecord

```java
record SubmitterViewerSessionRecord(
    String sessionId,
    String keyId,
    String principalId,
    String createdForUserId,
    Instant createdAt,
    Instant expiresAt,
    Instant revokedAt
) {}
```

Rules:

```text
1. created from a valid ApiKeyCredential
2. resolves to PrincipalContext(type=API_KEY), not an operator user session
3. cannot access user / role / API-key approval APIs
4. cannot task control / govern through the viewer session
5. can read only owner-scoped task/result/archive/usage resources
6. expires quickly and is invalidated when the source key is revoked
```

If a future API key scope allows task control, that must be a direct API-key API
call with explicit authorization. It must not be smuggled through submitter
viewer sessions.

## HTTP Surface

### User / Role / Permission

```text
GET    /api/v1/users
GET    /api/v1/users/{userId}
POST   /api/v1/users
PATCH  /api/v1/users/{userId}

GET    /api/v1/roles
GET    /api/v1/roles/{roleId}
POST   /api/v1/roles
PATCH  /api/v1/roles/{roleId}

POST   /api/v1/users/{userId}/roles/{roleId}
DELETE /api/v1/users/{userId}/roles/{roleId}

GET    /api/v1/permissions
```

Rules:

```text
1. POST /api/v1/users is operator/admin-managed creation, not open registration
2. no public sign-up endpoint is introduced by this roadmap
3. Google/GitHub login later maps provider identity to an existing or operator-approved user
```

### API Keys

Submitter-facing:

```text
POST /api/v1/api-key-applications
GET  /api/v1/api-key-applications/{applicationId}
GET  /api/v1/submitters/me/api-keys
GET  /api/v1/submitters/me/usage
```

Operator-facing:

```text
POST /api/v1/api-keys
GET  /api/v1/api-key-applications
POST /api/v1/api-key-applications/{applicationId}:approve
POST /api/v1/api-key-applications/{applicationId}:reject
GET  /api/v1/api-keys
GET  /api/v1/api-keys/{keyId}
POST /api/v1/api-keys/{keyId}:revoke
GET  /api/v1/api-keys/{keyId}/usage
```

Rules:

```text
1. early implementation may create API keys through operator-created keys first
2. application/approval flow remains the target workflow for requester-driven keys
3. both paths produce the same ApiKeyCredentialRecord and one-time raw secret response
4. both paths store hash + prefix only
```

### Submitter Viewer Session

```text
POST /api/v1/submitter-sessions
GET  /api/v1/submitter-sessions/me
POST /api/v1/submitter-sessions:logout
```

Rules:

```text
1. session creation accepts an API key and returns a short-lived browser session
2. session principal remains API-key scoped
3. session can use owner-scoped task/result/archive/usage APIs
4. session cannot call operator-only user/role/API-key approval APIs
5. revoked API keys cannot create or continue submitter viewer sessions
```

## Usage Audit Rules

Meter after authentication and authorization resolve the submitter credential,
but before the controller returns.

Default units:

```text
TASK_CREATE:
  units = 1

TASK_ITEM_APPEND:
  units = accepted item count

TASK_ITEM_SYNC_APPEND:
  units = 1

TASK_RESULT_READ:
  units = returned row count

TASK_ARCHIVE_DOWNLOAD:
  units = 1
```

Rules:

```text
1. meter only authenticated API-key calls
2. meter accepted ingress, not final task success
3. do not meter worker callbacks
4. do not meter retries or redispatches
5. use requestId/clientRequestId for idempotency when available
6. operator console calls are not API-key usage
7. usage records are proof for later accounting, but do not enforce balance
```

## Storage Contract

Suggested server-owned stores:

```java
interface UserRolePermissionStore { ... }
interface ApiKeyApplicationStore { ... }
interface ApiKeyCredentialStore { ... }
interface ApiUsageLedgerStore { ... }
interface SubmitterViewerSessionStore { ... }
```

Storage placement:

```text
memory:
  dev and controller tests

JDBC:
  persistent server profile

engine/runtime:
  no dependency
```

The credential store should back or adapt the existing
`SubmitterOperations` / `AuthProvider` credential truth.

## Phase Plan

### Phase IAM-0: Inventory And Boundary Lock

Goal: document current auth, permission, and submitter credential seams without
behavior changes.

Scope:

```text
1. inventory DefaultOperatorPrincipalDirectory and header auth behavior
2. inventory ApiPermissionNames and route authorization catalog
3. inventory SubmitterRegistration / AuthProvider credential path
4. inventory every server endpoint that accepts API-key submitter credentials
5. inventory SDK auth contract touchpoints and decide whether each is reused, tightened, or removed
6. document no-identity-or-usage-in-engine-or-transport boundary
```

Acceptance:

```text
1. no behavior change
2. no new auth framework
3. no new API routes
4. no engine/runtime import changes
5. clear owner path from key authentication to PrincipalContext
```

### Phase IAM-1: User / Role / Permission Store

Goal: make built-in/operator identity data server-owned instead of bootstrap-only.

Scope:

```text
1. add UserRecord / RoleRecord / UserRoleBindingRecord
2. add memory UserRolePermissionStore
3. adapt DefaultOperatorPrincipalDirectory to resolve roles from the store
4. seed a small set of built-in users, including OPS_ADMIN and OPS_VIEWER
5. expose read-only user / role / permission APIs
```

Acceptance:

```text
1. /api/v1/auth/me still returns admin/viewer permissions correctly
2. permissions resolve from roles
3. existing route authorization tests still pass
4. engine imports no user/role classes
5. there is no public registration endpoint
```

### Phase IAM-2: API-Key Application And Credential Lifecycle

Goal: create, approve, authenticate, and revoke scoped API keys safely.

Scope:

```text
1. add ApiKeyApplicationRecord and store
2. add ApiKeyCredentialRecord metadata shape
3. add operator-created API-key API for the early low-complexity path
4. add application create/list/detail APIs
5. add operator approve/reject APIs
6. generate raw key only on operator create or approval
7. store hash + prefix, never raw secret
8. wire generated keys into existing submitter authentication path
9. add list/revoke key APIs
```

Acceptance:

```text
1. applicant can create a pending application
2. operator can directly create a scoped key and receive one-time raw secret
3. operator can approve an application and receive one-time raw secret
4. direct-create and approval paths produce the same credential shape
5. /api/v1/submitters/me works with the generated key
6. revoked/disabled keys cannot authenticate
7. key permissions do not inherit full user permissions automatically
8. list/detail APIs never expose raw secret or hash
```

### Phase IAM-3: Submitter Viewer Session

Goal: allow an API key to create a restricted viewer session for its own
task/result/usage resources without becoming an operator session.

Scope:

```text
1. add SubmitterViewerSessionRecord and store
2. add POST /api/v1/submitter-sessions
3. validate API-key status and scopes before session creation
4. resolve viewer session to PrincipalContext(type=API_KEY)
5. allow owner-scoped task/result/archive reads
6. deny user/role/API-key approval APIs
7. expire sessions quickly and invalidate sessions for revoked keys
8. audit submitter viewer session creation and logout
```

Acceptance:

```text
1. API key can create a submitter viewer session
2. viewer session can list tasks created by the key/principal
3. viewer session can read own task results and archives
4. viewer session cannot control/govern tasks
5. viewer session can read own usage summary after IAM-4 usage audit exists
6. viewer session cannot access /api/v1/users or approve/revoke API keys
7. revoked key cannot create or continue viewer sessions
8. viewer session does not create an operator session
```

### Phase IAM-4: API-Key Usage Audit

Goal: record API-key usage without changing admission behavior.

Scope:

```text
1. add ApiUsageLedgerRecord and store
2. resolve authenticated keyId during API-key auth
3. meter task create, append, sync append, result read, archive download
4. record accepted/rejected/failure status
5. add submitter/operator usage query APIs
```

Acceptance:

```text
1. API-key task create creates a usage row
2. API-key item append records accepted item count
3. API-key sync append records one sync usage row
4. operator console calls do not create API-key usage rows
5. usage query is bounded and filtered by keyId/principal/project/time
```

### Phase IAM-5: SDK Auth Contract Tightening

Goal: reuse the existing SDK auth contracts and remove only genuinely stale server-side assumptions.

Scope:

```text
1. verify PrincipalContext carries all fields needed by server IAM
2. verify AuthorizationPolicy remains the only route/action authorization bridge
3. verify SubmitterRegistration / AuthProvider can host approved API keys
4. tighten or remove stale server auth helpers that duplicate SDK policy semantics
5. keep all changes inside server and SDK auth surfaces
```

Acceptance:

```text
1. existing submitter credential tests still pass
2. route authorization still goes through AuthorizationPolicy
3. generated API keys authenticate through the existing AuthProvider path
4. no engine/transport/runtime package changes are required
```

### Phase IAM-6: User / Role Management APIs

Goal: support operator-managed users and roles after API-key distribution and
viewer sessions are stable.

Scope:

```text
1. add create/update user APIs
2. add create/update role APIs
3. add role bind/unbind APIs
4. emit audit entries for role changes
5. keep permission catalog fixed in code for v1
6. keep all user creation admin/operator-only
```

Acceptance:

```text
1. operator can create a user
2. operator can assign and remove roles
3. role changes affect /api/v1/auth/me permission snapshots
4. role mutations are auditable
5. anonymous callers cannot register users
```

### Phase IAM-7: Google / GitHub Login Adapter Point

Goal: preserve future Google/GitHub login integration without making external IAM the current owner.

Scope:

```text
1. keep OperatorIdentityProvider narrow
2. allow Google/GitHub provider implementation later
3. map external identity to ExternalIdentityLinkRecord
4. resolve ExternalIdentityLinkRecord to UserRecord / PrincipalContext
5. keep AuthorizationPolicy as the server decision bridge
```

Acceptance:

```text
1. built-in dev/header auth still works
2. Google/GitHub provider can be added without changing route controllers
3. engine/transport/runtime remain unaware of external IAM
4. provider login does not create public self-registration semantics by default
```

### Phase IAM-8: Dev Operator Login Shell

Goal: provide a dev/local permission-validation entry without building a
password or registration product.

Scope:

```text
1. add a dev/local-only built-in user selector
2. selecting ops-admin / ops-viewer creates a dev-only operator session
3. session resolves through UserRecord -> Role -> Permission -> PrincipalContext
4. production-like profiles disable the selector
5. startup fails fast if dev impersonation is enabled in a production profile
6. audit dev impersonation with userId, targetUserId, request ip, and user agent
```

Out of scope:

```text
1. no password login
2. no public registration
3. no password reset or email verification
4. no production login solution
5. no replacement for future Google/GitHub login
6. no dependency from API-key auth to operator session
```

Acceptance:

```text
1. ops-admin and ops-viewer produce different /api/v1/auth/me snapshots
2. viewer cannot approve/revoke API keys
3. admin can approve/revoke API keys
4. API-key authentication works without an operator session
5. dev impersonation emits an audit event
6. production profile with dev impersonation enabled fails startup
```

## Testing Plan

Identity / authorization:

```text
1. role permissions produce expected PrincipalContext
2. viewer cannot approve API keys
3. API-key reviewer can approve but not mutate task runtime directly
4. disabled user cannot authenticate through operator provider
5. anonymous caller cannot create a user through public registration
```

API key:

```text
1. application create validates required scopes and purpose
2. operator-created key returns raw secret once
3. approve returns raw secret once
4. direct-created and approved keys work through /api/v1/submitters/me
5. key does not inherit unapproved user permissions
6. revoked key returns 401
7. key list/detail never exposes raw secret or hash
8. rejected application cannot create a credential
```

Submitter viewer session:

```text
1. API key creates short-lived submitter viewer session
2. viewer session can list only principal-owned tasks
3. viewer session can read only own task results/archive
4. viewer session cannot call user/role APIs
5. viewer session cannot approve/revoke API keys
6. revoked key invalidates viewer session
7. usage summary is available only after IAM-4 usage audit exists
```

Usage audit:

```text
1. task create with API key records TASK_CREATE usage
2. append N items records TASK_ITEM_APPEND units=N
3. sync append records TASK_ITEM_SYNC_APPEND usage
4. operator console task create records no API-key usage
5. failed authorization records no accepted units
6. duplicate clientRequestId does not double meter when idempotency exists
```

Server host-shell:

```text
1. /api/v1/auth/me returns permission snapshots from the server IAM store
2. apply -> approve -> use generated key -> create task shell
3. append items with generated key -> usage visible
4. stale WorkerContext or legacy task-action routes are not part of the server IAM contract
5. dev operator selector shows distinct ops-admin / ops-viewer permission states
6. production profile rejects dev impersonation configuration
```

## Architecture Guards

Add targeted guards after the corresponding packages exist:

```text
1. engine packages must not import user/role/API-key/usage classes
2. transport packages must not import user/role/API-key/usage classes
3. runtime modules must not import identity or authorization store classes
4. API-key DTOs must not expose raw secret except approval response
5. route authorization must go through ApiRouteAuthorizationCatalog
6. usage metering must be wired through server/API ingress
7. worker callback and result convergence code must not write API-key usage
8. submitter viewer sessions must not create operator sessions
9. submitter viewer routes must stay owner-scoped
```

## Risks And Mitigations

Risk: user and API-key permission confusion.

Mitigation:

```text
API-key permissions are explicit and approved. Do not inherit all user roles by
default.
```

Risk: API-key viewer becomes an operator session.

Mitigation:

```text
Submitter viewer sessions resolve to API-key PrincipalContext only. They are
owner-scoped, read-only viewers and cannot access user/role/API-key approval or
task-control APIs.
```

Risk: credential registry split.

Mitigation:

```text
Approved API keys must feed the existing submitter credential authentication
owner. Do not create a second independent auth provider path.
```

Risk: runtime usage coupling.

Mitigation:

```text
Record accepted API ingress. Do not meter retry, callback, lease expiry, or
result repair as API-key usage.
```

Risk: secret leakage.

Mitigation:

```text
Return raw secret only once. Persist hash + prefix. Test that list/detail
responses never include raw secret or credentialHash.
```

Risk: permission names drift across server and SDK authorization surfaces.

Mitigation:

```text
Reuse the existing permission catalog and AuthorizationPolicy contract. Do not
create a second server-only permission vocabulary.
```

Risk: open registration sneaks in through external login.

Mitigation:

```text
External provider login maps to existing or operator-approved users. Automatic
first-login provisioning is a later explicit product decision, not this roadmap.
```

Risk: dev login shell becomes a production auth system.

Mitigation:

```text
Dev operator sessions are profile-gated, loopback/local oriented, audited, and
fail-fast when enabled in production-like profiles. No passwords are introduced.
```

Risk: future quota breaks idempotency.

Mitigation:

```text
Quota enforcement belongs to a later accounting roadmap. Pair future accounting
decisions with requestId or clientRequestId where available.
```

Risk: IAM work leaks into engine.

Mitigation:

```text
Identity and permission stores stay in server control-plane packages. Engine,
transport, and runtime receive no IAM store dependencies.
```

## Recommended First Slice

Start with:

```text
IAM-0 + IAM-1
```

Concrete first PR:

```text
1. inventory current operator and submitter credential owners
2. add UserRecord / RoleRecord / UserRoleBindingRecord
3. add in-memory UserRolePermissionStore
4. seed built-in OPS_ADMIN and OPS_VIEWER users through the store
5. adapt DefaultOperatorPrincipalDirectory to resolve from the store
6. expose read-only /api/v1/users, /api/v1/roles, /api/v1/permissions
7. keep all existing auth behavior passing
```

First product slice after the foundation:

```text
IAM-2 + IAM-3
```

This makes API-key distribution useful before full user/role administration:

```text
1. create or approve a scoped API key
2. use it from SDK/API
3. create a submitter viewer session
4. inspect only that key/principal's own task/result resources
```

Do not implement credits, worker earnings, quota enforcement, public
registration, password auth, account recovery, or production Google/GitHub
login in this roadmap.

## Final Target

```text
User:
  human/operator owner

Role:
  permission bundle

Permission:
  stable platform action string

ApiKeyApplication:
  request and review workflow

ApiKeyCredential:
  scoped programmatic principal credential

ApiUsageLedger:
  API-key usage audit

SubmitterViewerSession:
  API-key backed scoped viewer for own tasks/results/usage

ExternalIdentityLink:
  later Google/GitHub login mapping

DevOperatorSession:
  dev-only built-in user selector for permission validation

Engine:
  unchanged runtime kernel
```

One-line owner decision:

```text
layer=server control-plane storage; reason=user/role/API-key state and API-key
usage audit are host authorization truth, while execution remains engine/runtime
truth.
```
