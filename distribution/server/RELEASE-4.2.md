# XA Mass 4.2 release and upgrade contract

Status: version-scoped 4.2 contract. Publication requires the complete manual Proof at the
exact candidate commit. Published 4.1 artifacts remain immutable.

Subsequent public acceptance is recorded in the dated
[2026-11-13 closeout](../../doc/guide/capabilities.md#external-public-closeout).
It accepts bounded 100/1,000 diagnostics, not this version's full 15k/10k target.
The current manual workflow defaults to an explicit bounded preset; the shipped
4.2 command and full-target contract below remain unchanged.

## External capacity consumer

The existing Worker Simulator kit adds `acceptance/run_loaded.py` and the compiled
`worker-loaded-recovery` Harness. Its manifest is schema 2 and records `loadedEntry`;
Runtime and SDK manifest versions are unchanged. The Release still contains three ZIPs
and their SHA256 sidecars. A consumer downloads the kit instead of building source.

The manual `external-release` mode of the existing loaded-recovery workflow connects
to a separately managed Runtime. It prepares 15,000 identities, requires at least 14,800
stable connections, executes ten finite Tasks of 5,000 Items, stops its own final 5,000
Workers, then requires the retained 10,000 identities and at least 9,900 connections
before executing another ten Tasks. All 100,000 MD5 answers and complete exports are
checked independently. The original scheduled/default nightly keeps its full local
Server recovery stages and original assertions; the external mode has no Server fault,
Redis cleanup or SSH path. Public operation is manual-only and outside the ordinary PR gate.

The measured completion rate includes public network/Worker execution and observation
lag. It is not a Server maximum-TPS claim or proof that all connected Workers executed.
Record failures and unconfirmed writes without replaying them or reducing the workload.

## Persistent Release Preview capacity

There are no business API, Worker wire, Kernel, Pacer, Matching or data-format changes.
Reuse the current users file, scope and archives. The operator records the change in
the host operations log and takes the one upgrade restore point before switching.

The gateway renderer accepts explicit positive capacity fields. Omitted values preserve
the original conservative defaults. Apply these settings to the external deployment JSON
on the currently observed 8-logical-CPU, approximately 12-GiB Preview host:

| Field | Preview value |
| --- | ---: |
| `worker_processes` | 8 |
| `worker_connections` | 32768 |
| `worker_connection_limit` | 20000 |
| `worker_total_connection_limit` | 30000 |
| `worker_requests_per_second` | 200 |
| `worker_request_burst` | 1000 |
| `admin_requests_per_second` | 300 |
| `admin_request_burst` | 600 |

One proxied WSS uses both a client and an upstream connection. Keep Runtime/Nginx limits
at least 65536 FDs; set the Linux local port range to `10000 65535` while retaining existing
reserved-port settings. Set Runtime `-Xms1g -Xmx3g` and systemd `MemoryMax=6G`;
align `MemoryHigh=6G` so the old 4-GiB soft throttle does not remain hidden. Redis keeps
its 2-GiB noeviction/AOF policy and the host keeps its 2-GiB swap. Record actual CPU, memory,
FD, GC and swap activity during the first campaign. Do not disable authentication, TLS,
route restrictions or internal loopback listeners to obtain capacity.

Re-render using this Release's gateway renderer and validate Nginx before applying.
Switch to the exact published Runtime JAR and retain one Pacer per scope. Keep DEFAULT
Pacer, its mechanisms and timing unchanged. Tasks request the same shared Any target of
1000 as the existing nightly. These resource values are an experiment configuration,
not a capacity guarantee. After successful acceptance retain the validated configuration;
do not restore the small 100/200-connection entry by routine test cleanup.

## External operator and acceptance

Assign the existing `soak` operator, `soak-java-workers` Group and `soak-host` Worker key
exclusively to this external consumer. Stop any previous owner before handover: separate
Host directories alone do not isolate inventory coordinates within a Group. This is an
operating convention, not credential-to-Group or Worker identity authentication.

Configure the protected GitHub Environment with the public target coordinates and installed
Release tag/commit, the operator access key and the Worker credential. Never provide the
consumer with the administrator key or SSH access. The operator verifies the real installed
version before updating these coordinates; the client does not claim to inspect a remote JAR.

Run the published tool once through the manual workflow and preserve both scale results,
including uncertainty. The environment operator collects a bounded, read-only Server/Redis/
Nginx resource witness in parallel; it is separate from the consumer's own process samples.
Stop the consumer's processes and clean temporary secret files, retaining operational
credentials and normal Task retention. No browser/Computer Use acceptance is added.

On deployment health failure restore the single upgrade restore point; never clear Redis
or replay uncertain Tasks. Remove the previous Release and the temporary restore point
only after deployment acceptance, following the Release Preview operating conventions.

## Publication proof

Run focused Integration/runner/archive/gateway tests, the unchanged full-scale local
loaded-recovery proof, and complete manual Proof on the exact Release commit. Runtime
Distribution uses the candidate kit for a small real TLS/Redis external-consumer fixture;
that verifies the shipped entry, not the 15k public capacity claim. The latter requires
the explicit public campaign above. No existing proof threshold or workload is weakened.
