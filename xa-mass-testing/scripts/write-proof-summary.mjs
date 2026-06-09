#!/usr/bin/env node
// Write a CI proof summary from existing test and lane artifacts.
//
// The summary is evidence about what this job produced. It is not runtime truth
// and it must not replace the owning test/report artifacts.

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, "../..");
const DEFAULT_OUTPUT = path.join(REPO_ROOT, "xa-mass-testing", "target", "proof-summary", "summary.json");
const RELEASE_EVIDENCE_FILE = path.join(REPO_ROOT, "xa-mass-testing", "proof", "perf-soak-release-evidence.json");

const PROOF_CLASSES = {
  PRODUCT_API_CAPABILITY: {
    id: "product-api-capability",
    name: "Can It Be Used / Product API Capability Proof",
    plainName: "Product / API Capability Proof",
    question: "Can it be used through supported external surfaces?",
    proves: "Supported external product/API paths can initialize, authenticate, create work, run workers, and read results.",
    primaryEntrances: ["server jar", "admin CLI", "Java SDK task producer", "Java SDK or worker-api worker process", "result verifier"],
    claimDiscipline: "A green path proves usability only, including that a valid credential/session on an allowed route/scope is not wrongly rejected. It does not prove that unsafe paths are rejected.",
    doesNotProve: "Full policy, authorization, scheduling, or scale correctness.",
  },
  POLICY_SAFETY: {
    id: "policy-safety-correctness",
    name: "Can It Be Wrong / Policy & Safety Correctness Proof",
    plainName: "Policy & Safety Correctness Proof",
    question: "Can it bind, authorize, schedule, or mutate incorrectly?",
    proves: "Scheduling, worker selection, authorization, scope, credential, readiness, occupancy, and no-bypass behavior are correct.",
    primaryEntrances: ["engine deterministic tests (primary)", "representative server E2E", "negative auth tests", "trace analyzers"],
    claimDiscipline: "This is the highest-priority confidence class. Policy correctness is engine deterministic proof first; representative server E2E proves real wiring only and must not become a full matrix. Happy-path E2E, chaos, perf, or soak labels do not replace deterministic no-bypass and negative proof.",
    doesNotProve: "High-volume or distributed-runtime convergence unless a scoped resilience report is present.",
  },
  SCOPED_OPERATIONAL_RESILIENCE: {
    id: "scoped-operational-resilience",
    name: "Can It Withstand This / Scoped Operational Resilience Proof",
    plainName: "Scoped Operational Resilience Proof",
    question: "Can it withstand this named load, fault, runtime, duration, and oracle?",
    proves: "Runtime convergence for the explicitly named concurrency, retry, duplicate delivery, stale callback, worker churn, lease expiry, restart, Redis/runtime, or high-volume scenario.",
    primaryEntrances: ["scale/contention reports", "chaos/fault reports", "perf", "soak", "packaged-process restart"],
    claimDiscipline: "Do not claim general resilience, chaos, perf, soak, or production capacity from a report unless the exact condition and pass/fail oracle are present.",
    doesNotProve: "A full route-permission matrix, complete product-path usability, production SLOs, Redis HA, process kill, partition/failover, lease-clock skew, or multi-node presence flap unless the evidence explicitly names and asserts that condition.",
  },
};

const PROOF_LINES = {
  OPERATOR_ADMIN_SESSION: {
    id: "operator-admin-session",
    proofClass: PROOF_CLASSES.PRODUCT_API_CAPABILITY.id,
    owns: "Operator login, project/rule sync, API-key approval, task seal/approve, and operator commands with a valid operator session that must not be wrongly rejected.",
  },
  TASK_PRODUCER_API_KEY: {
    id: "task-producer-api-key",
    proofClass: PROOF_CLASSES.PRODUCT_API_CAPABILITY.id,
    owns: "Task create, item append, and allowed task/result/archive reads through task producer surfaces with a valid task API key that must not be wrongly rejected.",
  },
  WORKER_API_KEY: {
    id: "worker-api-key",
    proofClass: PROOF_CLASSES.PRODUCT_API_CAPABILITY.id,
    owns: "Worker registration/topology, online/heartbeat/poll, result submit, command ack, state report, and capability report with a valid worker API key that must not be wrongly rejected.",
  },
  SCHEDULING_POLICY_CORRECTNESS: {
    id: "scheduling-policy-correctness",
    proofClass: PROOF_CLASSES.POLICY_SAFETY.id,
    owns: "WorkerGroup, eventCode capability, target/attributes, candidate buckets, readiness, occupancy, capacity, locks, and policy-sensitive retry/wakeup/lease-expiry selection.",
  },
  LIFECYCLE_RESULT_CORRECTNESS: {
    id: "lifecycle-result-correctness",
    proofClass: PROOF_CLASSES.POLICY_SAFETY.id,
    owns: "Lifecycle transition, retry/finality, resource release, result convergence, duplicate callback, and stale callback correctness.",
  },
  AUTHORIZATION_NO_BYPASS_SAFETY: {
    id: "authorization-no-bypass-safety",
    proofClass: PROOF_CLASSES.POLICY_SAFETY.id,
    owns: "Negative auth/security cases for missing session, wrong credential family, scope mismatch, CSRF failure, fixture/dev header exclusion, and impersonation.",
  },
  SCALE_CONTENTION_EVIDENCE: {
    id: "scale-contention-evidence",
    proofClass: PROOF_CLASSES.SCOPED_OPERATIONAL_RESILIENCE.id,
    owns: "Named concurrency, worker contention, capacity/exclusive-lock pressure, Redis runtime contention, large batch, or many-worker scenarios.",
  },
  FAULT_RECOVERY_EVIDENCE: {
    id: "fault-recovery-evidence",
    proofClass: PROOF_CLASSES.SCOPED_OPERATIONAL_RESILIENCE.id,
    owns: "Named restart, reconnect, worker churn, lease-expiry redispatch, duplicate result, stale callback, delayed/retry recovery, or runtime/process fault scenarios.",
  },
};

