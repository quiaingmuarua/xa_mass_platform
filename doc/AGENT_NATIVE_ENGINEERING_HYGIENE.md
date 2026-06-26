# Agent-Native Engineering Hygiene

Status: current cross-module engineering hygiene guide.

This document describes how this repo keeps fast agent-led iteration from
turning into duplicate truth, stale tests, or compatibility residue. It is not
more context to memorize. It is the operating method that helps a new agent
find the current truth, make a bounded change, prove the right invariant, and
delete what should no longer exist.

Core loop:

`Truth Lane -> Roadmap Review -> Scoped Implementation -> Proof Operator -> Residue Scan -> Archive`

## 1. Purpose

Agent-native engineering assumes that many changes will be planned, executed,
reviewed, and continued by different agents across multiple sessions.

The project therefore optimizes for low-entropy continuation:

- make current truth easy to find
- keep projections and diagnostics from becoming hidden owners
- review complex direction before large implementation
- prove critical planes through explicit proof operators
- archive completed or superseded plans
- remove old paths instead of preserving compatibility residue

The goal is not to make every agent write perfect code on the first attempt.
The goal is to make the system continuously tell the next agent what is true,
where change belongs, how it is proven, and what should be deleted.

This discipline becomes more important after the MVP phase. Early exploration
may intentionally optimize for learning speed, but production convergence
requires the project to identify which facts now participate in runtime,
policy, API, auth, or proof decisions.

## 2. Core Governance

### Truth Lane

Every significant model or runtime fact must have one truth lane.

Common categories:

- `truth`: the owner state used by mainline decisions
- `projection`: a read model derived from truth
- `hint`: a wakeup, cache, route suggestion, or best-effort signal
- `evidence`: trace, diagnostics, audit, timing, or observation output
- `residue`: old compatibility state, old names, or migration-only code waiting for removal
- `experimental`: prototype or brainstorm-only shape that must have an owner,
  expiry, and a rule that prevents it from entering mainline decisions

Agent rule:

- never promote projection, hint, evidence, or residue into mainline truth
- when adding a field, route, key, or test, name which category it belongs to
- if two lanes can mutate the same fact, stop and converge before expanding behavior
- allow experimental or evidence-only work during discovery, but keep it out of
  runtime truth, public API contracts, and mainline proof claims until it is
  intentionally promoted

Examples in this repo:

- worker selection truth belongs to Scheduling Plane owners, not frontend tables
- runtime queue, lease, and result convergence belong to runtime owners, not SQLite control-plane storage
- trace observes lifecycle and runtime behavior; it must not drive runtime ownership backward
- control-console pages display server/API truth; they must not invent production-only frontend models

### Lane Promotion And Demotion

Lane changes are design decisions, not incidental refactors.

Rules:

- `experimental -> truth` requires an owner, runtime consumer, proof line,
  owner-doc update, and residue plan for the prototype shape
- `evidence -> truth` is forbidden by default; if evidence must become truth,
  define a new owner instead of letting the observation path mutate runtime
- `projection -> truth` requires deleting or disabling the old truth writer;
  two mutable lanes for the same fact are not allowed
- `truth -> projection` requires caller migration and removal of write
  authority from the downgraded lane
- `hint -> truth` requires explicit admission into the owner model; otherwise
  hints stay best-effort and replay-safe
- `residue -> removed` requires caller migration, focused proof, current-doc
  backfill, and archive or deletion of stale planning text

Do not let temporary fields, caches, compatibility aliases, or test fixtures
become mainline by accident.

### Public Interface Contract Shape

Public runtime interfaces are owner boundaries. Their parameters must be stable
contracts, not whichever implementation record happened to be available at the
call site.

Allowed parameter shapes:

- primitive fields or stable value objects owned by the caller or shared
  contract, such as `workerId`, `workerGroupId`, `messageId`, or
  `observedAtMillis`
- explicit public contract DTOs with fields that are stable, meaningful, and
  constructible by the caller
- functional interfaces or callbacks when the caller is providing behavior
  rather than handing over owner-internal state
- opaque handles or refs that callers only store and return, without reading
  implementation fields

Forbidden public contract shapes:

- internal observation records whose fields are produced by the callee's state
  machine
- cache snapshots, diagnostics, trace evidence, score-band observations,
  lease/session internals, or current implementation DTOs
