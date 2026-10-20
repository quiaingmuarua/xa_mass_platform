# XA Mass 3.1 release and upgrade contract

Status: current 3.1.0 release contract; publication requires the complete manual Proof and exact-asset promotion below.

## Version and compatibility

Runtime and Worker SDK use one explicit `3.1.0` version, complete Git commit and
exact ZIP hashes. Runtime manifest schema remains 6, SDK schema remains 1,
execution routes remain `/api/v1`, and the four SDK artifacts retain their public
interfaces. Java 21, external Redis and Android minSdk 24 requirements remain.
The `default`, `consumer` and `preview` profiles and `runtime-websocket` Adapter
retain their 3.0 meanings.

This minor release deliberately includes an incompatible startup configuration
and Runtime bindings-view migration, explicitly accepted as a one-time exception
to the [3.0 compatibility commitment](RELEASE-3.0.md). It is not a claim of full
3.0 configuration compatibility or a general relaxation of future version rules.
Old fields are rejected; no aliases or automatic conversion are provided.
Published 3.0 binaries, checksums and their contract remain immutable.

## Named Group window bindings

Use `xa.mass.worker-assembly.fixed-window.pools.<name>` to declare the query,
timestamp/count fields, window, threshold and required nested
`feedback.message-events`. Select that Pool once through
`fixed-window.groups.<group>.pools.<name>: {}`; the reference enables stock,
query and paired allocation feedback together. A Group may override window and
threshold. Retired top-level `feedbacks`, Group `feedbacks` and Project-based
window binding fields are not accepted. See the
[binding Owner](../../server_jvm/README.md#named-window-pool-and-feedback-bindings)
for the complete input and collision rules.

The Runtime Group bindings response now embeds
`feedback: {observationEventName, messageEvents}` in each `fixedWindowPools` row;
consumers must stop reading the retired separate feedback list. These views
describe configuration, not stock or execution evidence.

Project membership controls Task admission and never enables resources. Group
registration neither installs a Handler nor creates a Task or Pool. Declare the
host Groups and events before startup dependency validation, then explicitly
create a finite Task or prepare a caller-owned RPC Task. The workbench discovers
the enabled Pool/query and accepts the existing Handler's raw input without a
new business Controller or Matching strategy.

Window qualification occurs during refill against an admitted Facts snapshot.
Allocation feedback is best effort, not reserved quota, execution success or a
strict global rate limit. No Kernel, Pacer or Matching algorithm is changed by
this release. Runtime registration or hot replacement of window policy is not
introduced.

## External deployment and Batch Checks

The clean `consumer` profile may explicitly enable Batch Checks using external
Runtime YAML, Group registration JSON and a writable Scenario configuration.
The Runtime ZIP includes a deployment README and three seed examples under
`examples/batch-checks`; copying them does not activate a Scenario. Installation
files remain immutable while configuration and Task archives live in a separate
deployment data directory. See [external deployment](README.md#external-consumer-workbench-deployment).

Batch Checks supports complete validated configuration replacement, including
removal, Group rebinding and protocol changes. Saved configuration becomes
effective only after restart. Existing Tasks retain their original execution
snapshot and price; configuration replacement does not remove runtime data or
charges. Host Pool activation and business protocol interpretation remain with
their existing Owners.

## Fresh-state upgrade and rollback

Stop the old deployment, retain its installation, lock and data, install both
verified 3.1.0 ZIPs, and supply migrated configuration with a new explicit Redis
scope and separate business data directory. Do not rewrite or clean the old
scope. Window-length changes always require a new scope. No history migration,
backfill or old-field decoding is provided.

AgentForge upgrades its immutable release lock explicitly and supplies its own
Group/window configuration. Its `default` raw finite-task workbench can consume
the configured Pools independently of Batch Checks seed/result adaptation.
Rollback restores the whole previous consumer, Runtime, SDK, configuration and
old scope; new state is never written back into the old deployment.

## Proof and exact-asset promotion

One clean main commit produces one Runtime/SDK ZIP pair with explicit version
3.1.0. A successful complete manual Proof CI at that same SHA is required;
path-selected push/PR runs cannot substitute for it. Runtime Distribution and
the independent Android consumer use the original candidate ZIP bytes.

Tag promotion prepares a Draft containing the two ZIPs and two basename-only
SHA256 files. Re-download and compare the complete assets against the accepted
candidate before publishing, then verify `immutable=true`. Existing Draft assets
must match exactly and published assets cannot be overwritten. Release notes
select this major/minor contract, including for later 3.1 patches, without a
fallback to the 3.0 document.

XA's Java/Redis lanes own window qualification, rejection/recovery, isolation and
snapshot boundaries. The complete API 33 Android proof owns candidate ZIP/SDK
consumption, actual digest results, lifecycle/fault recovery, triad isolation and
raw/Batch Checks window witnesses. It does not establish AgentForge target App,
account, hook or physical-device behavior; those remain downstream acceptance.
