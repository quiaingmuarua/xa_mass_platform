# XA Mass Platform Architecture Guide

Status: human-facing architecture entry.

Chinese entry: [README.zh-CN.md](./README.zh-CN.md).

This directory is for people who want to understand and use XA Mass Platform.
It is intentionally different from [`../doc/`](../doc/), which holds kernel
contracts, owner baselines, verification runbooks, and agent-facing guardrails.

Use this guide when you want to answer practical questions:

- What is this project?
- What is the shortest mainline to run a task?
- How do `task`, `event`, `worker`, and `result` fit together?
- How do I add a new event and a worker capability?
- Which module should I open first?

## Reading Order

1. [Quick Start](./quick-start.md)
   - shortest path for creating a task, appending items, and pulling work with
     a worker
2. [Mental Model](./mental-model.md)
   - human-level architecture map and owner boundaries
3. [Add Worker And Event](./add-worker-and-event.md)
   - register an event, bind it to a project, register a worker, and process
     items by `eventCode`

After this directory, use the owner documents for precise contracts:

- external Java SDK: [`../sdk/xa-mass-java-sdk/README.md`](../sdk/xa-mass-java-sdk/README.md)
- SDK module map: [`../sdk/README.md`](../sdk/README.md)
- integrations module map: [`../integrations/README.md`](../integrations/README.md)
- SDK embedding: [`../sdk/xa-mass-embedded-sdk/README.md`](../sdk/xa-mass-embedded-sdk/README.md)
- HTTP/API contracts: [`../xa-mass-server/doc/INTERNAL_API_REFERENCE.md`](../xa-mass-server/doc/INTERNAL_API_REFERENCE.md)
- external worker protocol: [`../sdk/xa-mass-java-sdk/EXTERNAL_WORKER_QUICKSTART.md`](../sdk/xa-mass-java-sdk/EXTERNAL_WORKER_QUICKSTART.md)
- engine owner truth: [`../xa-mass-engine/README.md`](../xa-mass-engine/README.md)
- task lifecycle baseline: [`../doc/TASK_LIFECYCLE_BASELINE.md`](../doc/TASK_LIFECYCLE_BASELINE.md)
- transport boundary: [`../transport/TRANSPORT_BOUNDARY_BASELINE.md`](../transport/TRANSPORT_BOUNDARY_BASELINE.md)

## One-Line Model

XA Mass Platform is reusable distributed scheduling infrastructure for
structured work items:

```text
task shell
  -> append items with eventCode
  -> runtime schedules work
  -> worker executes the selected event handler
  -> worker submits result
  -> runtime converges item and task state
```

The core value is not "store a task row". The core value is reliable runtime
convergence under worker matching, dispatch, retry, timeout, result callbacks,
and task-level terminal policy.

The reusable kernel is:

```text
Task + Worker + Scheduling + Matching
+ lease-based dispatch
+ idempotent result convergence
+ multi-transport delivery
+ retry/repair/backpressure
= reusable distributed scheduling infrastructure
```

Matching is a policy surface, not just a fixed rule list. The current default
uses WorkerGroup-backed candidate acquisition, worker scheduling evidence,
rule-backed eligibility, ranking, and runtime admission. Future policies can
add worker metrics, task-type affinity, fairness, historical performance, or
domain-specific scoring without changing the task/worker/runtime ownership
model.

## Current Integration Bias

Use the SDK first:

- `xa-mass-java-sdk` is the recommended external Java surface for task
  producers and worker processes that connect to a running server.
- `integrations/xa-mass-scenario-launcher` is the primary executable proof of
  that external Java SDK path.
- `integrations/xa-mass-worker-pack` owns reusable worker capability code and
  dev/E2E harness support; it is not an SDK module.
- `xa-mass-embedded-sdk` is the recommended JVM embedding surface for in-process
  runtime composition.
- `xa-mass-server` is the reference Boot host and lightweight backend product
  skeleton for HTTP APIs, auth/IAM, API keys, and the control console.
- `xa-mass-engine` is the runtime kernel owner, not a CRUD backend API.
- `transport/` owns worker delivery and result-ingest data-plane mechanics.

For most new users, the first useful path is:

```text
register event
  -> register project
  -> declare WorkerGroup eventBindings
  -> register worker into the group
  -> create task shell
  -> append task items
  -> worker pulls or receives work
  -> worker submits result
  -> read stable-final results
```