- wrapper records created only to move many internal fields through an
  interface
- parallel overloads such as `handle` and `evidence` variants that exist only
  because the lifecycle boundary is unclear

Agent rule:

- before adding an interface parameter, ask whether the caller owns, validates,
  and can construct every field
- if not, keep the data behind the owner and expose an opaque ref/handle or a
  smaller stable command
- tests must not mock internal observation records into public contracts just
  to preserve old call shapes
- if an interface already accepts such a record, treat it as convergence debt
  and avoid expanding it

### Roadmap Review

Complex cross-module changes require a reviewed roadmap before implementation.

Use a roadmap when the change touches:

- runtime truth or storage shape
- scheduling, worker selection, lifecycle, result convergence, or transport
- SDK/server/frontend boundary contracts
- auth, profile, startup, env init, seed/import, or CI proof lanes
- deleting or replacing an old concept across modules

Roadmaps may also be used as brainstorm or direction records. Writing a
roadmap does not mean it must be executed. Mark the status honestly:

- `brainstorm`: option space, not an approved direction
- `proposed`: direction exists, execution not started
- `active`: high-ROI convergence work is being executed in slices
- `superseded` or `archived`: not current execution truth

Only move a roadmap into active goal-mode execution when the owner boundary,
current high-ROI slice, proof surface, and residue expectation are clear enough
to keep each slice independently verifiable.

Roadmap activation and stop rules:

- an active roadmap must explain `why now`
- an active roadmap must have a current high-ROI slice
- if owner boundaries are unclear, mark the roadmap `proposed` or `blocked`
  instead of implementing through ambiguity
- if implementation proves the target direction wrong, stop and revise the
  roadmap rather than forcing the original plan through
- if proof cost exceeds the expected confidence gain, reduce scope or defer the
  slice explicitly
- goal-mode execution does not require finishing every phase; stop when the
  mainline is unblocked, ROI drops, or a later phase needs a new owner decision

Preferred phase shape:

1. `Converge`: inventory facts, reduce call sites, clarify owners, add guards
2. `Modify`: change one atomic boundary at a time with local verification
3. `Delete Residue`: remove old paths, old tests, old docs, old names, and fallback logic

Roadmap and goal-mode fit:

- a one-turn plan is useful for a small, well-bounded implementation slice
- a roadmap is a multi-turn convergence artifact, not a one-turn checklist
- complex cross-module work usually needs goal-mode progression: review the
  boundary, implement one slice, verify, scan residue, then continue
- do not compress unknown owner decisions, proof gaps, migration residue, and
  deletion work into a single "plan complete" response
- do not force small local fixes into a roadmap when the owner, truth lane,
  proof surface, and residue boundary are already obvious

Roadmap review is not bureaucracy. It prevents an agent from solving a local
symptom by creating a second owner, bypass, wrapper, or compatibility layer.

Workflow references:

- [roadmap-refinement](./skills/roadmap-refinement/SKILL.md) for executable
  roadmap review, owner-boundary analysis, proof surfaces, guardrails, and
  independently verifiable slice planning
- [roadmap-residue-scan](./skills/roadmap-residue-scan/SKILL.md) for
  post-slice or completion-gate scans across stale names, compatibility paths,
  stale status, duplicate owners, and archive readiness

These skills are workflow aids. They do not override owner docs, baseline
docs, current code, or verified runtime behavior.

### Proof Operator

A proof operator is an explicit external entry that proves a critical truth
plane through the same kind of process a real caller uses.

Examples:

- packaged server startup smoke
- admin CLI env init and API health
- Java SDK task producer flow
- worker launcher registration and polling flow
- trace analyzer or proof summary writer

Operator map:

| Proof operator | Proves | Does not prove |
| --- | --- | --- |
| `admin CLI` | operator auth, env init, readiness, and admin/API reachability through real HTTP | runtime scheduling correctness |
| `scenario launcher` | external task producer or worker path through SDK/API surfaces | full policy matrix or all credential denial cases |
| `trace analyzer` | runtime observation through canonical trace evidence | mutation ownership or hidden repair authority |
| `worker-read health` | worker read-model/API health at a named worker/group scale | task execution scale or scheduling policy correctness |
| `proof summary` | evidence classification, proof-line visibility, and workflow/report integrity | the underlying runtime behavior unless linked to executed proof evidence |

