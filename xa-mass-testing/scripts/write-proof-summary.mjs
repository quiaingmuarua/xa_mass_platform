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

const SUITE_CLASSIFICATION = {
  EngineSchedulingCoreSuite: {
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
      "Not a full route-permission matrix.",
    ],
  },
  ServerLifecycleResultConvergenceSuite: {
    gate: "server lifecycle/result representative proof",
    criticalInvariantIds: [
      "kernel.duplicate-callback-idempotence",
      "kernel.result-terminal-convergence",
      "kernel.resource-release-reuse",
    ],
    knownNonProofBoundaries: [
      "Representative host proof only; engine suite remains primary kernel proof.",
      "Not active-profile packaged-process confidence.",
    ],
  },
  ExternalWorkerParitySuite: {
    gate: "external worker parity proof",
    criticalInvariantIds: ["ext.worker-parity-public-contract"],
    knownNonProofBoundaries: ["Not a full SDK transport fault matrix."],
  },
  ProofRegistryClosureGuardTest: {
    gate: "proof registry closure guard",
    criticalInvariantIds: [],
    knownNonProofBoundaries: [
      "Proof-map guard only; it does not execute the named proof scenarios.",
    ],
  },
  WorkerFaultScenarioIndexTest: {
    gate: "worker fault scenario index guard",
    criticalInvariantIds: [],
    knownNonProofBoundaries: [
      "Scenario metadata guard only; it does not execute chaos scenarios.",
    ],
  },
  WorkerFaultReportMetadataTest: {
    gate: "worker fault report metadata guard",
    criticalInvariantIds: [],
    knownNonProofBoundaries: [
      "Report-shape guard only; it does not execute chaos scenarios.",
    ],
  },
  PlatformConfidenceProfileMatrixGuardTest: {
    gate: "platform confidence profile guard",
    criticalInvariantIds: [],
    knownNonProofBoundaries: [
      "Profile/matrix/schema guard only; packaged process smokes carry the runtime proof.",
    ],
  },
  ServerStartupProfileSuite: {
    gate: "server startup profile context support",
    criticalInvariantIds: [],
    knownNonProofBoundaries: [
      "Spring context support proof only; packaged no-arg startup smoke carries process proof.",
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
      }];
    }
    return [name, {
      status: value ?? "unknown",
      httpStatus: null,
      code: null,
      failureReason: null,
    }];
  }));
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
  };
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
