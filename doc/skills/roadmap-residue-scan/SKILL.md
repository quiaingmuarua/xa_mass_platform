---
name: roadmap-residue-scan
description: Scan for leftover residue after roadmap slices or boundary migrations, including old imports, old names, stale docs, compatibility aliases, duplicate owner paths, stale roadmap status, unarchived completed roadmaps, and tests preserving removed vocabulary. Use when the user asks to audit completion, scan residue, check old symbols, verify cleanup after a roadmap, classify many roadmaps for archive/readiness, or find stale compatibility paths before declaring work complete.
---

# Roadmap Residue Scan

Use this skill after a roadmap slice, phase, or full roadmap appears complete.
The goal is to find old paths that would let the architecture decay after the
main work lands.

This skill is scan-first. Do not edit files unless the user asks to fix the
residue.

## Mode Rule

- **Scan mode**: report findings only. Use when the user asks to scan, audit,
  review completion, classify, or check residue.
- **Fix mode**: edit files only when the user explicitly asks to fix residue.
- **Completion gate mode**: when used at the end of a roadmap goal, scan first;
  only mark the goal complete if no blocking residue remains.

If a scan finds that the roadmap itself is wrong or the owner boundary is
unclear, hand back to `roadmap-refinement` instead of fixing blindly.

## Scan Inputs

Identify:

- roadmap path and target slices
- scan scope: slice, phase, full roadmap, or portfolio
- old symbol names and new symbol names
- old package/import paths and new owner paths
- old route names and new route names
- old doc vocabulary and intended replacement vocabulary
- compatibility aliases or fallback paths that should be gone
- verification commands from the roadmap

When names are not listed, infer candidates from commits, inventory docs,
roadmap acceptance criteria, and old/new class names.

## Scan Scope

Choose the scan scope before searching.

| Scope | Use When | Scan Boundary |
| --- | --- | --- |
| slice scan | one slice just landed | only that slice's acceptance, symbols, docs, and promised guards |
| phase scan | several consecutive slices landed | the phase's combined acceptance plus cross-slice residue |
| full-roadmap scan | roadmap appears complete | full completion gate, status/proof/archive/guard checks |
| portfolio scan | many roadmaps need classification | roadmap state table, stale status, replacement links, archive readiness |

Do not fail a slice scan because future-slice residue still exists. Do fail a
full-roadmap scan if any future-slice residue remains.

## Residue Classes

Classify every hit:

| Class | Meaning | Default Action |
| --- | --- | --- |
| production residue | old path remains in main code | blocking finding |
| test fixture residue | tests still use old vocabulary or old owner path | fix unless intentionally testing compatibility |
| doc residue | docs describe removed or stale path | fix or archive |
| compatibility alias | old and new paths both live | blocking unless external compatibility is required |
| fallback path | code silently falls back to old owner/source | blocking for boundary work |
| generated/build artifact | target/classes, reports, generated output | ignore unless committed |
| historical archive | under archive or explicitly historical | report only if it is referenced as active |
| stale status | roadmap status disagrees with code/proof | fix status or flag |
| duplicate owner | two modules define the same contract or truth | blocking finding |

## Scan Procedure

1. Read the relevant roadmap slice or roadmap-wide acceptance criteria,
   Non-Goals, "Do Not Start With" notes, inventories, and verification
   section.
2. Search source, tests, docs, and build files for old names and old imports.
   Prefer `rg`.
3. Exclude generated output such as `target/`, build reports, caches, and local
   IDE files unless they are intentionally committed source.
4. Check current docs and indexes for stale active references. Treat archive
   docs as historical unless active docs link to them as current truth.
5. Check roadmap status against source code, tests, guards, and recent commits
   when available.
6. Check the roadmap's own `Known gaps`, `current site`, `Scope`, and
   verification text against the implementation. A completed issue still
   described as current residue is doc residue.
7. Check for compatibility aliases, adapters, wrappers, and fallbacks that keep
   the old path alive.
8. Check tests for hidden compatibility tracks: old helper names, old route
   names, old storage names, or assertions against removed read models.
