# XA Mass 2.0 release and upgrade contract

Status: current 2.x public release contract.

## Public compatibility and scope

XA Mass 2.0 delivers the Project-scoped Task workbench, shared TaskCaller admission,
development user directory, explicit registered-Group default Project, and named
App Checks execution contracts. These changes require caller and startup
adaptation, so this is a new major release. The Runtime API remains `/api/v1`.
Compatible additions and fixes within 2.x use minor and patch versions; public
incompatible changes require another major version.

The Runtime requires Java 21 and external Redis. The paired Worker SDK still
contains four Java/Android artifacts, retains Java 11 transport-neutral contracts
and Android minSdk 24, and introduces no Worker Delivery wire change in this
upgrade. Runtime manifest schema remains 6 and SDK manifest schema remains 1.
Version, complete commit and exact ZIP SHA256 identify one Runtime/SDK pair.
Preview and the frontend package retain their independent version meanings.

Kernel/Pacer retain scheduling and convergence authority. Matching owns Worker
qualification, queries, Pools and indexes. Server admits callers and composes
use cases; Transport delivers Commands and executes local Handlers. The new
read-only Group catalog page does not interpret Projects in Kernel or register
resources. No scheduling, retry, recovery or Task-finality authority moves.

## Task workbench and raw input

The workbench supports every declared Project. RPC and finite Tasks have lists,
explicit creation and immutable saved configuration. RPC debugging has a separate
JSON request page for 1..100 Items, including one Item in the same shape, plus
per-message observations and explicit result reload. No route, read or call
implicitly prepares a Task.

RPC preparation accepts explicit Pool `refill`; the workbench always supplies it,
including `[]`. Omission by programmatic callers still uses the host Group's
configured RPC supply. Supply and per-Item queries are independent; waterlines
are shared hints rather than reserved Worker quotas. Function capabilities now
contain `{name, poolName}`, where `poolName` may be null.

Finite creation accepts raw payload JSONL, field-mapped TXT or complete Item
JSONL. Input is validated before creation; append remains bounded to 100 Items
per request. Finite approval requires `{expectedItemCount}` with a positive
integer. Result preview is bounded and independent of successful terminal export.

Task creation, directory registration and approval remain separate Owner commits.
Unknown HTTP outcomes can follow committed work; retain the known request/Task/
message identities and observe before deciding the next explicit action.
The UI does not automatically retry writes. Same message IDs do not promise
exactly-once execution.

## Caller and user-directory adaptation

Protected Task and Scenario operations require exactly one `X-XA-Dev-User`
(UTF-8 Base64URL user ID) and one `X-XA-Dev-Role` (`admin` or `operator`).
Server resolves them through its current development directory into one immutable
request snapshot. Missing or malformed declarations are rejected; unknown users,
role mismatches and insufficient Project/Task access are denied.

The packaged seed includes administrator `1000`; its explicit headers are
`X-XA-Dev-User: MTAwMA` and `X-XA-Dev-Role: admin`. Deployments may supply
`xa.mass.users.seed`. Operators use their assigned Projects and owned Tasks
under the role's permissions. Public metadata cannot supply `userId` or
`server.*`: Server stamps creator, Project, input owner and creation time.
Raw append and Item Call require `server.inputOwner=raw`; business input passes
its Scenario conversion even for an administrator.

User management is Server-process memory initialized from JSON. Changes govern
new request admission; accepted work keeps its snapshot. Restart reloads the
seed and discards directory edits. This is a trusted-development identity
contract, without password/session authentication, persistence or replication.
Internal Project service callers remain assembly-only and cannot be declared
through HTTP. See the [Server caller contract](../../server_jvm/README.md#shared-development-caller).

## Default Project and Group initialization

Declare `default` explicitly with an empty Group binding list. Do not pass the
1.0 deployment's four `worker-group-ids` under default. Its capabilities read
traverses current registered Groups; writes still point-check registration.
Other Projects retain nonempty explicit bindings. Responses include
`workerGroupSource=registered` or `declared`.

For an AgentForge deployment, retain the clean `agentforge` Boot profile,
declare Project `default`, and supply the deployment's four Group descriptors
through `xa.mass.worker-assembly.group-config-json`. Runtime initializes them
before Adapter ingress. The public Group registration API remains available.
Neither Group registration nor Project declaration creates a Task, enables a
Matching Pool, or proves that a Worker has installed the declared Handlers.

## App Checks configuration

App Checks uses statically registered `registration-v1` and
`simulated-registration-v1` contracts for parameter validation, input conversion
and result interpretation. Each Task retains its first execution binding and
normalized parameters. Malformed business content is reported as a content error
without changing Kernel execution success or being converted to an unregistered
answer.

Current source stores the complete configuration in a scenario-owned JSON file,
with normalized content fingerprints, one process owner and atomic replacement.
Saves take effect on restart. Existing Tasks retain saved execution values.
The file-storage cutover uses `configuration.path`/`configuration.seed` and a fresh
JSON file; it does not read, migrate or delete old H2 files. Changing existing
window lengths requires a new Redis scope. Server and Kernel do not read the file.
Published immutable artifacts retain their version's configuration contract.
See the [App Checks Owner](../../scenarios/app-checks-jvm/README.md).

## Fresh-state upgrade and rollback

Stop the old deployment before switching. Keep its Runtime, SDK, consumer lock,
configuration and Redis scope for rollback. Import the new exact Runtime/SDK
pair into a separate installation, adapt callers and declarations, and select
a fresh Redis scope such as `profile_agentforge_v2`. Use a fresh App Checks
configuration location when that scenario is enabled. There is no historical
descriptor, directory, identity or configuration migration.

Start the new Server, register its Groups, explicitly prepare caller-owned RPC
Tasks, then connect upgraded Workers. AgentForge's finite proof uses unique
`test_*` scopes and retains its known RPC Task ID through Server restart.
Rollback restores the complete old deployment and old scope; new state is not
copied back.

## Release evidence and publication

The candidate main commit must pass a complete manual Proof CI. Distribution
builds explicit `2.0.0` Runtime/SDK ZIPs, verifies archive contents and proves
consumption outside the checkout. Full proof includes JVM/Redis Owners, Runtime
Boundary, Worker correctness and convergence, Android Host/APK/Emulator,
Frontend, Distribution and product coexistence. Artifacts are retained 30 days.
Path-selected push/PR success is not a substitute for that complete manual run.

The tag workflow promotes those exact ZIP bytes to a Draft with two basename-only
SHA256 sidecars. It validates version, full SHA, current attempt, complete proof
and archive contents. Draft resumption accepts only identical existing assets;
published releases are never overwritten. Consumer and workbench acceptance use
those same ZIPs before publication. Confirm Release immutability before publishing,
re-download the four assets and compare hashes, then verify `immutable=true`.
After publication, verify public Release download through the consumer's normal CI.
The development default advances to `2.0.1-SNAPSHOT` after publication.

Proof remains finite: API 33 Emulator evidence does not establish target-account
or real-device Telegram/Instagram/RCS acceptance, authentication security, HA,
durable Report replay, exactly-once execution or a throughput/latency SLA.
