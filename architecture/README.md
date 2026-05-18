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

- SDK embedding: [`../xa-mass-sdk/README.md`](../xa-mass-sdk/README.md)
- HTTP/API contracts: [`../doc/INTERNAL_API_REFERENCE.md`](../doc/INTERNAL_API_REFERENCE.md)
- external worker protocol: [`../doc/EXTERNAL_WORKER_QUICKSTART.md`](../doc/EXTERNAL_WORKER_QUICKSTART.md)
- engine owner truth: [`../xa-mass-engine/README.md`](../xa-mass-engine/README.md)
- result boundary: [`../doc/RESULT_BOUNDARY_BASELINE.md`](../doc/RESULT_BOUNDARY_BASELINE.md)
- transport boundary: [`../transport/TRANSPORT_BOUNDARY_BASELINE.md`](../transport/TRANSPORT_BOUNDARY_BASELINE.md)

## One-Line Model

XA Mass Platform is a distributed task scheduling kernel for structured work
items:

```text
task shell
  -> append items with eventCode
  -> runtime schedules work
  -> worker executes by capability
  -> worker submits result
  -> runtime converges item and task state
```

The core value is not "store a task row". The core value is reliable runtime
convergence under worker matching, dispatch, retry, timeout, result callbacks,
and task-level terminal policy.

## Current Integration Bias

Use the SDK first:

- `xa-mass-sdk` is the recommended JVM embedding surface.
- `xa-mass-server` is the reference Boot host and HTTP validation shell.
- `xa-mass-engine` is the runtime kernel owner, not a CRUD backend API.
- `transport/` owns worker delivery and result-ingest data-plane mechanics.

For most new users, the first useful path is:

```text
register event
  -> register project
  -> register worker with eventBindings
  -> create task shell
  -> append task items
  -> worker pulls or receives work
  -> worker submits result
  -> read stable-final results
```
