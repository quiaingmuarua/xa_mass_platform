# Legacy Testing Design Assets

Status: historical design asset only.

This document records transferable proof practices from the superseded Java
platform. It is not a current test inventory. Complete source is preserved by
`legacy-java-platform-final-2026-07-24`.

## Proof Classes

- **Product/API capability:** proves that a supported external caller can
  complete a named workflow through real process boundaries.
- **Policy and safety correctness:** proves owner invariants, stale-fence
  behavior, authorization boundaries, and fail-closed decisions.
- **Scoped operational resilience:** proves one named load, restart, timeout,
  disconnect, replay, or contention condition with an explicit oracle.

End-to-end is an evidence shape, not a proof class. A large E2E suite does not
replace focused owner proofs.

## Transferable Practices

- Name the invariant and authoritative proof surface before adding a test.
- Keep deterministic owner tests separate from real Redis integration.
- Run startup-level proof for composition and lifecycle changes.
- Record load and chaos parameters with their result artifacts.
- Distinguish executed proof from source guards, schema guards, skipped tests,
  and artifact metadata.
- Treat test counts as inventory, not confidence.
- Use trace evidence to explain a verified runtime scenario, not to substitute
  for runtime truth.
- Keep expensive soak, chaos, and performance lanes scheduled or manual until
  calibrated; keep fast deterministic safety checks in the normal gate.

## Do Not Reuse Directly

- Harnesses that instantiate the superseded engine, embedded SDK, runtime, or
  transport composition.
- Old scenario identifiers, lifecycle expectations, HTTP routes, credential
  families, and report schemas.
- Projection-first assertions when the new owner exposes direct runtime truth.

New Kotlin proof infrastructure should be introduced only with the owner slice
it verifies and should retain the Python executable specification as the parity
oracle during migration.
