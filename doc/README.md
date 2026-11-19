# XA Mass Documentation

Status: current repository document index.

## Start Here

The [Chinese usage and development guide](guide/index.md) is the shared
human/Agent entrypoint: existing-environment operations, local experience, business use, troubleshooting,
development navigation and explicitly dated capability evidence. The Console
and Runtime/Preview packages publish the same source documents at `/docs/`.

The [Repository reading path](../README.md#reading-path) defines task-specific
routes and stopping conditions. This index locates document roles and Owners;
follow the relevant branch without treating the whole list as required reading.
Use the [Human Architecture Guide](guide/architecture.md) as a
visual projection of the same boundaries.

| Document | Information it owns |
| --- | --- |
| [Usage and Development Guide](guide/index.md) | User procedures, problem-oriented navigation and readable architecture; links to exact Owners and evidence |
| [Repository Entry](../README.md) | Short system summary, reading path, authority, module and deployment map |
| [Scheduling Mainline](kernel/scheduling-overview.md) | Complete Matching / Execution / Convergence model, work/resource feedback, Owner mapping and code/proof navigation |
| [Delivery Boundary](kernel/worker-delivery-dispatch.md) | Command and observation handoffs, local Owner links and cross-boundary failure windows |
| Module README and linked Owner documents | Local mechanism, transitions, storage, configuration and lifecycle |
| [Proof Registry](testing/proof-registry.md) | Primary proof, claim and deliberate nonclaims |
| [TESTING](../TESTING.md) | Proof commands, prerequisites and CI selection |
| Integration/scenario README | Complete workload, mutation sequence, thresholds and assertions |
| [AGENTS](../AGENTS.md) | Change constraints, scoped Owner reading and [evolution principles](../AGENTS.md#evolution-principles) |

Keep a mechanism's detailed definition with its Owner. Entry documents link to
that definition and explain the handoff instead of repeating local parameters.
When an entrypoint or contract changes, update its callers, Owner description
and navigation together. A source pointer is a reading aid; only an executed
proof provides a current validation result.

## Publishing And Maintenance

Guides and module documents are authored in place as Markdown. The documentation
site supplies navigation, search and rendering; generated pages are not a second
editable contract. Keep repository-relative source links usable on GitHub.
The site resolves published document links and source/evidence links at build time.

Update user steps when a flow changes and update the affected Owner alongside
behavior. Replace superseded statements in the same change; remove completed
todos and obsolete procedures. Distill reusable conclusions into their current
Owner or guide, with dated evidence where needed, instead of appending investigation
diaries or maintaining an archive directory. Do not copy complete DTOs, storage definitions, threshold tables or
proof assertions into guides. API reference generation and runtime evidence keep
their existing owners. Performance figures always identify their measured commit,
environment, status, metric definition and immutable record.

Current navigation and search exclude historical records. Necessary historical
references use a full immutable Git commit or published evidence, labelled with
their version and purpose; they are not current operating instructions. Raw proof
records and published artifacts remain unchanged when narrative documents are pruned.
Keep the active Release contract required by release tooling; older contract
files can leave the current tree after their live references are resolved.
No parallel overview HTML, hand-maintained document mirror or old URL compatibility
layer is maintained.

## Owner Documents

| Area | Owner entrypoints |
| --- | --- |
| Scheduling and resources | [Kernel mechanism/policy index](kernel/README.md), [Matching](../worker_matching_jvm/README.md) |
| Runtime API and executable | [Server](../server_jvm/README.md), [Boot profiles and assembly](../server_boot_jvm/README.md) |
| Delivery and Worker SDKs | [Transport index](../transport/README.md), [Event Catalog](../transport/EVENTS.md) |
| Worker Hosts | [Simulator](../worker_simulator_jvm/README.md), [Android](../xa-android/README.md) |
| Business scenarios | [SMS](../scenarios/sms-reception-jvm/README.md), [Messages](../scenarios/message-campaigns-jvm/README.md), [App Checks](../scenarios/app-checks-jvm/README.md) |
| UI and delivery | [Frontend](../frontend/README.md), [Runtime distribution](../distribution/server/README.md), [Preview launch](../distribution/server/PREVIEW.md) |
| Integration evidence | [Proof Registry](testing/proof-registry.md) links every lane to its responsible Integration/Scenario document. |