const SUITE_CLASSIFICATION = {
  EngineSchedulingCoreSuite: {
    proofClass: PROOF_CLASSES.POLICY_SAFETY.id,
    proofLines: [PROOF_LINES.SCHEDULING_POLICY_CORRECTNESS.id],
    evidenceShape: "deterministic-engine-suite",
    gateType: "pr-gate",
    claimScope: "primary deterministic scheduling/policy matrix",
    gate: "scheduling primary proof",
    criticalInvariantIds: [
      "sched.worker-eligibility-routing",
      "sched.worker-state-dimensions",
      "sched.min-worker-gate",
      "sched.retry-redispatch",
      "sched.policy-binding-entry-bypass",
      "sched.background-sharing",
      "sched.cross-task-fairness",
      "sched.late-worker-backfill",
    ],
    knownNonProofBoundaries: [
      "Not packaged-process API/auth proof.",
      "Not a distributed infra-fault harness.",
    ],
  },
  EngineKernelConvergenceSuite: {
    proofClass: PROOF_CLASSES.POLICY_SAFETY.id,
    proofLines: [PROOF_LINES.LIFECYCLE_RESULT_CORRECTNESS.id],
    evidenceShape: "deterministic-engine-convergence-suite",
    gateType: "pr-gate",
    claimScope: "primary deterministic lifecycle/result convergence matrix",
    gate: "kernel convergence primary proof",
    criticalInvariantIds: [
      "kernel.duplicate-callback-idempotence",
      "kernel.result-terminal-convergence",
      "kernel.resource-release-reuse",
    ],
    knownNonProofBoundaries: [
      "Not packaged-process API/auth proof.",
      "Not default server startup proof.",
    ],
  },
  ServerSchedulingE2eSuite: {
    proofClass: PROOF_CLASSES.POLICY_SAFETY.id,
    proofLines: [PROOF_LINES.SCHEDULING_POLICY_CORRECTNESS.id],
    evidenceShape: "representative-server-e2e",
    gateType: "pr-gate",
    claimScope: "representative real-wiring scheduling proof only",
    gate: "server scheduling representative proof",
    criticalInvariantIds: [
      "sched.worker-eligibility-routing",
      "sched.min-worker-gate",
      "sched.retry-redispatch",
      "sched.background-sharing",
      "sched.cross-task-fairness",
      "sched.late-worker-backfill",
    ],
    knownNonProofBoundaries: [
      "Representative host proof only; engine suite remains primary scheduling proof.",
      "Do not expand this suite into the full policy matrix; add or strengthen engine deterministic proof first.",
      "Not a full route-permission matrix.",
    ],
  },
  ServerLifecycleResultConvergenceSuite: {
    proofClass: PROOF_CLASSES.POLICY_SAFETY.id,
    proofLines: [PROOF_LINES.LIFECYCLE_RESULT_CORRECTNESS.id],
    evidenceShape: "representative-server-e2e",
    gateType: "pr-gate",
    claimScope: "representative real-wiring lifecycle/result proof only",
    gate: "server lifecycle/result representative proof",
    criticalInvariantIds: [
      "kernel.duplicate-callback-idempotence",
      "kernel.result-terminal-convergence",
      "kernel.resource-release-reuse",
    ],
    knownNonProofBoundaries: [
      "Representative host proof only; engine suite remains primary kernel proof.",
      "Do not expand this suite into the full lifecycle matrix; add or strengthen engine deterministic proof first.",
      "Not active-profile packaged-process confidence.",
    ],
  },
  ExternalWorkerParitySuite: {
    proofClass: PROOF_CLASSES.PRODUCT_API_CAPABILITY.id,
    proofLines: [PROOF_LINES.TASK_PRODUCER_API_KEY.id, PROOF_LINES.WORKER_API_KEY.id],
    evidenceShape: "cross-language-black-box",
    gateType: "pr-gate",
    gate: "external worker parity proof",
    criticalInvariantIds: ["ext.worker-parity-public-contract"],
    knownNonProofBoundaries: ["Not a full SDK transport fault matrix."],
  },
  ProofRegistryClosureGuardTest: {
    proofClass: PROOF_CLASSES.POLICY_SAFETY.id,
    proofLines: [
      PROOF_LINES.SCHEDULING_POLICY_CORRECTNESS.id,
      PROOF_LINES.LIFECYCLE_RESULT_CORRECTNESS.id,
      PROOF_LINES.AUTHORIZATION_NO_BYPASS_SAFETY.id,
    ],
    evidenceShape: "proof-registry-source-guard",
    gateType: "pr-gate",
    gate: "proof registry closure guard",
    criticalInvariantIds: [],
    knownNonProofBoundaries: [
      "Proof-map guard only; it does not execute the named proof scenarios.",
    ],
  },
  WorkerFaultScenarioIndexTest: {
    proofClass: PROOF_CLASSES.SCOPED_OPERATIONAL_RESILIENCE.id,
    proofLines: [PROOF_LINES.FAULT_RECOVERY_EVIDENCE.id],
    evidenceShape: "scenario-ledger-source-guard",
    gateType: "pr-gate",
    gate: "worker fault scenario index guard",
    criticalInvariantIds: [],
    knownNonProofBoundaries: [
      "Scenario metadata guard only; it does not execute chaos scenarios.",
    ],
  },
  WorkerFaultReportMetadataTest: {
    proofClass: PROOF_CLASSES.SCOPED_OPERATIONAL_RESILIENCE.id,
    proofLines: [PROOF_LINES.FAULT_RECOVERY_EVIDENCE.id],
    evidenceShape: "report-schema-source-guard",
    gateType: "pr-gate",
    gate: "worker fault report metadata guard",
    criticalInvariantIds: [],
    knownNonProofBoundaries: [
      "Report-shape guard only; it does not execute chaos scenarios.",
    ],
  },
  PlatformConfidenceProfileMatrixGuardTest: {
    proofClass: PROOF_CLASSES.PRODUCT_API_CAPABILITY.id,
    proofLines: [
      PROOF_LINES.OPERATOR_ADMIN_SESSION.id,
      PROOF_LINES.TASK_PRODUCER_API_KEY.id,
      PROOF_LINES.WORKER_API_KEY.id,
    ],
    evidenceShape: "profile-matrix-source-guard",
    gateType: "pr-gate",
    gate: "platform confidence profile guard",
    criticalInvariantIds: [],
    knownNonProofBoundaries: [
      "Profile/matrix/schema guard only; packaged process smokes carry the runtime proof.",
    ],
  },
  ServerStartupProfileSuite: {
    proofClass: PROOF_CLASSES.PRODUCT_API_CAPABILITY.id,
    proofLines: [PROOF_LINES.OPERATOR_ADMIN_SESSION.id],
    evidenceShape: "spring-profile-context-suite",
    gateType: "pr-gate",
    gate: "server startup profile context support",
    criticalInvariantIds: [],
    knownNonProofBoundaries: [
      "Spring context support proof only; packaged no-arg startup smoke carries process proof.",
    ],
  },
  ProofSummaryWorkflowGuardTest: {
    proofClass: null,
    evidenceShape: "proof-summary-schema-source-guard",
    gateType: "pr-gate",
    gate: "proof summary schema guard",
    criticalInvariantIds: [],
    knownNonProofBoundaries: [
      "Summary schema guard only; it does not execute platform behavior.",
    ],
  },
  PerfSoakReleaseEvidenceGuardTest: {
    proofClass: PROOF_CLASSES.SCOPED_OPERATIONAL_RESILIENCE.id,
    proofLines: [PROOF_LINES.SCALE_CONTENTION_EVIDENCE.id],
    evidenceShape: "release-evidence-policy-guard",
    gateType: "pr-gate",
    gate: "perf/soak release evidence guard",
    criticalInvariantIds: [],
    knownNonProofBoundaries: [
      "Release-evidence policy guard only; scheduled/manual reports carry runtime proof.",
    ],
  },
};

