# XA Mass 4.1 release and upgrade contract

Status: version-scoped 4.1 release contract; publication requires complete manual Proof at the
exact candidate commit. Published 4.0 artifacts remain immutable.

The changes below apply to the 4.1 series. GCP's purpose and deployment cadence belong to the
[Release Preview guide](../../doc/guide/operations/deploy.md#release-preview). This release's
upgrade path is not a commitment to preserve interfaces, configuration or data across future
releases.

## New asset: Worker Simulator kit

The Release publishes a third component, `xa-mass-worker-simulator-4.1.0.zip` with its SHA256
sidecar, next to the Runtime and Worker SDK ZIPs. It lets any machine act as an external user
of a Runtime without a checkout or a build:

- `bin/xa-mass-worker-simulator --config host.json` runs the existing Java Worker Simulator as
  a remote Host through the Worker gateway credential;
- `python3 acceptance/finite_round.py` runs one finite-Task round through the public API as the
  caller of its access key, checks every MD5 Result, Item state, preview and export, and prints
  one JSON line with outcome `passed`, `failed` or `unconfirmed`.

The kit is a test and acceptance capability, not a production Worker, and is supported only
against the Runtime of the same Release. Its user guide ships inside the ZIP; packaging is
owned by [Worker Simulator Kit Distribution](../worker-simulator/README.md).

## Console fix

Approving or closing a raw finite Task from its list row now opens that Task and confirms
against its fresh snapshot. In 4.0 the row action did nothing unless that Task was already open.

## Configuration, state and upgrade

There are no API, wire, configuration or data changes. A 4.0 deployment keeps its scope,
archive directory, users file and gateway input. Take the one upgrade backup, install 4.1
separately, switch the exact JAR, start it and check local readiness.

The Release Preview additionally prepares a long-running external user. This is host
configuration, recorded through the host operations log:

1. Add Group `soak-java-workers` (event `extension.worker.string.md5`, Pool `any`, function
   `worker.any`) to `worker-groups.json` and to the gateway's Worker Prepare allowlist, then
   re-render and validate the gateway.
2. Issue a Worker gateway key `soak-host` for that Group's Host.
3. After the Runtime starts, create operator user `soak` with Project `default` in the Console
   and issue its access key.
4. Hand both credentials to the external user privately. Its Workers and rounds then never
   share a Group with acceptance Tasks.
5. In the host records, the acceptance Java Host now runs from the published kit instead of a
   source build.

## Acceptance focus

Beyond the per-Release baseline, check list-row approval in the browser, because Console
behavior changed. Also run one kit round against the Preview from an external machine, using
the published kit, a temporary acceptance Worker key and the acceptance user's key.

## Publication evidence

Use the existing complete manual Proof. Candidate Distribution Archives now builds and validates
the kit ZIP with the Runtime and SDK ZIPs. Runtime And Worker SDK Distribution no longer builds
the Simulator from source. It extracts that exact kit as its external Java Host, behind real
Nginx, and runs the kit's shipped round command as a Project-scoped operator. Promote all three
candidate ZIPs and their SHA256 sidecars and verify immutable publication.
