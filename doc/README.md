# XA Mass Documentation

Status: current repository document index.

## Start Here

Follow the [Repository reading path](../README.md#reading-path): start with the
system summary, then the [behavior loop](kernel/scheduling-overview.md#system-behavior-model),
the affected Owner and its callers/proof. Read business scenarios after this
platform mainline.
Use the [Human Architecture Overview](../frontend/public/overview.htm) as a
visual projection of the same boundaries.

| Document | Information it owns |
| --- | --- |
| [Repository Entry](../README.md) | Short system summary, reading path, authority, module and deployment map |
| [Scheduling Mainline](kernel/scheduling-overview.md) | Complete Matching / Execution / Convergence model, work/resource feedback, Owner mapping and code/proof navigation |
| [Delivery Boundary](kernel/worker-delivery-dispatch.md) | Command and observation handoffs, local Owner links and cross-boundary failure windows |
| Module README and linked Owner documents | Local mechanism, transitions, storage, configuration and lifecycle |
| [Proof Registry](testing/proof-registry.md) | Primary proof, claim and deliberate nonclaims |
| [TESTING](../TESTING.md) | Proof commands, prerequisites and CI selection |
| Integration/scenario README | Complete workload, mutation sequence, thresholds and assertions |
| [AGENTS](../AGENTS.md) | Change constraints and required Owner reading |

Keep a mechanism's detailed definition with its Owner. Entry documents link to
that definition and explain the handoff instead of repeating local parameters.
When an entrypoint or contract changes, update its callers, Owner description
and navigation together. A source pointer is a reading aid; only an executed
proof provides a current validation result.

## Owner Documents

- [Java Kernel authority](kernel/README.md)
- [Kernel mechanical owners](../kernel_jvm/README.md)
- [Kernel Pacer policy](../kernel_pacer_jvm/README.md)
- [Worker Matching owner](../worker_matching_jvm/README.md): bounded Facts qualification,
  local Pool stock and independent Phone Index, including qualified Direct Messages.
- [Runtime API Server](../server_jvm/README.md)
- [Server Boot and profiles](../server_boot_jvm/README.md)
- [Transport](../transport/README.md)
- [Transport Platform Event Catalog](../transport/EVENTS.md)
- [Scenario Workers](../worker_simulator_jvm/README.md)
- [Android surfaces](../xa-android/README.md)
- [Worker Correctness](../integrations/worker-correctness/README.md)
- [Worker Dynamic Matching](../integrations/worker-dynamic-matching/README.md)
- [Worker Convergence Health](../integrations/worker-convergence-health/README.md)
- [Proof Registry](testing/proof-registry.md)
- [Worker Loaded Recovery](../integrations/worker-loaded-recovery/README.md)
- [Android Worker Proof](../integrations/android-worker-proof/README.md)
- [Frontend](../frontend/README.md)
- [SMS Reception business workload](../scenarios/sms-reception-jvm/README.md)
- [Message Campaigns business workload](../scenarios/message-campaigns-jvm/README.md)
- [Scenario Preview delivery](../distribution/server/PREVIEW.md)
- [Scenario Coexistence](../integrations/scenario-coexistence/README.md)

Module READMEs explain only their assembly, public entrypoints, local owner
mechanism and verification.

## Historical Change Records

- [Pre-Matching lease verification, 2026-09-15](https://github.com/quiaingmuarua/xa_mass_platform/blob/86052b2855a8d73c4df9d03cd5c40af31d0bf6f3/kernel_pacer_jvm/doc/dispatch/assignment-dispatch-scheduling.md#serviceability-and-verification-scope)
  preserves the named baseline/worktree results and their evidence limits.
- [Task and Rule decoupling record](https://github.com/quiaingmuarua/xa_mass_platform/blob/6f9d01a098a322be5e559c41c313466be8fd361e/doc/task-rule-decoupling-plan.md) records the
  earlier Matching-owned binding cutover and its version-scoped local proofs.
- [Named Rule index cutover](https://github.com/quiaingmuarua/xa_mass_platform/blob/6f9d01a098a322be5e559c41c313466be8fd361e/doc/named-rule-index-plan.md) records the first fixed
  Handler, direct index dispatch and version-scoped local proofs.

Current Pool supply, Item query and storage semantics belong to the
[Matching Owner](../worker_matching_jvm/README.md).

## Historical Assets

The following documents retain engineering lessons from the superseded Java
platform. They are historical evidence, not current mechanism truth:

- [Legacy Trace Assets](archive/trace/legacy-trace-assets.md)
- [Legacy Testing Assets](archive/testing/legacy-testing-assets.md)

The complete historical source is preserved by
`legacy-java-platform-final-2026-07-24`.