const CHAOS_SCENARIO_INVARIANTS = {
  "polling-lease-expiry-redispatch": ["sched.retry-redispatch"],
  "fault.dropped-result-retry": ["sched.retry-redispatch"],
  "websocket-lease-expiry-redispatch": ["sched.retry-redispatch"],
  "websocket-late-stale-result-replay": ["kernel.duplicate-callback-idempotence"],
  "lease-expiry-redispatch": ["sched.retry-redispatch"],
  "late-stale-result-replay": ["kernel.duplicate-callback-idempotence"],
  "all-failed-terminal-convergence": ["kernel.result-terminal-convergence"],
  "mixed-result-terminal-convergence": ["kernel.result-terminal-convergence"],
};

const GLOBAL_NON_PROOF_BOUNDARIES = [
  "A happy-path product flow proves that a supported path can be used; it does not prove unsafe routes, credentials, scheduling entries, or lifecycle mutations fail closed.",
  "Capability proof must name the credential/session family it exercised: operator-admin-session, task-producer-api-key, or worker-api-key.",
  "A valid credential/session on an allowed route, scope, project, event, and request shape being wrongly rejected is a Product/API Capability failure, not an authorization no-bypass success.",
  "Policy and safety correctness is the primary confidence question when a change can bind the wrong worker, authorize the wrong caller, bypass owner policy, or mutate the wrong lifecycle state.",
  "Policy correctness is engine deterministic proof first. Server E2E is representative real-wiring proof and must not be expanded into a full policy or lifecycle matrix.",
  "Authorization and no-bypass proof requires negative cases; a successful task producer or worker path is not evidence that the wrong credential, scope, route family, CSRF state, or fixture header fails closed.",
  "Chaos, perf, and soak labels are not broad confidence claims. They prove only the named scenario, load/fault, runtime, duration, and pass/fail oracle captured by the owning report.",
  "Redis process kill, partition/failover, lease-clock skew, and multi-node presence flap are not current proof unless a report explicitly names a deterministic infra-fault harness.",
  "Frontend workflow success is frontend quality and adapter-consumption evidence only, not server route/auth/kernel proof.",
  "Perf and soak reports are scheduled/manual evidence unless the workflow that produced this summary is the scheduled/manual perf or soak workflow.",
  "Proof summaries are CI evidence and do not replace doc/PROOF_REGISTRY.md or the owning test/report artifact.",
];

const args = parseArgs(process.argv.slice(2));
const output = path.resolve(REPO_ROOT, args.output ?? DEFAULT_OUTPUT);
const releaseEvidenceConfig = readJson(RELEASE_EVIDENCE_FILE) ?? {};
const releaseEvidenceByScenario = releaseEvidenceMap(releaseEvidenceConfig);
const evidence = [
  ...collectSurefireReports(),
  ...collectPlatformConfidenceSummaries(),
  ...collectDefaultStartupSummaries(),
  ...collectReportJson("chaos", args.chaosDirs ?? [path.join(REPO_ROOT, "xa-mass-testing", "target", "chaos-reports")]),
  ...collectReportJson("perf", args.perfDirs ?? [path.join(REPO_ROOT, "xa-mass-testing", "target", "perf-reports")]),
  ...collectReportJson("soak", args.soakDirs ?? [path.join(REPO_ROOT, "xa-mass-testing", "target", "soak-reports")]),
];

const summary = {
  schemaVersion: 1,
  generatedAt: new Date().toISOString(),
  workflow: args.workflow ?? process.env.GITHUB_WORKFLOW ?? "local",
  job: args.job ?? process.env.GITHUB_JOB ?? "local",
  github: {
    runId: process.env.GITHUB_RUN_ID ?? null,
    runAttempt: process.env.GITHUB_RUN_ATTEMPT ?? null,
    sha: process.env.GITHUB_SHA ?? null,
    ref: process.env.GITHUB_REF ?? null,
  },
  totals: totals(evidence),
  proofClassDefinitions: Object.values(PROOF_CLASSES),
  proofLineDefinitions: Object.values(PROOF_LINES),
  scheduledManualEvidence: scheduledManualEvidence(evidence),
  releaseEvidencePolicy: releaseEvidenceConfig.workflowPolicy ?? null,
  knownNonProofBoundaries: GLOBAL_NON_PROOF_BOUNDARIES,
  evidence,
};

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, `${JSON.stringify(summary, null, 2)}\n`, "utf8");
console.log(`wrote proof summary: ${relative(output)}`);

