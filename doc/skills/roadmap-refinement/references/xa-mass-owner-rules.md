# XA Mass Platform Owner Rules

Use only for XA Mass Platform roadmaps, or when the current repo explicitly
documents the same owner split.

- Engine owns task lifecycle, scheduling decisions, rule evaluation,
  allocation budget, dispatch binding, result convergence, and terminal policy.
- Worker runtime owns worker lifecycle, declaration ports, candidate source,
  scheduling evidence, admission, dispatch gates, and worker report projection.
- Transport owns protocol sessions, connection presence, and delivery
  mechanics.
- Storage modules implement persistence adapters and may own storage contracts
  only when the stored data is not a higher-level runtime/domain contract.
- Server and SDK own assembly, bootstrap, admin API, and product shell surfaces.
- Trace/archive owns durable history and analytics evidence, not hot-path
  runtime truth.

## Scheduling Plane Rules

- Scheduling Plane work must preserve strategy/mechanism separation. Strategy
  owns resolved scheduling intent and limits; runtime mechanisms own queue,
  lease, retry, candidate enumeration, ranking, reservation, locks, live worker
  evidence, and admission.
- A single computed default strategy is acceptable. Do not start by adding a
  catalog, binding table, plug-in framework, or public policy configuration
  until at least two concrete variants, caller-visible cost, storage owner,
  runtime consumer, and proof are named.
- `TaskDispatchIntent`, `ResolvedTaskSchedulingPolicy`, and
  `ResolvedWorkerSchedulingPolicy` are engine-facing value contracts. They are
  not storage truth, not trace truth, and not proof that public policy products
  exist.
- `SchedulingPolicyCatalog` and `ProjectSchedulingBinding` are target product
  boundaries until a successor decision proves the caller, cost, owner, and
  binding subject.
- `RuntimeWorkerSelection` is a first-class Scheduling Plane owner. It must not
  be collapsed into worker scheduling policy or rule DSL.
- Trace, assignment records, and diagnostics may prove a Scheduling Plane
  decision, but they must not become the source of scheduling truth.

Apply the repo handoff and module README/CONTRACTS files first. If those files
contradict this reference, report the gap and follow current code plus active
repo contracts.

## XA Mass Roadmap Discovery

Roadmaps and direction docs are distributed across the repo. Do not only scan
the current roadmap directory.

Start with:

- root `AGENTS.md`
- `doc/README.md`
- owning module `README.md` and `CONTRACTS.md`
- `xa-mass-engine/doc/roadmap/`
- `doc/`

Use `doc/archive/` only as historical context unless an active doc points to
it. For stale status checks, compare the roadmap `Status:` line with current
code, architecture guards, focused tests, and recent commits.

Common active boundary tracks may include:

- worker runtime extraction and API slimming
- worker match upgrade
- rule boundary convergence
- projection boundary convergence
- task/worker runtime-history boundary
- Java external SDK and integration bootstrap roadmaps