9. Verify guards promised by the relevant slice or guard/proof section:
   - the guard exists as an actual test or build rule
   - it checks the stated violation condition, not only happy path behavior
   - the roadmap verification commands include or reference the guard
10. Run or recommend the roadmap's verification commands. Missing, stale, or
    broken verification commands are residue.

If review findings are persisted in files, PR comments, issue comments, or a
roadmap review section, check whether the findings required before execution
were resolved. If review findings exist only in chat history and are not
available as artifacts, do not invent them.

## Roadmap Portfolio Classification

When asked to classify many roadmaps, use code evidence, not only status lines.

Use this table:

```markdown
| Roadmap | Status Line | Evidence | Class | Next Action |
| --- | --- | --- | --- | --- |
```

Classes:

- `proposed`: direction exists, no meaningful implementation found
- `active`: slices are in progress
- `implemented`: acceptance appears satisfied and no blocking residue found
- `implemented-with-residue`: mainline done, residue remains
- `superseded`: newer roadmap owns the direction
- `blocked`: owner decision, dependency, or proof gap blocks execution
- `stale-status`: status line conflicts with code/proof
- `historical`: archived or obsolete context only

Map residue findings to portfolio classes:

- blocking residue and unmet mainline acceptance -> `active` or `blocked`
- blocking residue but mainline acceptance appears satisfied ->
  `implemented-with-residue`
- medium residue remains -> usually `implemented-with-residue`
- only low archive/doc wording remains -> `implemented` with follow-up, unless
  the stale doc is still referenced as current truth
- zero blocking/medium residue and proof is present -> `implemented`
- status conflicts with code/proof -> `stale-status`

Do not move a roadmap to archive merely because some code landed. Archive only
after replacement links, proof, status, and residue are resolved.

## Output Format

Use concise output for small scans. Use full findings for multi-module or
completion-gate scans.

```markdown
## Residue Scan

Scope:
- scanScope: slice | phase | full-roadmap | portfolio
- roadmap/slice/symbols: <items scanned>

Findings:

**R1 - <title>**
Severity: Blocking | Medium | Low
Evidence: <file:line or command result>
Class: <residue class>
Recommendation: <specific fix or disposition>

Summary:

| Finding | Severity | Class | Blocks completion? |
| --- | --- | --- | --- |

Conclusion: <complete / complete after fixes / not complete>
```

Severity meanings:

- **Blocking**: production residue, duplicate owner, fallback path,
  compatibility alias, or stale status that would mislead execution.
- **Medium**: test/doc residue likely to confuse future work, but not a live
  production path.
- **Low**: archive-only references, wording cleanup, or optional clarity.

## Fix Mode Rules

When the user asks to fix residue:

- Fix only residue connected to the requested roadmap/slice.
- Do not redesign the owner boundary; return to roadmap refinement if needed.
- Remove old paths instead of preserving aliases unless external compatibility
  is explicitly required.
- Update guards when possible so the same residue cannot return.
- Update docs/indexes when status or active references change.
- Run focused verification after edits.

## Common Search Patterns

Search for:

- old class/interface names
- old package prefixes
- old route paths
- old method names
- old status labels
- old roadmap titles
- `compat`, `compatibility`, `legacy`, `fallback`, `alias`, `deprecated`
- old module artifact ids in build files
- test helper names that preserve old vocabulary

For Java/Maven repos, also check:

- `pom.xml` dependency scope
- production imports vs test imports
- architecture guard tests
- generated target output excluded from findings

## Completion Gate Checklist

Before declaring a roadmap complete:

- No blocking production residue remains.
- No duplicate owner path remains.
- No compatibility alias remains unless explicitly required.
- Tests do not preserve old vocabulary as a second API.
- Current docs and indexes point to the new owner/path.
- The roadmap itself no longer describes resolved work as a current gap.
- Roadmap status matches code and proof.
- Archive docs are not referenced as current truth.
- Promised guards exist and test the stated violation conditions.
- Verification commands pass.
- Skipped commands are acceptable only for named infrastructure limitations,
  such as Docker or an external database being unavailable. Missing test
  classes, stale commands, compilation failures, or actual test failures are
  blocking residue.