function parseArgs(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (value === "--workflow") {
      parsed.workflow = argv[++index];
    } else if (value.startsWith("--workflow=")) {
      parsed.workflow = value.slice("--workflow=".length);
    } else if (value === "--job") {
      parsed.job = argv[++index];
    } else if (value.startsWith("--job=")) {
      parsed.job = value.slice("--job=".length);
    } else if (value === "--output") {
      parsed.output = argv[++index];
    } else if (value.startsWith("--output=")) {
      parsed.output = value.slice("--output=".length);
    } else if (value === "--test-report-dir") {
      pushArg(parsed, "testReportDirs", argv[++index]);
    } else if (value.startsWith("--test-report-dir=")) {
      pushArg(parsed, "testReportDirs", value.slice("--test-report-dir=".length));
    } else if (value === "--platform-confidence-dir") {
      pushArg(parsed, "platformConfidenceDirs", argv[++index]);
    } else if (value.startsWith("--platform-confidence-dir=")) {
      pushArg(parsed, "platformConfidenceDirs", value.slice("--platform-confidence-dir=".length));
    } else if (value === "--server-default-startup-dir") {
      pushArg(parsed, "serverDefaultStartupDirs", argv[++index]);
    } else if (value.startsWith("--server-default-startup-dir=")) {
      pushArg(parsed, "serverDefaultStartupDirs", value.slice("--server-default-startup-dir=".length));
    } else if (value === "--chaos-dir") {
      pushArg(parsed, "chaosDirs", argv[++index]);
    } else if (value.startsWith("--chaos-dir=")) {
      pushArg(parsed, "chaosDirs", value.slice("--chaos-dir=".length));
    } else if (value === "--perf-dir") {
      pushArg(parsed, "perfDirs", argv[++index]);
    } else if (value.startsWith("--perf-dir=")) {
      pushArg(parsed, "perfDirs", value.slice("--perf-dir=".length));
    } else if (value === "--soak-dir") {
      pushArg(parsed, "soakDirs", argv[++index]);
    } else if (value.startsWith("--soak-dir=")) {
      pushArg(parsed, "soakDirs", value.slice("--soak-dir=".length));
    } else {
      throw new Error(`unknown argument: ${value}`);
    }
  }
  return parsed;
}

function pushArg(parsed, key, value) {
  if (!parsed[key]) {
    parsed[key] = [];
  }
  parsed[key].push(value);
}

function collectSurefireReports() {
  const reportDirs = scopedDirs(args.testReportDirs, [REPO_ROOT]);
  const reports = unique(reportDirs.flatMap((dir) => walk(dir)))
    .filter((file) => {
      const normalized = slash(file);
      return /\/target\/(surefire-reports[^/]*|failsafe-reports[^/]*)\/TEST-[^/]+\.xml$/.test(normalized)
          || /\/TEST-[^/]+\.xml$/.test(normalized);
    });
  return reports.map(parseSurefireXml).filter(Boolean);
}

function parseSurefireXml(file) {
  const source = safeRead(file);
  if (source == null) {
    return null;
  }
  const suiteName = xmlAttr(source, "name") ?? path.basename(file).replace(/^TEST-/, "").replace(/\.xml$/, "");
  const testcaseCount = countMatches(source, /<testcase\b/g);
  const tests = numberOrDefault(xmlAttr(source, "tests"), testcaseCount);
  const failures = numberOrDefault(xmlAttr(source, "failures"), 0);
  const errors = numberOrDefault(xmlAttr(source, "errors"), 0);
  const skipped = numberOrDefault(xmlAttr(source, "skipped"), 0);
  const classification = classifySuite(suiteName, file);
  return {
    type: "surefire",
    status: failures === 0 && errors === 0 ? "passed" : "failed",
    suite: suiteName,
    testcaseCount: tests,
    failures,
    errors,
    skipped,
    proofClass: classification.proofClass ?? null,
    proofLines: classification.proofLines ?? [],
    proofQuestion: proofQuestionFor(classification.proofClass),
    evidenceShape: classification.evidenceShape ?? "surefire-suite",
    gateType: classification.gateType ?? "job-local-test",
    credentialRouteFamilies: [],
    claimScope: classification.claimScope ?? "suite-local-evidence",
    gate: classification.gate ?? null,
    criticalInvariantIds: classification.criticalInvariantIds ?? [],
    knownNonProofBoundaries: classification.knownNonProofBoundaries ?? [],
    artifactPath: relative(file),
  };
}

function collectPlatformConfidenceSummaries() {
  const bases = scopedDirs(args.platformConfidenceDirs, [
    path.join(REPO_ROOT, "xa-mass-testing", "target", "platform-confidence"),
  ]);
  return bases.flatMap(collectSummaryJson).map((entry) => {
    const data = entry.data;
    return {
      type: "platform-confidence",
      status: data.status ?? "unknown",
      proofClass: PROOF_CLASSES.PRODUCT_API_CAPABILITY.id,
      proofLines: [
        PROOF_LINES.OPERATOR_ADMIN_SESSION.id,
        PROOF_LINES.TASK_PRODUCER_API_KEY.id,
        PROOF_LINES.WORKER_API_KEY.id,
      ],
      proofQuestion: proofQuestionFor(PROOF_CLASSES.PRODUCT_API_CAPABILITY.id),
      evidenceShape: "packaged-process-external-api-smoke",
      gateType: "pr-gate",
      credentialRouteFamilies: credentialRouteFamiliesFrom(data),
      authorizedPositiveChecks: authorizedPositiveChecksFrom(data),
      claimScope: "external-product-path-smoke",
      profile: data.profile ?? null,
      authMode: data.authMode ?? null,
      operatorHeaderSupported: valueOrNull(data.operatorHeaderSupported),
      fixtureHeaderDisabled: valueOrNull(data.fixtureHeaderDisabled),
      sessionCookieSupported: valueOrNull(data.sessionCookieSupported),
      adminRouteFamilies: data.adminRouteFamilies ?? [],
      sdkRouteFamilies: data.sdkRouteFamilies ?? [],
      credentialFamilies: ["operator-session", "task-api-key", "worker-api-key"],
      credentialChecks: credentialChecksFrom(data.credentialChecks),
      confidenceOverlay: data.confidenceOverlay ?? {},
      knownNonProofBoundaries: [
        "Active-profile API/auth confidence only; no-arg default startup is separate server-default-startup proof.",
        "Representative credential checks only; full route-permission matrix remains owned by API contract health.",
      ],
      artifactPath: relative(entry.file),
      runDir: data.runDir ?? null,
    };
  });
}

