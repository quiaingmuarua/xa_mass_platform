# XA Mass 1.0 release and upgrade contract

Status: current 1.x public release contract.

## Public compatibility

XA Mass 1.0 stabilizes the current `/api/v1` Runtime API, Worker Delivery DTOs
and Event meanings, the public Java/Android Worker SDK interfaces, supported
startup configuration, and Runtime/SDK archive manifests. Public incompatible
changes require another major version; compatible additions and fixes use
minor and patch versions. Redis keys, Scores, Pacer implementation classes,
diagnostic catalog entries and generated build files are internal mechanisms,
not caller APIs. Storage upgrade requirements are documented with each release.

The Runtime requires Java 21 and caller-owned Redis. SDK transport-neutral
modules retain Java 11 compatibility; Android Worker retains minSdk 24.
The four distributed Maven artifacts are `xa-mass-worker-delivery-contract`,
`xa-mass-worker-core`, `xa-mass-android-worker` and
`xa-mass-android-capability-http`, with POMs and source archives.

Production composition retains one active Server/Pacer for a scope. Kernel
and Pacer decide scheduling, Matching owns identity evidence, Server admits and
correlates, and Transport delivers decided Commands. Report delivery and later
observations retain their best-effort contracts. This release establishes no
exactly-once execution, durable report replay, HA election or performance SLA.

Preview retains its separate version and finite topology. App Checks' development
identity headers are trusted caller declarations, not authenticated public-user
credentials. See the versioned
[Owner contracts](https://github.com/quiaingmuarua/xa_mass_platform/blob/v1.0.0/AGENTS.md).

## Changes for 0.x callers

- Project responses declare `projectId` and `workerGroupIds`. Reads and Group
  registration do not prepare Tasks. A caller explicitly sends
  `POST /api/v1/tasks/{taskId}/rpc:prepare` with its known Project and Group.
  The complete descriptor and caller Task ID must match on reuse. Retain that
  identity through restart; an uncertain mutation is not automatically replayed.
- Worker Properties use flat `Map<String,String>` values. Convert typed device
  observations explicitly at the Properties producer. Business payloads retain
  their Event-owned format and remain opaque to platform scheduling.
- Worker reports carry `diagnosticCode`, allowing an empty string for success.
  Report `messageType` owns success/failure meaning. Local capability HTTP uses
  its `status`; Direct Call observations include `messageType`,
  `diagnosticCode` and `opaqueResultPayload`. Do not classify execution by a
  numeric diagnostic code. Worker SDK local outcomes expose `isSuccess()`.
- Current named Worker queries use `executorName` and `input`. Directed Worker
  identity evidence remains separate from Pool stock consumption.
- Runtime manifest schema is 6; SDK manifest schema remains 1. Runtime contains
  a Boot JAR and frontend, with `default`, `agentforge` and `preview` profiles.
  Start the manifest's `serverJar` with Java. Runtime supplies no Python kernel,
  wheel, virtual environment or Scenario Worker Host launcher.

## Fresh-state cutover

No historical Redis or H2 data migration is required for 0.x to 1.0. Stop the
old Server and Workers, retain the old installation for rollback, and select a
new Redis scope and fresh business configuration directory. Initialize startup
declarations, register caller-owned Groups explicitly, and prepare the caller's
RPC Tasks before submitting Items. Server and Worker SDK switch together.

AgentForge declares its `agentforge` Project with the four existing Group IDs.
It registers those Groups through the public API and uses the caller convention
`agentforge-rpc-v1.<workerGroupId>`. This convention is attribution and caller
identity management; Server does not interpret it as authorization or scheduling.

From an extracted Runtime root:

```powershell
java -jar .\lib\xa-mass-server-jvm-1.0.0.jar `
  --spring.profiles.active=agentforge `
  --spring.web.resources.static-locations=file:frontend/dist/ `
  --xa.mass.redis.scope=profile_agentforge_v1
```

Deployment supplies its Project declarations and Group catalog. H2 remains
scenario-owned configuration storage; saved changes take effect on restart.
Rollback restores the paired previous Runtime, SDK, caller build and old scope.
New state is not read by the previous release or copied back into it.

## Release evidence and publication

The candidate main commit must pass a complete manual Proof CI, including both
convergence matrix cases and the real Redis, Runtime Boundary, Android Emulator,
product, frontend and Distribution lanes. Distribution builds explicit `1.0.0`
ZIPs and proves Runtime and SDK consumption outside the checkout. Its artifacts
are retained for 30 days. Normal path-selected green CI is not this release gate.

The tag workflow promotes those exact ZIP bytes to a Draft Release and creates
basename-only SHA256 sidecars. It verifies SHA, version, full Proof and current
attempt artifacts, then runs Distribution's archive validators. Resuming a
Draft can add missing identical assets; existing different or published assets
are never overwritten. Enable GitHub Release immutability before publication.

Consumer acceptance uses the same ZIPs on a clean AgentForge commit with an
exact release lock. API 33 x86_64 Emulator evidence covers admitted-Handler stop,
reconnect, process loss, Server restart and the fixed three-App topology.
Host stop must return inside the five-second request budget while a thirty-second
DELAY remains admitted; this is a lifecycle witness, not a throughput or latency
promise. Telegram/Instagram/RCS target-version and account acceptance remains
with their own Owners.

After consumer acceptance, download the four Draft assets and compare them with
the accepted files before publishing. Verify the published release is immutable
and resolves to the candidate SHA. AgentForge then verifies public downloading
and completes its normal CI. Published fixes use a new version.
