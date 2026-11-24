---
name: roadmap-residue-scan
description: Audit an explicitly requested XA Mass migration or convergence completion for live old paths and stale callers, tests, configuration or documentation. Use the accepted change and its promised proof; ordinary task completion and general code review do not require this scan.
---

# Roadmap Residue Scan

Audit the accepted migration scope, whether recorded in conversation or a file.
Review-only requests remain read-only; an authorized implementation already
covers its necessary residue fixes. This skill adds no goal-tool requirement,
new plan or approval gate, and does not override the active execution mode.

## Trace The Replacement

Identify the intended invariant, old entry/Owner/names, replacement path,
affected callers and promised guards. Infer missing search terms from the scoped
diff and current code rather than requiring a new inventory. Search those names
and dependencies across production, tests, configuration, packaging and current
documentation, following callers where the migration crosses a boundary.

Exclude generated output unless it is committed or is itself the delivery under
review. Check active indexes and links as well as text. A full migration audit
covers its accepted completion criteria; a slice audit does not fail because a
later slice remains, and explicitly deferred non-goals stay outside the gate.

## Establish Each Finding

A search hit is a candidate, not a defect. For each finding, show the accepted
invariant it violates, the live caller or misleading current reference, and the
consequence. Names such as Snapshot, Context, state or legacy do not establish
an ownership violation on their own.

Distinguish:

- **Live old behavior:** an old entry, fallback, compatibility alias or second
  writer still supplies behavior that the accepted migration removes.
- **Test/configuration/documentation drift:** fixtures, build rules, commands,
  descriptions or status still preserve or prescribe the superseded behavior.
- **Historical evidence:** fixed-version records remain valid as history unless
  current navigation presents them as current truth.
- **Outside the accepted scope:** a deliberate non-goal or later slice is not
  blocking residue for this audit.

Assess projections and evidence against their explicit Owner contract; permitted
observation-based qualification is not the same as an unauthorized writer,
reserved quota or execution guarantee. Do not infer a defect from a type's size
or an implementation pattern alone.

Check promised guards actually detect the stated violation. A green happy-path
test may still pass with the old Owner serving the request. Guards should protect
the accepted behavior or boundary, not freeze temporary decomposition.

## Close The Requested Audit

Report actionable findings with evidence, impact and a concrete disposition.
Severity follows the consequence and acceptance criteria, not the category or
keyword. State the inspected scope and distinguish executed checks from source
inspection and outstanding proof; a review does not silently authorize running
external infrastructure or publishing findings.

For authorized fixes, remove only the connected residue, update affected callers
and Owner/navigation references, and run the checks selected by
[TESTING](../../../TESTING.md). Prose-only fixes do not activate runtime proof.
If a material mechanism choice remains unresolved, isolate that dependency
while completing independent authorized work. Do not mark the accepted outcome
complete while required cleanup or proof is still missing.