function credentialChecksFrom(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return {};
  }
  return Object.fromEntries(Object.entries(raw).map(([name, value]) => {
    if (value && typeof value === "object" && !Array.isArray(value)) {
      return [name, {
        status: value.status ?? "unknown",
        httpStatus: value.httpStatus ?? null,
        code: value.code ?? null,
        failureReason: value.failureReason ?? null,
        proofLine: PROOF_LINES.AUTHORIZATION_NO_BYPASS_SAFETY.id,
        claimScope: "representative credential-family fail-closed check",
      }];
    }
    return [name, {
      status: value ?? "unknown",
      httpStatus: null,
      code: null,
      failureReason: null,
      proofLine: PROOF_LINES.AUTHORIZATION_NO_BYPASS_SAFETY.id,
      claimScope: "representative credential-family fail-closed check",
    }];
  }));
}

function credentialRouteFamiliesFrom(data) {
  const adminRoutes = Array.isArray(data.adminRouteFamilies) ? data.adminRouteFamilies : [];
  const sdkRoutes = Array.isArray(data.sdkRouteFamilies) ? data.sdkRouteFamilies : [];
  const taskRoutes = sdkRoutes.filter((route) => String(route).startsWith("/api/v1/tasks"));
  const workerRoutes = sdkRoutes.filter((route) => String(route).startsWith("/worker-api/"));
  return [
    {
      proofLine: PROOF_LINES.OPERATOR_ADMIN_SESSION.id,
      credentialFamily: "operator-session",
      routeFamilies: adminRoutes,
      authorizationExpectation: "authorized-positive",
      wrongRejectionProofClass: PROOF_CLASSES.PRODUCT_API_CAPABILITY.id,
    },
    {
      proofLine: PROOF_LINES.TASK_PRODUCER_API_KEY.id,
      credentialFamily: "task-api-key",
      routeFamilies: taskRoutes,
      authorizationExpectation: "authorized-positive",
      wrongRejectionProofClass: PROOF_CLASSES.PRODUCT_API_CAPABILITY.id,
    },
    {
      proofLine: PROOF_LINES.WORKER_API_KEY.id,
      credentialFamily: "worker-api-key",
      routeFamilies: workerRoutes,
      authorizationExpectation: "authorized-positive",
      wrongRejectionProofClass: PROOF_CLASSES.PRODUCT_API_CAPABILITY.id,
    },
  ];
}

function authorizedPositiveChecksFrom(data) {
  return [
    authorizedPositiveCheck(
      "operator.login",
      PROOF_LINES.OPERATOR_ADMIN_SESSION.id,
      "operator-session",
      ["/api/v1/auth"],
      data.adminAuthLoginLog,
      data.status,
    ),
    authorizedPositiveCheck(
      "operator.envInit",
      PROOF_LINES.OPERATOR_ADMIN_SESSION.id,
      "operator-session",
      ["/api/v1/control-plane", "/api/v1/api-keys"],
      data.adminEnvLog,
      data.status,
    ),
    authorizedPositiveCheck(
      "operator.taskApprove",
      PROOF_LINES.OPERATOR_ADMIN_SESSION.id,
      "operator-session",
      ["/api/v1/tasks/{taskId}/commands"],
      data.adminTaskCommandLog,
      data.status,
    ),
    authorizedPositiveCheck(
      "taskProducer.createAndAppendItems",
      PROOF_LINES.TASK_PRODUCER_API_KEY.id,
      "task-api-key",
      ["/api/v1/tasks"],
      data.taskLog,
      data.status,
    ),
    authorizedPositiveCheck(
      "taskProducer.readResult",
      PROOF_LINES.TASK_PRODUCER_API_KEY.id,
      "task-api-key",
      ["/api/v1/tasks"],
      data.taskVerifyLog,
      data.status,
    ),
    authorizedPositiveCheck(
      "worker.registerAndPoll",
      PROOF_LINES.WORKER_API_KEY.id,
      "worker-api-key",
      ["/worker-api/v1"],
      data.workerLog,
      data.status,
    ),
    authorizedPositiveCheck(
      "worker.submitResult",
      PROOF_LINES.WORKER_API_KEY.id,
      "worker-api-key",
      ["/worker-api/v1"],
      data.workerLog,
      data.status,
    ),
  ];
}

function defaultStartupAuthorizedPositiveChecksFrom(data) {
  return [
    authorizedPositiveCheck(
      "server.health",
      PROOF_LINES.OPERATOR_ADMIN_SESSION.id,
      "operator-session",
      ["/actuator/health"],
      data.firstHealth ?? null,
      data.status,
    ),
    authorizedPositiveCheck(
      "operator.login",
      PROOF_LINES.OPERATOR_ADMIN_SESSION.id,
      "operator-session",
      ["/api/v1/auth"],
      data.firstOperatorLogin ?? null,
      data.status,
    ),
    authorizedPositiveCheck(
      "operator.loginAfterRestart",
      PROOF_LINES.OPERATOR_ADMIN_SESSION.id,
      "operator-session",
      ["/api/v1/auth"],
      data.secondOperatorLogin ?? null,
      data.status,
    ),
  ];
}

