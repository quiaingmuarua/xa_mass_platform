# Java External SDK Task Append Receipt Decision

Status: active deferred SDK decision.

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