Proof operators are not substitutes for deterministic kernel tests. They prove
real wiring, startup, auth, process shape, and cross-boundary usability. Kernel
policy and lifecycle correctness still need owner-level deterministic proof.

### Three-Layer Proof

Use the three project proof classes from [TESTING_INDEX.md](./TESTING_INDEX.md):

| Proof class | Owns | Typical entry |
| --- | --- | --- |
| `Product / API Capability Proof` | correct users can start, authenticate, create work, run workers, and read results | packaged server, admin CLI, Java SDK, worker launcher |
| `Policy & Safety Correctness Proof` | wrong worker, wrong scope, wrong state, wrong credential, or wrong lifecycle mutation is rejected | engine deterministic tests, representative server E2E, negative auth tests |
| `Scoped Operational Resilience Proof` | a named fault, load, runtime, duration, and oracle holds under pressure | chaos, soak, perf, worker-read health, owner-scoped runtime probes |

Agent rule:

- do not treat happy E2E as proof of policy safety
- do not treat chaos/perf as broad production reliability proof unless the exact condition is exercised
- do not add a test until the proof class and proof line are clear
- when unsure, start with [PROOF_REGISTRY.md](./PROOF_REGISTRY.md)

### Proof Claim Discipline

A proof artifact must not claim more than it executed.

Rules:

- guard evidence protects the proof system; it is not runtime proof
- artifact existence is not behavior proof
- key existence is not runtime correctness proof
- E2E is an evidence shape, not a proof class
- proof summaries must preserve known non-proof boundaries
- `source-guard`, `schema-guard`, and `release-policy-guard` evidence must not
  inflate runtime proof counts
- `artifact-metadata` keeps incomplete or downgraded reports visible without
  proving the named runtime scenario

If a proof summary cannot explain what executed, which proof line it belongs
to, and what it does not prove, treat it as support evidence until it is
classified.

### Proof Failure Response

A failed proof is a signal to classify, not an excuse to weaken the proof.

Classify failures first:

- implementation bug: owner truth or behavior is wrong
- proof bug: test/operator/assertion does not match the intended contract
- environment issue: process, dependency, port, file, Redis, or profile setup failed
- overclaim: artifact claims more than it executed
- owner-boundary drift: caller, credential, route, runtime, or storage owner moved
  without matching docs/proof updates

Rules:

- do not add dev bypass, fixture-only auth, weakened assertions, or compatibility
  fallback just to make CI green
- if a proof operator fails while deterministic owner proof passes, inspect
  wiring, auth, startup, profile, process, or fixture setup before changing kernel
- if deterministic owner proof fails, fix the owner truth before adding E2E
  workarounds
- if proof failure exposes a wrong claim, update the proof summary, docs, or
  registry instead of hiding the non-proof boundary
- after the fix, add or update a guard only when it prevents the same class of
  drift from returning

### Archive Discipline

Roadmaps and inventories are active only while they guide current work.

When a roadmap is complete or superseded:

1. scan for stale names, old active links, duplicate paths, and compatibility residue
2. move current facts into owner docs or baseline docs
3. archive the roadmap under `doc/archive/<owner>/YYYY-MM-DD_NAME.md`
4. remove it from the active reading path

Archive discipline matters because agents reuse visible documents. A stale
roadmap left in the active path becomes false context.

## 3. Engineering Practices

### Docs As Agent Context

Docs are part of the agent execution environment.

Good docs:

- define owner boundaries
- state what is current truth
- distinguish target direction from current implementation
- link to proof owners
- say what not to do

Doc truth order:

- owner READMEs and baseline docs are current truth
- active roadmaps are change intent and execution plans
- archived roadmaps are historical context, not current truth
- when owner docs and a roadmap disagree, owner docs win unless the roadmap is
  explicitly active, newer, and the implementation gap is stated

Bad docs:

- preserve old narratives after implementation
- describe target state as already implemented
- accumulate roadmaps without archive
- list every detail but hide the decision owner

### Trace As Runtime Observation Proof

Trace is the runtime observation lane. It is evidence, not a mutation owner.

Use trace to answer:

- what happened
- which worker was selected
- which result converged
- which retry or lease path fired
- whether a proof scenario is observable through the canonical sink