function authorizedPositiveCheck(operation, proofLine, credentialFamily, routeFamilies, sourceLog, status) {
  return {
    operation,
    proofLine,
    credentialFamily,
    routeFamilies,
    authorizationExpectation: "authorized-positive",
    wrongRejectionProofClass: PROOF_CLASSES.PRODUCT_API_CAPABILITY.id,
    status: status === "passed" ? "passed" : "not-confirmed",
    claimScope: "valid credential/session must not be wrongly rejected",
    sourceLog: sourceLog ?? null,
  };
}

function collectDefaultStartupSummaries() {
  const bases = scopedDirs(args.serverDefaultStartupDirs, [
    path.join(REPO_ROOT, "xa-mass-testing", "target", "server-default-startup"),
  ]);
  return bases.flatMap(collectSummaryJson).map((entry) => {
    const data = entry.data;
    return {
      type: "server-default-startup",
      status: data.status ?? "unknown",
      proofClass: PROOF_CLASSES.PRODUCT_API_CAPABILITY.id,
      proofLines: [PROOF_LINES.OPERATOR_ADMIN_SESSION.id],
      proofQuestion: proofQuestionFor(PROOF_CLASSES.PRODUCT_API_CAPABILITY.id),
      evidenceShape: "packaged-process-startup-restart-smoke",
      gateType: "pr-gate",
      credentialRouteFamilies: [{
        proofLine: PROOF_LINES.OPERATOR_ADMIN_SESSION.id,
        credentialFamily: "operator-session",
        routeFamilies: ["/actuator/health", "/api/v1/auth"],
        authorizationExpectation: "authorized-positive",
        wrongRejectionProofClass: PROOF_CLASSES.PRODUCT_API_CAPABILITY.id,
      }],
      authorizedPositiveChecks: defaultStartupAuthorizedPositiveChecksFrom(data),
      claimScope: "default-startup-restart-smoke",
      defaultProfile: data.defaultProfile ?? null,
      defaultProfileLogObserved: valueOrNull(data.defaultProfileLogObserved),
      workDir: data.workDir ?? null,
      sqlitePath: data.sqlitePath ?? null,
      restartCount: data.restartCount ?? null,
      firstHealth: data.firstHealth ?? null,
      secondHealth: data.secondHealth ?? null,
      firstOperatorLogin: data.firstOperatorLogin ?? null,
      secondOperatorLogin: data.secondOperatorLogin ?? null,
      sameSqliteRestart: valueOrNull(data.sameSqliteRestart),
      redisNamespaceMode: data.redisNamespaceMode ?? null,
      logFailureScan: data.logFailureScan ?? null,
      knownNonProofBoundaries: [
        "Default startup/restart proof only; not task/worker scheduling proof.",
        "Not a full API route-permission matrix.",
      ],
      artifactPath: relative(entry.file),
    };
  });
}

function collectReportJson(reportType, dirs) {
  return scopedDirs(dirs, []).flatMap((dir) => {
    if (!fs.existsSync(dir)) {
      return [];
    }
    return fs.readdirSync(dir)
    .filter((name) => name.endsWith(".json"))
    .sort()
      .map((name) => path.join(dir, name));
  })
    .map((file) => ({ file, data: readJson(file) }))
    .filter((entry) => entry.data && typeof entry.data === "object")
    .map((entry) => {
      const data = entry.data;
      const scenarioId = scenarioIdFrom(data);
      const traceAnalyzerIds = traceAnalyzerIdsFrom(data);
      const releaseEvidence = releaseEvidenceFrom(releaseEvidenceByScenario.get(scenarioId), data);
      return {
        type: `${reportType}-report`,
        status: reportStatus(reportType, data, releaseEvidence),
        proofClass: reportProofClass(reportType),
        proofLines: reportProofLines(reportType, scenarioId),
        proofQuestion: proofQuestionFor(reportProofClass(reportType)),
        evidenceShape: reportEvidenceShape(reportType),
        gateType: reportGateType(reportType),
        credentialRouteFamilies: [],
        claimScope: reportClaimScope(reportType, scenarioId),
        scenarioId,
        runtimeBackend: nestedGet(data, "runtimeBackend") ?? nestedGet(data, "config", "runtimeBackend") ?? nestedGet(data, "proof", "matrixProfile", "runtimeBackend") ?? null,
        transport: nestedGet(data, "transport") ?? nestedGet(data, "runtime", "transport") ?? nestedGet(data, "proof", "matrixProfile", "transport") ?? null,
        workerProfile: nestedGet(data, "workerProfile") ?? nestedGet(data, "proof", "matrixProfile", "workerProfile") ?? null,
        faultShape: nestedGet(data, "faultShape") ?? nestedGet(data, "proof", "matrixProfile", "faultShape") ?? null,
        traceAnalyzerIds,
        criticalInvariantIds: invariantIds(scenarioId, traceAnalyzerIds),
        releaseEvidence,
        scheduledManualEvidence: reportType === "perf" || reportType === "soak",
        knownNonProofBoundaries: reportBoundaries(reportType),
        artifactPath: relative(entry.file),
      };
    });
}

function collectSummaryJson(base) {
  if (!fs.existsSync(base)) {
    return [];
  }
  return fs.readdirSync(base, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => path.join(base, entry.name, "summary.json"))
    .filter((file) => fs.existsSync(file))
    .map((file) => ({ file, data: readJson(file) }))
    .filter((entry) => entry.data && typeof entry.data === "object");
}

function classifySuite(suiteName, file) {
  const haystack = `${suiteName} ${slash(file)}`;
  for (const [marker, classification] of Object.entries(SUITE_CLASSIFICATION)) {
    if (haystack.includes(marker)) {
      return classification;
    }
  }
  return {};
}

function scenarioIdFrom(data) {
  return nestedGet(data, "scenarioId")
    ?? nestedGet(data, "config", "scenarioId")
    ?? nestedGet(data, "proof", "matrixProfile", "scenarioId")
    ?? null;
}

