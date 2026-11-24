---
name: xa-mass-migration-audit
description: Audit completion of an agreed XA Mass migration for live old paths and stale callers, tests, configuration or current documentation. Use for requested migration closure or residue reviews, not ordinary task completion or general code review.
---

# XA Mass Migration Audit

Use the [change contract](../../../AGENTS.md) and the accepted migration scope,
whether recorded in conversation or a file. Establish the old entry/Owner,
replacement, affected callers and promised proof from the scoped diff and source.

## Follow The Old Path

Search the affected names and dependencies through production, tests,
configuration, packaging and current navigation. Inspect generated output only
when committed or when the delivery itself is under review.

A hit becomes a finding only after identifying the accepted invariant it violates,
the live caller or misleading current reference, and the consequence. Distinguish:

- Old behavior still served through an entry, fallback, alias or second writer.
- Tests, configuration or current prose that preserve superseded behavior.
- Fixed historical evidence, which is a problem only when used as current truth.
- Explicit non-goals or later slices outside this audit's completion criteria.

Judge evidence and projections against their Owner contract. Permitted lossy
qualification is not a reserved quota or an execution guarantee; names such as
Snapshot, Context or legacy do not establish a violation by themselves.

## Check Closure

Check promised guards detect the stated violation, including the wrong Owner
still serving the request. A passing happy path or a test for an old type name
does not establish the migrated boundary.

Report findings with source/evidence, consequence and disposition. Distinguish
inspected source, executed checks and missing proof using the
[evidence rules](../../../AGENTS.md#trust-order). Severity follows impact and
accepted completion criteria rather than a keyword or residue category.
