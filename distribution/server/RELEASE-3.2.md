# XA Mass 3.2 release and staging upgrade contract

Status: version-scoped 3.2 release contract; publication requires complete manual Proof at the
exact candidate commit. Published 3.1 artifacts remain immutable.

The changes and upgrade facts below apply to the 3.2 series. GCP's purpose and
deployment cadence belong to the
[Release Preview guide](../../doc/guide/operations/deploy.md#release-preview).
The compatible 3.1-to-3.2 state transition described here is not a commitment to
preserve interfaces, configuration or data across future releases.

## Added connection capability

Runtime and Worker SDK use the same explicit 3.2.0 version and full commit.
Runtime manifest schema remains 6 and SDK schema remains 1. The existing four
SDK coordinates, Java 21 runtime and Android minSdk 24 boundaries remain.

Worker connection options now accept an optional Basic gateway credential.
Java single/batch Prepare and Java/Android WSS handshakes, including reconnects,
carry it only to the configured HTTPS/WSS authority. Authenticated clients refuse
redirects, plaintext and foreign endpoints. Default unauthenticated local
connections retain their existing behavior. No scheduling or identity wire
contract is changed.

The standalone Simulator reads an explicitly supplied `gatewayCredentialFile`
once, relative to its main JSON. Secrets are deployment inputs, never inventory,
Properties, APK resources, logs or release assets. Android SDK support does not
mean the Demo App has a runtime credential configuration screen.

## Trusted staging ingress

The Runtime includes opt-in [staging ingress examples](src/main/dist/examples/staging-ingress/README.md).
Nginx terminates TLS and applies separate environment-administrator and Worker
Basic credential files. Positive route/method lists isolate those entrypoints
and exclude private Adapter delivery APIs. Merely copying examples enables
nothing; backend and Redis listeners stay private.

Gateway identities are not TaskCaller identities or Worker ownership. All
administrative credential holders remain trusted developers who can select the
existing development identities. All admitted Worker Hosts share one Worker
trust domain. These credentials do not establish customer or device isolation.

## State and rollback

This release adds no Kernel, Pacer, Matching, Redis identity or Task data format.
An existing 3.1 deployment can retain its scope and archive directory. Before
upgrading, retain its immutable installation, external configuration and a
usable Redis/archive restore point. Keep one Runtime/Pacer per scope.

Install the new Runtime separately, update the exact JAR and advertised WSS
address together, then perform bounded deployment acceptance. An old SDK cannot
use the authenticated public gateway; upgrade clients from the matched SDK pair.
The Java Simulator distribution is built separately at this release commit and
its hash is recorded; it is not an additional GitHub Release asset.

On failure close public ingress before restoring the previous binary and
configuration. Binary rollback does not undo completed tasks. Credential removal
blocks new authentication; immediate WSS revocation also closes existing gateway
connections through a controlled restart. Reload alone is not immediate revocation.

## Publication evidence

Use the existing complete manual Proof, including Runtime Distribution's actual
Nginx/TLS refusal and packaged-Runtime/Java-Host witnesses. Its test CA is trusted
normally; neither TLS certificate nor hostname checks are disabled. Existing
Android and Scenario proofs remain required by the release promotion contract.
No additional performance or nightly workload is introduced.

Promote the exact candidate Runtime/SDK ZIPs and their two SHA256 sidecars to the
Draft; verify all four assets before publication and verify immutable publication.
GCP downloads the published Runtime directly and records commit, artifact and
configuration hashes. GCP runs only a small deployment acceptance, not CI proofs.