function traceAnalyzerIdsFrom(data) {
  const analyses = nestedGet(data, "trace", "analyses") ?? nestedGet(data, "proof", "trace", "analyses");
  if (!Array.isArray(analyses)) {
    return [];
  }
  return [...new Set(analyses.map((item) => item?.scenarioId).filter(Boolean).map(String))].sort();
}

function invariantIds(scenarioId, traceAnalyzerIds) {
  const ids = [];
  for (const key of [scenarioId, ...traceAnalyzerIds]) {
    if (key && CHAOS_SCENARIO_INVARIANTS[key]) {
      ids.push(...CHAOS_SCENARIO_INVARIANTS[key]);
    }
  }
  return [...new Set(ids)].sort();
}

function reportStatus(reportType, data, releaseEvidence = null) {
  if (releaseEvidence && releaseEvidence.thresholdSignals.length > 0) {
    return releaseEvidence.thresholdSignals.every((signal) => signal.passed === true) ? "passed" : "failed";
  }
  if (reportType === "soak") {
    return nestedGet(data, "proof", "runtimeInvariants", "ok") === true ? "passed" : "unknown";
  }
  if (reportType === "chaos") {
    if (nestedGet(data, "task", "status") === "TERMINAL") {
      return "passed";
    }
    return traceAnalysesPassed(data) ? "passed" : "unknown";
  }
  return "reported";
}

function reportBoundaries(reportType) {
  if (reportType === "chaos") {
    return [
      "Current PR chaos smokes prove selected distributed-edge recovery paths only.",
      "Redis process kill, partition/failover, lease-clock skew, and multi-node presence flap remain not-current-proof unless a report explicitly names them.",
    ];
  }
  if (reportType === "perf") {
    return [
      "Perf reports are scheduled/manual trend evidence unless promoted to a PR gate.",
      "Perf reports do not replace correctness proof in engine/server suites.",
    ];
  }
  if (reportType === "soak") {
    return [
      "Soak reports are scheduled/manual confidence evidence unless promoted to a PR gate.",
      "Soak reports do not replace deterministic scheduling proof.",
    ];
  }
  return [];
}

function reportProofClass(reportType) {
  if (reportType === "chaos" || reportType === "perf" || reportType === "soak") {
    return PROOF_CLASSES.SCOPED_OPERATIONAL_RESILIENCE.id;
  }
  return null;
}

function reportProofLines(reportType, scenarioId) {
  if (reportType === "chaos") {
    return [PROOF_LINES.FAULT_RECOVERY_EVIDENCE.id];
  }
  if (reportType === "perf") {
    return [PROOF_LINES.SCALE_CONTENTION_EVIDENCE.id];
  }
  if (reportType === "soak") {
    const id = scenarioId ?? "";
    if (id.includes("lease") || id.includes("retry") || id.includes("restart") || id.includes("stale")) {
      return [PROOF_LINES.FAULT_RECOVERY_EVIDENCE.id];
    }
    return [PROOF_LINES.SCALE_CONTENTION_EVIDENCE.id];
  }
  return [];
}

function reportEvidenceShape(reportType) {
  if (reportType === "chaos") {
    return "chaos-runtime-report";
  }
  if (reportType === "perf") {
    return "perf-runtime-report";
  }
  if (reportType === "soak") {
    return "soak-runtime-report";
  }
  return "json-report";
}

function reportGateType(reportType) {
  if (reportType === "chaos") {
    return "pr-gate-selected-scenarios";
  }
  if (reportType === "perf" || reportType === "soak") {
    return "scheduled-manual";
  }
  return "job-local-report";
}

function reportClaimScope(reportType, scenarioId) {
  const scenario = scenarioId ? ` scenario ${scenarioId}` : "";
  if (reportType === "chaos") {
    return `selected distributed-edge recovery${scenario}`;
  }
  if (reportType === "perf") {
    return `selected runtime performance and counter-invariant${scenario}`;
  }
  if (reportType === "soak") {
    return `scheduled/manual soak${scenario}`;
  }
  return "report-local-evidence";
}

function proofQuestionFor(proofClassId) {
  const definition = Object.values(PROOF_CLASSES).find((item) => item.id === proofClassId);
  return definition?.question ?? null;
}

function scheduledManualEvidence(evidence) {
  const types = new Set(evidence.map((item) => item.type));
  return {
    perf: types.has("perf-report") ? "present" : "not-run-in-this-job",
    soak: types.has("soak-report") ? "present" : "not-run-in-this-job",
  };
}

function totals(evidence) {
  const surefire = evidence.filter((item) => item.type === "surefire");
  return {
    evidenceCount: evidence.length,
    surefireSuiteCount: surefire.length,
    surefireTestcaseCount: surefire.reduce((sum, item) => sum + Number(item.testcaseCount ?? 0), 0),
    platformConfidenceRuns: evidence.filter((item) => item.type === "platform-confidence").length,
    serverDefaultStartupRuns: evidence.filter((item) => item.type === "server-default-startup").length,
    chaosReports: evidence.filter((item) => item.type === "chaos-report").length,
    perfReports: evidence.filter((item) => item.type === "perf-report").length,
    soakReports: evidence.filter((item) => item.type === "soak-report").length,
    releaseEvidenceReports: evidence.filter((item) => item.releaseEvidence).length,
    proofClassCounts: proofClassCounts(evidence),
    proofLineCounts: proofLineCounts(evidence),
    credentialCheckCount: credentialCheckCount(evidence),
    credentialCheckProofLineCounts: credentialCheckProofLineCounts(evidence),
    authorizedPositiveCheckCount: authorizedPositiveCheckCount(evidence),
    authorizedPositiveProofLineCounts: authorizedPositiveProofLineCounts(evidence),
  };
}

function proofClassCounts(evidence) {
  const counts = {};
  for (const item of evidence) {
    if (!item.proofClass) {
      continue;
    }
    counts[item.proofClass] = (counts[item.proofClass] ?? 0) + 1;
  }
  return counts;
}

