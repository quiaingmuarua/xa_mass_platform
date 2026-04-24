# Gateway Queue Package Baseline

This package contains gateway-side message codec and transport helpers.

## Current Files

- `WebSocketTransportFrameCodec`: payload serialization and parsing helper for the current WebSocket adapter.
- `RedisEnvelopeQueue`: placeholder for non-memory queue work; not part of the verified mainline.

## Current Mainline Reality

- The verified runtime path is still the in-memory/embedded path.
- This package helps bridge WebSocket gateway traffic with the current raw-json adapter pipeline.
- It does not define task lifecycle, assignment, or result semantics.
- Canonical `eventCode` diagnostics come from explicit root fields on control/task frames only; transport decoding must not invent capability identity from legacy frame metadata.
- Connection dispatch now keys off `workerId` only. The active gateway endpoint model no longer carries separate role/lane routing.
- `project` is optional scope metadata only; do not inject a synthetic default project in transport helpers.

## Working Rule

- Keep this package note limited to current files and active role.
- Do not preserve refactor summaries or migration stories here.
- If queue backends become real runtime paths, document that in code and in the runbook, not via historical design notes.
