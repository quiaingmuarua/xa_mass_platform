# XA Mass Documentation

Status: current repository document index.

## Start Here

1. [Repository Entry](../README.md) defines cross-module authority, dispatch
   vocabulary and the TASK/CONTROL_ONLY mainlines.
2. [Kernel Design Workspace](../kernel_design/README.md) leads to executable
   mechanism truth and owner documents.
3. [Proof Lanes](../TESTING.md) defines what each deterministic, Redis-backed,
   cross-process, Android and frontend lane proves.
4. [Agent Handoff](../AGENTS.md) defines repository change rules and forbidden
   boundary drift.
5. [Human Architecture Overview](../frontend/public/overview.htm) is the visual
   projection served by the frontend and Server.

## Owner Documents

- [Kernel documents](../kernel_design/doc/README.md)
- [JVM Kernel parity](../kernel_jvm/README.md)
- [Runtime API Server](../server_jvm/README.md)
- [Transport](../transport/README.md)
- [Transport Platform Event Catalog](../transport/EVENTS.md)
- [Scenario Workers](../scenario_workers_jvm/README.md)
- [Android surfaces](../xa-android/README.md)
- [External acceptance](../integrations/worker-capability-rpc/README.md)
- [Frontend](../frontend/README.md)

Module READMEs explain only their assembly, public entrypoints, local owner
mechanism and verification.

## Historical Assets

The following documents retain engineering lessons from the superseded Java
platform. They are historical evidence, not current mechanism truth:

- [Legacy Trace Assets](archive/trace/legacy-trace-assets.md)
- [Legacy Testing Assets](archive/testing/legacy-testing-assets.md)

The complete historical source is preserved by
`legacy-java-platform-final-2026-07-24`.