function proofLineCounts(evidence) {
  const counts = {};
  for (const item of evidence) {
    for (const line of item.proofLines ?? []) {
      counts[line] = (counts[line] ?? 0) + 1;
    }
  }
  return counts;
}

function credentialCheckCount(evidence) {
  return evidence.reduce((sum, item) => sum + Object.keys(item.credentialChecks ?? {}).length, 0);
}

function credentialCheckProofLineCounts(evidence) {
  const counts = {};
  for (const item of evidence) {
    for (const check of Object.values(item.credentialChecks ?? {})) {
      if (!check?.proofLine) {
        continue;
      }
      counts[check.proofLine] = (counts[check.proofLine] ?? 0) + 1;
    }
  }
  return counts;
}

function authorizedPositiveCheckCount(evidence) {
  return evidence.reduce((sum, item) => sum + (item.authorizedPositiveChecks ?? []).length, 0);
}

function authorizedPositiveProofLineCounts(evidence) {
  const counts = {};
  for (const item of evidence) {
    for (const check of item.authorizedPositiveChecks ?? []) {
      if (!check?.proofLine) {
        continue;
      }
      counts[check.proofLine] = (counts[check.proofLine] ?? 0) + 1;
    }
  }
  return counts;
}

function releaseEvidenceMap(config) {
  const scenarios = Array.isArray(config.stableScenarios) ? config.stableScenarios : [];
  return new Map(scenarios
    .filter((scenario) => scenario && typeof scenario.scenarioId === "string")
    .map((scenario) => [scenario.scenarioId, scenario]));
}

function releaseEvidenceFrom(definition, data) {
  if (!definition) {
    return null;
  }
  return {
    lane: definition.lane ?? null,
    workflow: definition.workflow ?? null,
    runner: definition.runner ?? null,
    reportGlob: definition.reportGlob ?? null,
    prGate: releaseEvidenceConfig.workflowPolicy?.prGate ?? false,
    comparisonTarget: releaseEvidenceConfig.workflowPolicy?.comparisonTarget ?? null,
    thresholdPolicy: releaseEvidenceConfig.workflowPolicy?.thresholdPolicy ?? null,
    thresholdSignals: Array.isArray(definition.thresholdSignals)
      ? definition.thresholdSignals.map((signal) => evaluateThresholdSignal(signal, data))
      : [],
    trendSignals: Array.isArray(definition.trendSignals)
      ? definition.trendSignals.map((signal) => ({
          name: signal.name ?? signal.path ?? "trend",
          path: signal.path ?? null,
          value: signal.path ? pathGet(data, signal.path) ?? null : null,
          unit: signal.unit ?? null,
        }))
      : [],
  };
}

function evaluateThresholdSignal(signal, data) {
  const value = signal.path ? pathGet(data, signal.path) : undefined;
  if (Object.hasOwn(signal, "expect")) {
    return {
      name: signal.name ?? signal.path ?? "threshold",
      path: signal.path ?? null,
      value: value ?? null,
      expect: signal.expect,
      passed: sameValue(value, signal.expect),
    };
  }
  if (signal.gteConfigPath) {
    const configValue = pathGet(data, signal.gteConfigPath);
    return {
      name: signal.name ?? signal.path ?? "threshold",
      path: signal.path ?? null,
      value: value ?? null,
      gteConfigPath: signal.gteConfigPath,
      configValue: configValue ?? null,
      passed: typeof value === "number" && typeof configValue === "number" && value >= configValue,
    };
  }
  return {
    name: signal.name ?? signal.path ?? "threshold",
    path: signal.path ?? null,
    value: value ?? null,
    passed: value !== undefined,
  };
}

function pathGet(data, pathExpression) {
  if (!pathExpression || typeof pathExpression !== "string") {
    return undefined;
  }
  return pathExpression.split(".").reduce((current, key) => {
    if (!current || typeof current !== "object" || !(key in current)) {
      return undefined;
    }
    return current[key];
  }, data);
}

function sameValue(actual, expected) {
  return JSON.stringify(actual) === JSON.stringify(expected);
}

function traceAnalysesPassed(data) {
  const analyses = nestedGet(data, "trace", "analyses") ?? nestedGet(data, "proof", "trace", "analyses");
  return Array.isArray(analyses)
    && analyses.length > 0
    && analyses.every((analysis) => analysis?.ok === true && Array.isArray(analysis?.issues) && analysis.issues.length === 0);
}

function nestedGet(data, ...keys) {
  let current = data;
  for (const key of keys) {
    if (!current || typeof current !== "object" || !(key in current)) {
      return undefined;
    }
    current = current[key];
  }
  return current;
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    return null;
  }
}

function safeRead(file) {
  try {
    return fs.readFileSync(file, "utf8");
  } catch {
    return null;
  }
}

function xmlAttr(source, attrName) {
  const escaped = attrName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = source.match(new RegExp(`\\b${escaped}="([^"]*)"`));
  return match ? match[1] : null;
}

function numberOrDefault(value, fallback) {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function countMatches(source, regex) {
  return [...source.matchAll(regex)].length;
}

function scopedDirs(values, defaults) {
  const selected = values && values.length > 0 ? values : defaults;
  return selected.map((value) => path.resolve(REPO_ROOT, value));
}

function unique(values) {
  return [...new Set(values)];
}

function walk(root) {
  if (!fs.existsSync(root)) {
    return [];
  }
  const result = [];
  const stack = [root];
  while (stack.length > 0) {
    const current = stack.pop();
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const next = path.join(current, entry.name);
      if (entry.isDirectory()) {
        if (entry.name === ".git" || entry.name === "node_modules") {
          continue;
        }
        stack.push(next);
      } else if (entry.isFile()) {
        result.push(next);
      }
    }
  }
  return result;
}

function valueOrNull(value) {
  return value === undefined ? null : value;
}

function relative(file) {
  return slash(path.relative(REPO_ROOT, path.resolve(file)));
}

function slash(value) {
  return value.replaceAll(path.sep, "/");
}
