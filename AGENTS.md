# XA Mass Agent Handoff

Status: current repository change contract.

Use the root [reading path](README.md#reading-path) to select the scope for an
overall handoff, cross-owner change or local edit. This file governs changes;
mechanism flow, storage shape, configuration and scenario limits stay in Owners.

## Applying This Contract

- Check the current Git HEAD, worktree and requested outcome; preserve unrelated
  edits. Reuse established session evidence and refresh what the change affects.
  A local edit does not require another whole-repository scan.
- Follow the authorized scope through its necessary slices. Slice boundaries,
  status labels and missing template fields do not require another approval.
  Review-only requests remain read-only and do not imply tests or a PR. Ask only
  when a material unresolved choice affects the next action; continue independent
  authorized work.
- Read the affected Owner sections, their mandatory linked clauses and actual
  callers/consumers. Follow each affected input, state effect and failure boundary;
  the applicable contract is not every chapter of every linked document.
- Current ownership governs ordinary changes. An explicitly requested migration
  may revise the affected contract with its callers, Owner document and proof;
  "fixed" or "frozen" does not prohibit it. Hardening does not authorize unrelated
  boundary changes.
- Skills and memory are working guidance, not current project truth or permission
  to expand scope. Retrieve history only when the requested investigation needs it.

## Evolution Principles

- Distinguish implemented behavior, authorized plans, possible directions and
  version-scoped history. Discussion creates no required work or reserved interface.
- Let concrete problems drive small, verifiable changes. Foundational risks or
  clear high-return improvements may justify earlier structural work: establish
  the cost or risk, expected benefit and verification without requiring an incident.
- Evidence-based, authorized migrations may revise Owner boundaries with their
  callers and proof. These principles add no scoring system, proposal template or
  approval step and do not interrupt authorized implementation.

## Trust Order

Choose evidence by the question, affected Owner and claim, not by a fixed ranking
of document types or test layers.

| Question | Evidence and limits |
| --- | --- |
| What is implemented now? | Current production code and its actual callers/assembly; test source shows intended assertions, not an executed result. |
| What has been verified? | Executed proof for that claim, with its tested commit/worktree, configuration, environment and result. CI success covers only the selected assertions; a source link or site build is not runtime proof. |
| What behavior is required? | The requested outcome and applicable Owner contracts, including an authorized migration. Current code or a passing test can still fail that requirement. |
| What happened previously? | Immutable, version-scoped records. Their result does not automatically apply to changed code or conditions. |

Current prose must agree with implementation and applicable proof evidence;
it does not overrule a demonstrated result. Resolve disagreements within those
scopes: correct stale documentation, report confirmed code defects, and repair
behavior only when the task covers it. Do not change production merely to match prose.

## Authority And Change Rules

| Owner | Authority |
| --- | --- |
| Kernel | Mechanical scheduling state, Scores, resources and exact execution claims |
| Pacer | Kernel scheduling policy, bounded rounds, retry/recovery and lifecycle |
| Matching | Properties, qualification, fixed queries, indexes and bounded candidate evidence |
| Server | API admission, identity, application use cases, routing, correlation and assembly |
| Transport | Delivery of decided Commands and endpoint-local Handler execution |

Behavioral domains do not create modules or relocate mutation authority. Do not
move candidate selection, Worker lease, TaskItem claim, retry, recovery or Task
finality into Server or Transport. Boot, Distribution, Scenario, Android, frontend
and proof surfaces retain their [existing Owners](README.md#authority-and-dispatch).

- Call out core authority or scheduling-flow changes in the implementation plan,
  report and Owner document; implement only explicitly discussed and authorized
  mechanism changes. Preserve owners for truth, evidence, address, correlation,
  projection and hints.
- Treat new Kernel operations as long-lived costs. Prefer caller-bounded identities,
  same-key aggregation and owner-local operations. Keep cross-key fan-out, global
  discovery, owner-spanning aggregation and background coordination in the caller
  or policy unless a named invariant proves otherwise.
- Keep Scores opaque outside their Owner. Pacer may retain, associate, exact-compare
  and return raw scores; it must not decode, construct or calculate coordinates.
  Opacity does not require another wrapper class.
- Build Redis keys from `RedisKeyspace`'s fixed `xa_mass:<scope>` base; each Owner
  appends only its domain suffix. Proofs use unique `test_*` scopes and clean only
  their exact scope with `SCAN` plus `UNLINK`, never `KEYS`, `FLUSHDB` or `FLUSHALL`.
- Best-effort hints must not become correctness prerequisites. A bounded evidence
  handoff may be lossy across process failure; local capacity is retryable
  backpressure when upstream already owns bounded retry, not semantic rejection.
- Do not add bridge layers, compatibility aliases, mirrored DTOs, fallback Owners
  or speculative modules. The bounded Pacer lifecycle bridges in its
  [public boundary](kernel_pacer_jvm/README.md#public-boundary) are the explicit
  internal assembly exception.
- Keep module-coded exceptions local: `errorCode + owner.method operation + message
  + cause`. Put context in safe logs/traces; never log opaque Worker payload or
  result content. JVM-only modules use `System.Logger`; Android-consumed Java 11
  modules use `java.util.logging`.
- Keep Redis and SQLite strictly separated by hot/cold role: Redis owns hot
  scheduling, Matching, active Task/Item/Result, delivery, cooldown and credit
  state; `task-history.sqlite` owns the finite Task history archive; optional
  `control-plane.sqlite` owns low-frequency Server control-plane metadata backed
  by an immutable in-memory read snapshot. Kernel, Pacer and Matching hot paths
  must never read or write SQLite.
- Update the owning mechanism document in the same change as behavior.

## Applicable Owner Contracts

Use these entrypoints for the affected path, not as a serial reading checklist.
The root module map and [development guide](doc/guide/development/index.md) locate
business, capability and observation Owners.

| Change | Required contract entrypoints |
| --- | --- |
| Kernel operations, dependencies or Scores | [Production caller closure](kernel_jvm/README.md#production-call-closure), [boundaries](kernel_jvm/README.md#boundaries), affected mechanical Owner and [Result commit contract](kernel_jvm/doc/runtime-redis/task-result-runtime-redis-shape.md) for Score/content changes |
| Cross-owner scheduling or Pacer policy | Affected [scheduling mainline](doc/kernel/scheduling-overview.md), including scale/liveness when applicable; [Pacer assembly guardrails](kernel_pacer_jvm/doc/application-assembly.md#guardrails) and affected Dispatch/Result Policy |
| Matching queries, refill, resources or lifecycle | [Matching Owner](worker_matching_jvm/README.md#owner-boundary), affected query/refill/failure clauses and [fixed composition](worker_matching_jvm/README.md#fixed-resource-composition) for resource/lifecycle changes |
| Server API, access, accounts, Task data or Scenario composition | Affected [Server operation](server_jvm/README.md#projects-tasks-and-results), [response rules](server_jvm/README.md#api-reference-and-response-contract) and [assembly boundaries](server_jvm/README.md#assembly-boundaries); affected Scenario Owner and [Boot](server_boot_jvm/README.md) for composition |
| Events, wire shape or delivery | [Delivery Contract](transport/worker-delivery-contract/README.md) and [Event Catalog](transport/EVENTS.md); affected [Adapter loops/lifecycle](transport/netty-adapter/README.md#delivery-processes) |
| Worker lifecycle or capabilities | [Core run](transport/worker-core/README.md#one-worker-run), affected Java/Android Owner, including [Android callback admission and close](transport/android-worker/README.md#callback-admission-and-close); [Simulator](worker_simulator_jvm/README.md) or [Android Host](xa-android/README.md) for capability/Host changes |
| Proof, frontend or delivery | Affected [Proof Registry entry](doc/testing/proof-registry.md) and linked Integration workload/fault/evidence/assertion clauses; [Frontend](frontend/README.md), [Distribution](distribution/server/README.md) or [Preview](distribution/server/PREVIEW.md) for those surfaces |

## Verification And Execution

Select checks by the changed claim through [TESTING](TESTING.md#selection-decision)
and the [Proof Registry](doc/testing/proof-registry.md). Behavior changes require
the focused Owner lane first; Redis concurrency/atomicity and runtime claims need
their named real-infrastructure proof. Documentation-only changes use Docs Contract
and link/content review without runtime proofs, even when describing a runtime mechanism.

For compilation, tests, packaging and CI, follow the existing
[long-command and waiting guidance](TESTING.md#long-running-commands-and-ci-waiting).
Do not create a sub-agent solely to wait. Keep searches and output scoped, reuse
established evidence and report meaningful changes instead of unchanged logs or
repeated analysis.

Before completion, run `git diff --check`; scan affected references after removals
or renames; confirm historical material is not linked as current truth. Report
checks actually run and required proof that could not run. Distinguish source
inspection, executed proof, submitted changes and externally applied results.
Finish independent authorized work and state remaining dependencies; do not claim
completion while a required external step remains pending.

## Documentation And Environment Boundaries

Keep one current source for each contract and follow
[publishing and maintenance](doc/README.md#publishing-and-maintenance) for guide,
Owner and skill updates. Pruning must preserve raw proof records and the active
release-tool contract.

Ordinary development and review use repository evidence and selected local/CI
checks. Access GCP, host records or credentials, deploy Releases or perform public
acceptance only when explicitly included in the task. An undeployed change is not
unfinished ordinary development. For authorized environment work, follow the
[Release Preview guide](doc/guide/operations/deploy.md#release-preview), target
Release contract and actual operation records; external capacity work starts from
the dated [public acceptance closeout](doc/guide/capabilities.md#external-public-closeout).
Closed investigations do not require repeating historical failed loads.

[Distribution local launch](distribution/server/README.md#source-launch) and
[staging ingress](distribution/server/src/main/dist/examples/staging-ingress/README.md)
own process/configuration and gateway procedures. Cleanup is limited to owned
processes, touched ADB mappings and explicitly owned temporary scopes; do not take
over unrelated processes or expand cleanup to other data.