Do not use trace to:

- drive scheduling decisions
- repair runtime state directly
- replace deterministic lifecycle proof
- invent a second data model because it is easier to query

### Integration Test Versus Deterministic Proof

Integrated tests prove real wiring. They do not replace core deterministic
proof.

Use deterministic owner tests for:

- scheduling policy correctness
- worker eligibility and admission
- lifecycle transition invariants
- result finality and idempotence
- runtime counters and resource release

Use integrated tests for:

- server startup and Spring wiring
- HTTP auth and route contracts
- SDK and external worker parity
- transport delivery and result ingestion
- representative real process flow

### Boundary Guards

Module boundaries are guardrails against second truth.

When adding an API or helper, ask:

- who owns the decision
- who is allowed to call it
- whether it protects a real lifecycle or protocol boundary
- whether it is only a pass-through wrapper
- whether it creates a second write path

If a new layer does not change ownership, caller surface, lifecycle, or
protocol boundary, it is usually noise.

A boundary guard should usually fail on:

- forbidden production dependencies
- forbidden package imports across owner boundaries
- public routes hard-coded outside SDK/client owner surfaces
- runtime key names leaking into SDK or server public contracts
- scenario launchers importing server or engine internals
- admin CLI importing runtime, storage, engine, or transport internals

### CI Drift Guards

CI should fail when proof infrastructure drifts.

Guard examples:

- proof registry and proof summary schema checks
- profile allowlists
- workflow proof-summary jobs
- no-bypass auth matrices
- source guards for forbidden runtime ownership
- startup profile context tests
- route/API health smoke gates

The goal is not just green CI. The goal is CI that can explain which proof lane
failed and which class of confidence was lost.

### Residue Scan

After a convergence change, scan for residue before expanding features.

Look for:

- old route names
- old field names
- compatibility projections promoted back into mainline
- stale roadmap references
- duplicate tests proving the same weak happy path
- fallback code preserved only because tests used it
- old seed/import paths that bypass current auth or owner surfaces

Residue scan is what keeps fast iteration from becoming architectural sediment.

### SDK / CLI / Scenario / Server Layering

Keep external actor, operator, scenario, and runtime owner roles separate.

Current intent:

- `server`: product/API host, auth boundary, control-console backend, startup validation surface
- `SDK`: API-key-authenticated external actor surface for task producers and workers
- `admin CLI`: operator/admin automation for env init, login, health, and control actions
- `scenario launcher`: repeatable scenario execution using SDK/API surfaces
- `engine/runtime`: kernel truth and runtime owners
- `frontend`: control-console consumer and validation surface

Do not mix these roles to make a test pass. A task producer API key is not an
operator session. A worker credential is not a task producer credential. A dev
profile may change infra convenience, but it must not create public API bypass
semantics that production does not have.

## 4. Default Workflow

Use this workflow for nontrivial changes:

1. Read current owner docs and code, not just old roadmaps.
2. Identify the truth lane and the owner boundary.
3. Decide whether the change needs a roadmap.
4. If it needs a roadmap, use it as a goal-mode convergence artifact: review
   the boundary before implementation, then advance one independently
   verifiable slice at a time.
5. Converge old call sites and name the current owner.
6. Modify one runtime or API boundary at a time.
7. Add proof in the correct proof class, and name the proof line, evidence shape, and non-proof boundary.
8. Run targeted verification and the relevant proof operator.
9. Delete residue and update owner docs.
10. Archive completed or superseded roadmaps.

## 5. Anti-Patterns

Avoid these patterns:

- treating read projection as write truth
- adding a same-module facade that only forwards calls
- using dev bypass to make tests pass
- letting E2E replace deterministic kernel proof
- adding a chaos/perf label to a happy path
- preserving old paths through adapters or aliases
- keeping roadmap prose active after implementation
- adding broad compatibility for pre-release internal APIs
- introducing DB tables for runtime hot state
- letting frontend-only models define backend production behavior
- testing implementation branches without naming the invariant
- adding a new proof line when an existing registry row already owns the invariant

## 6. Short Rule

Agent-native engineering is not about feeding the agent more context.

It is about keeping the system able to answer, quickly and mechanically:

- what is true
- who owns it
- where change belongs
- how it is proven
- what should be deleted
