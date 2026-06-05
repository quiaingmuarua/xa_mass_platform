# Java External SDK Task Append Receipt Decision

Archived on 2026-06-05 after the current append receipt constraint was moved
into `sdk/xa-mass-java-sdk/README.md` and
`sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md`.

Current truth owners:

- `sdk/xa-mass-java-sdk/README.md` for Java SDK public surface rules.
- `sdk/xa-mass-java-sdk/EXTERNAL_SDK_QUICKSTART.md` for task producer usage.

This document is historical context only. Do not use it as proof of current
implementation behavior; verify against current code, tests, SDK README, and
the external SDK quickstart.

Status: archived deferred SDK decision.

This short decision note replaces the implemented task-scoped invocation
roadmap in the active roadmap directory. The implemented record is archived at
`../doc/archive/sdk/2026-06-02_JAVA_EXTERNAL_SDK_TASK_SCOPED_INVOCATION_ROADMAP.md`.

Current decision:

- Do not add a Java SDK bulk append helper while `TaskAppendResult` lacks
  per-item message identity or equivalent append receipt identity.
- Keep task-scoped invocation on the implemented `TaskHandle` path:
  create one task, approve when needed, append or sync-append items, and read
  results through the task result APIs.
- If append receipts later expose item/message identity, a future helper may
  chunk appends against one existing task, must return identity-preserving
  receipts, and must be adopted by scenario-launcher in the same slice.
- The helper must not create one task per item and must not auto-seal unless
  the method name explicitly says it seals.

Verification before reopening:

```powershell
rg -n "record TaskAppendResult|chunks\\(|appendItems\\(" sdk/xa-mass-java-sdk integrations/xa-mass-scenario-launcher
```
