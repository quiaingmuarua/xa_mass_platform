import type { MockCreditSource } from "@/credits/mock-source";
import {
  CheckStartUnconfirmed,
  type CheckCreditConfirmation,
  type CheckCredits
} from "./credits";
import { MockTaskRetention } from "@/task-management/retention";
import { decodeUtf8 } from "@/files/text";
import type { MockUserDirectorySource } from "@/user-directory-source";
import {
  TaskApiError,
  requireCallerPermission,
  selectTaskUser,
  requireTaskCaller,
  type TaskCaller
} from "@/task-caller";
import type { AppCheckTaskSource } from "./task-source";
import { developmentUsers } from "@/development-users";
import { taskUserScore, compareTaskIds } from "@/task-user-id";
import type { TaskPolicySnapshot } from "./configuration";

import {
  validateMockSettings,
  mockConfigurationFingerprint,
  SIMULATED_CONTRACT,
  REGISTRATION_CONTRACT,
  CARRIER_CONTRACT,
  CARRIER_BATCH_CONTRACT,
  MD5_BATCH_CONTRACT,
  isCarrierContract,
  isMd5Contract,
  MD5_CONTRACT,
  PRIME_CONTRACT,
  settingsIdentity,
  requireSettingsUpdate,
  type AppCheckConfigurationView,
  type AppCheckSettings
} from "./settings";
import { csvCell, inspectImport, type ImportSnapshot } from "./import-model";
import { type PreviewState, type ExportFilter, type TaskActivity } from "./workbench";
import {
  checkTypeLabel,
  type Catalog,
  type CheckDetail,
  type CheckTask,
  type CheckResult,
  type CreateCheckTask,
  rangeExamples,
  simulationSchema
} from "./model";

export const mockSimulation = { ranges: rangeExamples.混合, delayMs: [2000, 5000] };
const mockCheckTypes: Catalog["checkTypes"] = [
  {
    id: SIMULATED_CONTRACT,
    label: "模拟注册",
    seedType: "phonenumber",
    maxSeedsPerItem: 1,
    description: "确定性模拟协议；按实际 Worker、salt、区间和延迟复算",
    parameterExample: mockSimulation,
    inputExample: { number: "+8613800000001", salt: "fixed-salt", ...mockSimulation },
    resultExample: {
      number: "+8613800000001",
      registered: true,
      workerId: "worker-a",
      workerGroupId: "app-a-sim",
      simulatedDelayMillis: 4067
    }
  },
  {
    id: REGISTRATION_CONTRACT,
    label: "注册检查",
    seedType: "phonenumber",
    maxSeedsPerItem: 1,
    description: "号码查询协议；不要求模拟字段",
    parameterExample: {},
    inputExample: { number: "+8613800000001" },
    resultExample: {
      number: "+8613800000001",
      registered: true,
      workerId: "worker-c",
      workerGroupId: "app-c"
    }
  },
  {
    id: CARRIER_CONTRACT,
    label: "原始运营商识别",
    seedType: "phonenumber",
    maxSeedsPerItem: 1,
    description: "判断是否识别到原始运营商；号码库元数据不表示当前运营商或号码在用状态",
    parameterExample: {},
    inputExample: { rawNumber: "+8613800000000" },
    resultExample: {
      input: "+8613800000000",
      possible: true,
      valid: true,
      originalCarrier: "China Mobile"
    }
  },
  {
    id: MD5_CONTRACT,
    label: "MD5 前缀命中（演示）",
    seedType: "text",
    maxSeedsPerItem: 1,
    description: "固定演示条件：文本 UTF-8 MD5 摘要以 0 开头。",
    parameterExample: {},
    inputExample: { value: "a" },
    resultExample: { input: "a", valid: true, md5: "0cc175b9c0f1b6a831c399e269772661" }
  },
  {
    id: PRIME_CONTRACT,
    label: "32 位整数素数判断（演示）",
    seedType: "text",
    maxSeedsPerItem: 1,
    description: "0～4,294,967,295 的无符号整数；是素数时满足。",
    parameterExample: {},
    inputExample: { value: "17" },
    resultExample: { input: "17", valid: true, isPrime: true }
  }
];
for (const [id, original, inputField] of [
  [CARRIER_BATCH_CONTRACT, CARRIER_CONTRACT, "rawNumbers"],
  [MD5_BATCH_CONTRACT, MD5_CONTRACT, "values"]
]) {
  const single = mockCheckTypes.find((type) => type.id === original)!;
  mockCheckTypes.push({
    ...structuredClone(single),
    id,
    maxSeedsPerItem: 100,
    inputExample: { [inputField]: [single.resultExample.input] },
    resultExample: { results: [single.resultExample] }
  });
}
export const mockCatalog: Catalog = {
  projectId: "app-checks",
  name: "批量检查",
  version: "0.3.0-preview",
  checkTypes: mockCheckTypes,
  checks: [
    ...["app-a", "app-b"].map((appId) => ({
      checkId: appId + "-registration",
      unitPrice: 1,
      label: (appId === "app-a" ? "App A" : "App B") + " 模拟注册",
      workerGroupId: appId + "-sim",
      checkType: SIMULATED_CONTRACT,
      execution: {
        eventCode: "extension.worker.app.registration.check",
        seedsPerItem: 1,
        refill: [{ poolName: "assignment-window", target: {}, count: 100 }],
        workerSelector: { executorName: "worker.assignment.available", input: {} }
      }
    })),
    ...[
      [
        "original-carrier",
        "原始运营商识别",
        CARRIER_BATCH_CONTRACT,
        "phonenumber.original-carrier.batch"
      ],
      [
        "uint32-prime",
        "32 位整数素数判断（演示）",
        PRIME_CONTRACT,
        "integer.uint32-prime"
      ],
      ["md5-prefix", "MD5 前缀命中（演示）", MD5_BATCH_CONTRACT, "string.md5.batch"]
    ].map(([checkId, label, checkType, event]) => ({
      checkId,
      unitPrice: 1,
      label,
      checkType,
      workerGroupId: "demo-sim",
      execution: {
        eventCode: "extension.worker." + event,
        seedsPerItem: checkType === PRIME_CONTRACT ? 1 : 10,
        refill: [{ poolName: "any", target: {}, count: 100 }],
        workerSelector: { executorName: "worker.any", input: {} }
      }
    }))
  ],
  countries: ["CN", "US", "GB"],
  limits: { seedsPerImport: 100000, importFileBytes: 10 * 1024 * 1024 }
};
export const mockSettings: AppCheckSettings = {
  common: {
    typeDefaults: {
      [SIMULATED_CONTRACT]: structuredClone(mockSimulation),
      [REGISTRATION_CONTRACT]: {},
      [CARRIER_CONTRACT]: {},
      [CARRIER_BATCH_CONTRACT]: {},
      [MD5_BATCH_CONTRACT]: {},
      [MD5_CONTRACT]: {},
      [PRIME_CONTRACT]: {}
    }
  },
  checks: Object.fromEntries(
    mockCatalog.checks.map(({ checkId, ...app }) => [checkId, app])
  )
};
const mockTaskPolicy: TaskPolicySnapshot = {
  priority: 50,
  maxRetries: 3,
  validityMillis: 365 * 86400000
};
const epoch = Date.UTC(2026, 9, 8, 2);
function task(taskId: string, changes: Partial<CheckTask> = {}): CheckTask {
  return {
    taskId,
    userId: developmentUsers[0]!.userId,
    name: taskId,
    createdAtMillis: epoch,
    workerGroupId: "app-a-sim",
    managed: false,
    inputVersion: "5",
    unitPrice: 1,
    seedsPerItem: 1,
    state: "terminal",
    checkId: "app-a-registration",
    country: "CN",
    checkType: SIMULATED_CONTRACT,
    checkLabel: "App A",
    checkTypeLabel: "模拟注册",
    seedType: "phonenumber",
    contractParameters: structuredClone(mockSimulation),
    eventCode: mockSettings.checks["app-a-registration"].execution.eventCode,
    workerSelector: structuredClone(
      mockSettings.checks["app-a-registration"].execution.workerSelector
    ),
    refill: structuredClone(mockSettings.checks["app-a-registration"].execution.refill),
    salt: "fixed-salt",
    saltDate: "2026-09-18",
    totalCount: 1,
    activeCount: 0,
    succeededCount: 1,
    failedCount: 0,
    mockPolicy: structuredClone(mockTaskPolicy),
    ...changes
  };
}
function records(): CheckDetail[] {
  // Fixed, independently checked vector shared by the Java protocol tests.
  const registered = {
    messageId: "r-1",
    resultStatus: "succeeded" as const,
    seed: "+8613800000001",
    matched: true,
    details: {
      workerId: "worker-a",
      workerGroupId: "app-a-sim",
      simulatedDelayMillis: 4067
    }
  };
  return [
    {
      task: task("check-review", {
        name: "十月 · 美国号码首批筛查",
        country: "US",
        createdAtMillis: epoch + 2_000,
        state: "pre_review",
        totalCount: 24000,
        activeCount: 24000,
        succeededCount: 0,
        sourceFile: "us_october_batch_01.txt",
        salt: undefined
      }),
      results: [],
      resultsTruncated: false
    },
    {
      task: task("check-live", {
        name: "App B · 英国号码增量查询",
        checkId: "app-b-registration",
        workerGroupId: "app-b-sim",
        country: "GB",
        createdAtMillis: epoch + 1_000,
        state: "running_visible",
        totalCount: 48000,
        activeCount: 16000,
        succeededCount: 31872,
        failedCount: 128,
        sourceFile: "uk_new_seeds.txt",
        salt: undefined
      }),
      results: sampleRows(32000, "app-b-sim", "GB"),
      resultsTruncated: true
    },
    {
      task: task("check-delivered", {
        name: "存量号码 · 十月注册状态查询",
        totalCount: 12800,
        succeededCount: 12749,
        failedCount: 51,
        sourceFile: "october_existing_seeds.txt",
        salt: undefined
      }),
      results: sampleRows(12800, "app-a-sim", "CN"),
      resultsTruncated: true
    },
    {
      task: task("check-mixed", {
        name: "华东存量号码复查",
        totalCount: 4,
        succeededCount: 3,
        failedCount: 1
      }),
      results: [
        registered,
        { ...registered, messageId: "tampered", matched: false },
        {
          messageId: "invalid",
          resultStatus: "succeeded",
          seed: registered.seed,
          contentError: "Mock 业务内容无法解析"
        },
        { messageId: "failed", resultStatus: "failed", seed: "+8613800000002" }
      ],
      resultsTruncated: false
    },
    {
      task: task("check-unregistered", {
        name: "补充号码查询",
        contractParameters: { ranges: rangeExamples.全未注册, delayMs: [0, 0] }
      }),
      results: [
        {
          ...registered,
          matched: false,
          details: { ...registered.details, simulatedDelayMillis: 0 }
        }
      ],
      resultsTruncated: false
    },
    {
      task: task("check-running", {
        name: "App B · 增量号码查询",
        checkId: "app-b-registration",
        workerGroupId: "app-b-sim",
        state: "running_visible",
        totalCount: 120,
        activeCount: 120,
        succeededCount: 0
      }),
      results: [],
      resultsTruncated: false
    },
    {
      task: task("check-preview", {
        name: "北区号码清单查询",
        totalCount: 120,
        succeededCount: 120
      }),
      results: Array.from({ length: 120 }, (_, i) => ({
        ...registered,
        messageId: `preview-${i}`
      })),
      resultsTruncated: true
    },
    {
      task: task("check-empty", {
        name: "新导入种子查询",
        succeededCount: 0,
        activeCount: 1,
        state: "running-initial"
      }),
      results: [],
      resultsTruncated: false
    },
    {
      task: task("check-read-error", { name: "渠道号码抽样查询" }),
      results: [registered],
      resultsTruncated: false
    },
    {
      task: {
        taskId: "check-missing",
        createdAtMillis: epoch - 1000,
        state: null,
        workerGroupId: null,
        managed: false
      },
      results: [],
      resultsTruncated: false
    },
    {
      task: task("check-carrier", {
        name: "原始运营商识别 · 演示样例",
        checkId: "original-carrier",
        workerGroupId: "demo-sim",
        checkLabel: "原始运营商识别",
        checkType: CARRIER_CONTRACT,
        contractParameters: {},
        salt: undefined,
        eventCode: mockSettings.checks["original-carrier"].execution.eventCode,
        workerSelector:
          mockSettings.checks["original-carrier"].execution.workerSelector,
        refill: mockSettings.checks["original-carrier"].execution.refill,
        totalCount: 5,
        succeededCount: 4,
        failedCount: 1
      }),
      results: [
        {
          messageId: "carrier-yes",
          seed: "+8613800000000",
          resultStatus: "succeeded",
          matched: true,
          details: { originalCarrier: "China Mobile", reason: "IDENTIFIED" }
        },
        {
          messageId: "carrier-no",
          seed: "+8610800000000",
          resultStatus: "succeeded",
          matched: false,
          details: { originalCarrier: "", reason: "NO_CARRIER_MAPPING" }
        },
        {
          messageId: "carrier-invalid-number",
          seed: "+86123",
          resultStatus: "succeeded",
          matched: false,
          details: { originalCarrier: "", reason: "INVALID_PHONE_NUMBER" }
        },
        {
          messageId: "carrier-failed",
          seed: "+8613800000001",
          resultStatus: "failed"
        },
        {
          messageId: "carrier-broken",
          seed: "+8613800000002",
          resultStatus: "succeeded",
          contentError: "Mock 运营商响应缺少必要字段"
        }
      ],
      resultsTruncated: false
    },
    ...(["uint32-prime", "md5-prefix"] as const).map((checkId) => {
      const configured = mockSettings.checks[checkId];
      const prime = checkId === "uint32-prime";
      return {
        task: task("check-" + checkId, {
          name: configured.label + " · Mock 样例",
          checkId,
          checkLabel: configured.label,
          checkType: configured.checkType,
          workerGroupId: configured.workerGroupId,
          seedType: "text",
          country: undefined,
          eventCode: configured.execution.eventCode,
          workerSelector: configured.execution.workerSelector,
          refill: configured.execution.refill,
          contractParameters: {},
          salt: undefined,
          totalCount: 2,
          succeededCount: 2,
          failedCount: 0
        }),
        results: prime
          ? [
              {
                messageId: "prime-yes",
                seed: "17",
                resultStatus: "succeeded" as const,
                matched: true,
                details: { isPrime: true }
              },
              {
                messageId: "prime-no",
                seed: "49",
                resultStatus: "succeeded" as const,
                matched: false,
                details: { isPrime: false }
              }
            ]
          : [
              {
                messageId: "md5-yes",
                seed: "a",
                resultStatus: "succeeded" as const,
                matched: true,
                details: { md5: "0cc175b9c0f1b6a831c399e269772661" }
              },
              {
                messageId: "md5-no",
                seed: "abc",
                resultStatus: "succeeded" as const,
                matched: false,
                details: { md5: "900150983cd24fb0d6963f7d28e17f72" }
              }
            ],
        resultsTruncated: false
      };
    }),
    {
      task: {
        taskId: "managed-app-a",
        createdAtMillis: epoch - 2000,
        state: "running_visible",
        workerGroupId: "app-a-sim",
        managed: true
      },
      results: [],
      resultsTruncated: false
    }
  ];
}
function sampleRows(
  count: number,
  group: string,
  country: string,
  seeds?: string[]
): CheckResult[] {
  const prefix = country === "US" ? "+1202" : country === "GB" ? "+4477" : "+86138";
  return Array.from({ length: count }, (_, i) =>
    (i + 1) % 250 === 0
      ? {
          messageId: `sample-${i}`,
          seed: seeds?.[i] ?? `${prefix}${String(i + 10000000).padStart(8, "0")}`,
          resultStatus: "failed" as const
        }
      : {
          messageId: `sample-${i}`,
          seed: seeds?.[i] ?? `${prefix}${String(i + 10000000).padStart(8, "0")}`,
          resultStatus: "succeeded" as const,
          matched: i % 3 !== 0,
          details: {
            workerId: `demo-worker-${(i % 20) + 1}`,
            workerGroupId: group,
            simulatedDelayMillis: 2400
          }
        }
  );
}
interface MockCheckStart {
  confirmation: CheckCreditConfirmation;
  ledgerRequestId: string;
  actorUserId: string;
  quote: CheckCredits;
}
export class MockAppCheckTaskSource implements AppCheckTaskSource {
  readonly mode = "mock";
  private savedConfiguration = {
    configuration: structuredClone(mockSettings)
  };
  private effectiveConfiguration = structuredClone(this.savedConfiguration);
  private readonly records = new Map(records().map((r) => [r.task.taskId, r]));
  private readonly requests = new Map<string, { input: string; taskId: string }>();
  private readonly reads = new Map<string, number>();
  private readonly states = new Map<string, PreviewState>();
  private readonly activities = new Map<string, TaskActivity[]>();
  private readonly imports = new Map<string, ImportSnapshot>();
  private readonly imported = new Map<
    string,
    Map<string, { messageId: string; seeds: string[] }>
  >();
  private sequence = 0;
  private readonly creditStarts = new Map<string, Map<string, MockCheckStart>>();
  private readonly activeStarts = new Map<string, string>();
  private readonly mutations = new Set<string>();
  private async mutate<T>(id: string, operation: () => Promise<T>): Promise<T> {
    if (this.mutations.has(id))
      throw new TaskApiError(409, "任务操作正在进行，请稍后核对");
    this.mutations.add(id);
    try {
      return await operation();
    } finally {
      this.mutations.delete(id);
    }
  }
  simulateStartResponseLoss(caller: TaskCaller) {
    this.credits.dropNextResponse(caller);
  }
  private async startObservation(caller: TaskCaller, id: string, requestId?: string) {
    const task = this.owned(caller, id).task;
    const start = this.creditStarts
      .get(id)
      ?.get(requestId ?? this.activeStarts.get(id) ?? "");
    return start
      ? {
          start,
          receipt: await this.credits.adjustment(
            caller,
            task.userId!,
            start.ledgerRequestId
          )
        }
      : undefined;
  }
  private async requireUncharged(caller: TaskCaller, id: string) {
    const observation = await this.startObservation(caller, id);
    if (observation && observation.receipt.status !== "rejected")
      throw new TaskApiError(400, "启动扣分已受理，不能继续导入；请核对原启动操作");
  }
  async loadCredits(
    caller: TaskCaller,
    id: string,
    requestId?: string
  ): Promise<CheckCredits> {
    const task = this.owned(caller, id).task;
    const observed = await this.startObservation(caller, id, requestId);
    const active = requestId ? await this.startObservation(caller, id) : observed;
    const saved = observed ?? active;
    const account = await this.credits.account(caller, task.userId!);
    const quantity =
      task.state === "pre_review"
        ? [...this.seedGroups(id).values()].reduce((n, g) => n + g.seeds.length, 0)
        : 0;
    const useSaved =
      !!saved &&
      !(
        requestId === undefined &&
        saved.receipt.status === "rejected" &&
        task.state === "pre_review"
      );
    const unitPrice = useSaved ? saved.start.quote.unitPrice : task.unitPrice!;
    const seedCount = useSaved ? saved.start.quote.seedCount : quantity;
    const amount = unitPrice * seedCount;
    if (
      !Number.isSafeInteger(unitPrice) ||
      unitPrice <= 0 ||
      !Number.isSafeInteger(amount)
    )
      throw new TaskApiError(400, "积分总额或固定单价无效");
    const receipt = observed?.receipt;
    return {
      taskId: id,
      userId: task.userId!,
      unitPrice,
      seedCount,
      amount,
      balance: account.balance,
      itemCount: useSaved ? saved.start.quote.itemCount : (task.totalCount ?? 0),
      requestId: observed?.start.confirmation.requestId ?? requestId ?? null,
      requestStatus: receipt?.status ?? "not_observed",
      rejectionCode: receipt?.status === "rejected" ? receipt.rejectionCode : null,
      transaction: receipt?.transaction ?? null,
      status: !receipt
        ? "ready"
        : receipt.status === "rejected"
          ? "rejected"
          : receipt.status === "not_observed"
            ? "debit_unconfirmed"
            : task.state === "pre_review"
              ? "charged"
              : task.state === "terminal"
                ? "closed"
                : "started"
    };
  }
  private readonly retention: MockTaskRetention;

  constructor(
    private readonly directory: MockUserDirectorySource,
    private readonly credits: MockCreditSource,
    now: () => number = Date.now
  ) {
    this.retention = new MockTaskRetention(now);
    let ownerIndex = 0;
    for (const record of this.records.values()) {
      if (record.task.checkId) {
        record.task.checkLabel = mockCatalog.checks.find(
          (check) => check.checkId === record.task.checkId
        )?.label;
        record.task.checkTypeLabel = checkTypeLabel(record.task.checkType, mockCatalog);
      }
      if (
        [REGISTRATION_CONTRACT, SIMULATED_CONTRACT].includes(
          record.task.checkType ?? ""
        )
      )
        for (const row of record.results)
          if (row.details && typeof row.matched === "boolean")
            row.details.registered = row.matched;
      this.retention.observe(record.task);
      if (record.task.checkId && !record.task.managed)
        record.task.userId =
          developmentUsers[ownerIndex++ % developmentUsers.length]!.userId;
      if (record.task.state === "terminal")
        this.states.set(record.task.taskId, { endReason: "completed" });
    }
    // Explicit file metadata for the seeded demos, independent of live Task counts.
    for (const [id, count] of [
      ["check-review", 24000],
      ["check-live", 48000],
      ["check-delivered", 12800]
    ] as const) {
      this.imports.set(id, {
        sourceFile: this.require(id).task.sourceFile!,
        summary: {
          inputCount: count,
          emptyCount: 0,
          validCount: count,
          duplicateCount: 0,
          invalidCount: 0,
          blocked: false,
          overLimit: false
        }
      });
    }
  }
  async catalog() {
    return structuredClone({
      ...mockCatalog,
      checks: Object.entries(this.effectiveConfiguration.configuration.checks)
        .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
        .map(
          ([checkId, { label, workerGroupId, checkType, execution, unitPrice }]) => ({
            checkId,
            unitPrice,
            label,
            workerGroupId,
            checkType,
            execution
          })
        )
    });
  }
  async loadConfiguration(caller: TaskCaller): Promise<AppCheckConfigurationView> {
    caller = this.directory.resolveCaller(caller, "app-checks");
    requireCallerPermission(caller, "app-checks.configuration");
    const saved = structuredClone(this.savedConfiguration);
    const effective = structuredClone(this.effectiveConfiguration);
    const [savedFingerprint, effectiveFingerprint] = await Promise.all([
      mockConfigurationFingerprint(saved.configuration),
      mockConfigurationFingerprint(effective.configuration)
    ]);
    return structuredClone({
      checkTypes: mockCheckTypes,
      saved: { ...saved, fingerprint: savedFingerprint },
      effective: { ...effective, fingerprint: effectiveFingerprint },
      restartRequired: savedFingerprint !== effectiveFingerprint
    });
  }
  async saveConfiguration(
    caller: TaskCaller,
    expectedFingerprint: string,
    configuration: Record<string, unknown>
  ) {
    caller = this.directory.resolveCaller(caller, "app-checks");
    requireCallerPermission(caller, "app-checks.configuration");
    if (!/^[0-9a-f]{64}$/.test(expectedFingerprint))
      throw new TaskApiError(400, "expectedFingerprint 必须是读取到的 SHA-256 指纹");
    const validated = validateMockSettings(configuration);
    const previous = this.savedConfiguration;
    const fingerprint = await mockConfigurationFingerprint(previous.configuration);
    if (
      expectedFingerprint !== fingerprint ||
      settingsIdentity(previous.configuration) !==
        settingsIdentity(this.savedConfiguration.configuration)
    )
      throw new TaskApiError(409, "配置内容已变化，请重新读取后核对");
    requireSettingsUpdate(previous.configuration, validated);
    this.savedConfiguration = { configuration: validated };
    return this.loadConfiguration(caller);
  }
  async simulateConfigurationRestart(caller: TaskCaller) {
    caller = this.directory.resolveCaller(caller, "app-checks");
    requireCallerPermission(caller, "app-checks.configuration");
    this.effectiveConfiguration = structuredClone(this.savedConfiguration);
  }
  async listTasks(caller: TaskCaller, userId?: string) {
    caller = this.directory.resolveCaller(caller, "app-checks");
    const owner = selectTaskUser(caller, userId);
    const tasks = [...this.records.values()]
      .map((r) => r.task)
      .filter((task) => owner === undefined || task.userId === owner)
      .sort(
        (a, b) =>
          taskUserScore(a.userId) - taskUserScore(b.userId) ||
          compareTaskIds(a.taskId, b.taskId)
      );
    return structuredClone({
      tasks: tasks
        .slice(0, 100)
        .sort(
          (a, b) =>
            (b.createdAtMillis ?? -1) - (a.createdAtMillis ?? -1) ||
            compareTaskIds(a.taskId, b.taskId)
        ),
      truncated: tasks.length > 100
    });
  }
  private owned(caller: TaskCaller, id: string) {
    caller = this.directory.resolveCaller(caller, "app-checks");
    const record = this.require(id);
    requireTaskCaller(caller, record.task);
    return record;
  }
  private require(id: string) {
    const record = this.records.get(id);
    if (!record) throw new Error("没有找到 Mock 任务，刷新应用会重置会话数据。");
    this.retention.observe(record.task);
    return record;
  }
  previewState(caller: TaskCaller, id: string): PreviewState {
    this.owned(caller, id);
    return { ...this.states.get(id) };
  }
  private recordActivity(id: string, label: string, detail?: string) {
    const rows = this.activities.get(id) ?? [];
    rows.push({ at: Date.now(), label, detail });
    this.activities.set(id, rows.slice(-100));
  }
  async loadTask(caller: TaskCaller, id: string) {
    const record = this.owned(caller, id);
    const count = (this.reads.get(id) ?? 0) + 1;
    this.reads.set(id, count);
    if (id === "check-read-error" && count === 2)
      throw new Error("Mock 读取失败；已保留上次数据，可再次刷新。");
    return structuredClone({ ...record, results: record.results.slice(0, 100) });
  }
  async createTask(caller: TaskCaller, input: CreateCheckTask) {
    caller = this.directory.resolveCaller(caller, "app-checks");
    if (
      "userId" in input ||
      "role" in input ||
      "simulation" in input ||
      "appId" in input
    )
      throw new TaskApiError(400, "Unknown application check fields");
    const checks = this.effectiveConfiguration.configuration.checks;
    const app = Object.hasOwn(checks, input.checkId)
      ? checks[input.checkId]
      : undefined;
    if (!app) throw new Error("当前生效配置不支持此检查项");
    const type = mockCheckTypes.find((type) => type.id === app.checkType)!;
    if (
      type.seedType === "phonenumber"
        ? !mockCatalog.countries.includes(input.country!)
        : input.country !== undefined
    )
      throw new TaskApiError(400, "检查类型与国家选项不符");
    const identity = JSON.stringify([
      "app-checks/v5/create",
      caller.userId,
      input.checkId,
      input.country
    ]);
    const prior = this.requests.get(caller.userId + "/" + input.requestId);
    if (prior) {
      if (prior.input !== identity) throw new Error("requestId 已用于其他内容");
      return { taskId: prior.taskId };
    }
    const taskId = "mock-check-" + ++this.sequence;
    this.records.set(taskId, {
      task: task(taskId, {
        userId: caller.userId,
        name: `${app.label}${input.country ? " · " + input.country : ""} · ${new Date().toISOString()}`,
        checkLabel: app.label,
        checkTypeLabel: type.label,
        seedType: type.seedType,
        checkId: input.checkId,
        workerGroupId: app.workerGroupId,
        country: input.country,
        createdAtMillis: Date.now(),
        checkType: app.checkType,
        unitPrice: app.unitPrice,
        contractParameters: structuredClone(
          app.parameters ??
            this.effectiveConfiguration.configuration.common.typeDefaults[app.checkType]
        ),
        eventCode: app.execution.eventCode,
        seedsPerItem: app.execution.seedsPerItem,
        workerSelector: structuredClone(app.execution.workerSelector),
        refill: structuredClone(app.execution.refill),
        mockPolicy: structuredClone(mockTaskPolicy),
        salt: undefined,
        saltDate: undefined,
        state: "pre_review",
        totalCount: 0,
        activeCount: 0,
        succeededCount: 0
      }),
      results: [],
      resultsTruncated: false
    });
    this.imported.set(taskId, new Map());
    this.requests.set(caller.userId + "/" + input.requestId, {
      input: identity,
      taskId
    });
    this.recordActivity(taskId, "创建任务", "空任务已创建，等待导入种子");
    return { taskId };
  }
  async importSeeds(
    caller: TaskCaller,
    id: string,
    file: Blob,
    summary?: ImportSnapshot
  ) {
    this.owned(caller, id);
    return this.mutate(id, async () => {
      const record = this.owned(caller, id);
      await this.requireUncharged(caller, id);
      if (record.task.state !== "pre_review")
        throw new Error("只有新版本待审核任务支持导入");
      if (file.size > mockCatalog.limits.importFileBytes)
        throw new Error("种子文件不能超过 10 MiB");
      const bytes =
        typeof file.arrayBuffer === "function"
          ? await file.arrayBuffer()
          : await new Promise<ArrayBuffer>((resolve, reject) => {
              const reader = new FileReader();
              reader.onload = () => resolve(reader.result as ArrayBuffer);
              reader.onerror = () => reject(reader.error);
              reader.readAsArrayBuffer(file);
            });
      const text = decodeUtf8(bytes);
      if (record.task.state !== "pre_review")
        throw new Error("任务状态已变化，请刷新后核对");
      const report = inspectImport(text, {
        format: "txt",
        seedType: record.task.seedType!,
        country: record.task.country ?? "",
        limit: mockCatalog.limits.seedsPerImport,
        header: false,
        column: 0
      });
      if (report.blocked || report.overLimit || !report.seeds.length)
        throw new Error("种子文件校验失败，未写入任何种子");
      if (record.task.checkType === PRIME_CONTRACT) {
        const bad = report.rows.find(
          (row) =>
            row.kind !== "empty" &&
            (!/^[0-9]{1,10}$/.test(row.seed) || Number(row.seed) > 4294967295)
        );
        if (bad)
          throw new TaskApiError(
            400,
            `种子文件第 ${bad.line} 行不是 uint32，未写入任何种子`
          );
      }
      const prior = this.seedGroups(id);
      let added = 0,
        existing = 0;
      const size = record.task.seedsPerItem!;
      const itemCount = Math.ceil(report.seeds.length / size);
      for (let offset = 0; offset < report.seeds.length; offset += size) {
        const seeds = report.seeds.slice(offset, offset + size),
          key = JSON.stringify(seeds);
        if (prior.has(key)) existing++;
        else {
          prior.set(key, { messageId: `sample-${prior.size}`, seeds });
          added++;
        }
      }
      record.task.totalCount = prior.size;
      record.task.activeCount = prior.size;
      const receipt = {
        taskId: id,
        inputCount: report.inputCount,
        emptyCount: report.emptyCount,
        duplicateCount: report.duplicateCount,
        uniqueCount: report.seeds.length,
        itemCount,
        addedCount: added,
        existingCount: existing
      };
      if (summary) this.imports.set(id, structuredClone({ ...summary, receipt }));
      this.recordActivity(
        id,
        "导入种子",
        `${report.seeds.length} 个种子组装为 ${itemCount} 个执行项；新增 ${added} 个执行项，已存在 ${existing} 个执行项`
      );
      return receipt;
    });
  }
  async approveTask(
    caller: TaskCaller,
    id: string,
    expectedCount: number,
    confirmation: CheckCreditConfirmation
  ) {
    this.owned(caller, id);
    return this.mutate(id, async () => {
      const record = this.owned(caller, id);
      if (
        !confirmation ||
        !Number.isSafeInteger(expectedCount) ||
        expectedCount <= 0 ||
        !Number.isSafeInteger(confirmation.expectedSeedCount) ||
        confirmation.expectedSeedCount <= 0 ||
        !Number.isSafeInteger(confirmation.expectedAmount) ||
        confirmation.expectedAmount <= 0 ||
        !/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(confirmation.requestId)
      )
        throw new TaskApiError(400, "需要有效的启动费用确认");
      let observation = await this.startObservation(caller, id, confirmation.requestId);
      if (!observation) {
        const active = await this.startObservation(caller, id);
        if (active?.receipt.status === "not_observed")
          throw new CheckStartUnconfirmed();
        if (active?.receipt.status === "applied") observation = active;
      }
      if (
        observation?.receipt.status !== "rejected" &&
        record.task.state === "pre_review" &&
        expectedCount !== record.task.totalCount
      )
        throw new TaskApiError(400, "执行项数量已变化，请重新核对");
      let start = observation?.start;
      if (start) {
        if (
          start.quote.itemCount !== expectedCount ||
          start.quote.seedCount !== confirmation.expectedSeedCount ||
          start.quote.amount !== confirmation.expectedAmount ||
          (start.confirmation.requestId === confirmation.requestId &&
            start.actorUserId !== caller.userId)
        )
          throw new TaskApiError(409, "启动请求或费用确认与原操作不一致");
        if (observation!.receipt.status === "rejected")
          throw new TaskApiError(409, "余额不足，本次启动请求已拒绝；请充值后重新确认");
      } else {
        if (record.task.state !== "pre_review")
          throw new TaskApiError(400, "当前任务不在待审核状态");
        const quote = await this.loadCredits(caller, id);
        if (
          quote.itemCount !== expectedCount ||
          quote.seedCount !== confirmation.expectedSeedCount ||
          quote.amount !== confirmation.expectedAmount
        )
          throw new TaskApiError(409, "输入数量或费用已变化，请重新确认");
        start = {
          confirmation: Object.freeze({ ...confirmation }),
          ledgerRequestId: crypto.randomUUID(),
          actorUserId: caller.userId,
          quote
        };
        const attempts = this.creditStarts.get(id) ?? new Map<string, MockCheckStart>();
        attempts.set(confirmation.requestId, start);
        this.creditStarts.set(id, attempts);
        this.activeStarts.set(id, confirmation.requestId);
      }
      try {
        if (observation?.receipt.status !== "applied")
          await this.credits.debit(caller, start.quote.userId, start.ledgerRequestId, {
            source: "app-checks",
            taskId: id,
            checkId: record.task.checkId!,
            unitPrice: start.quote.unitPrice,
            quantity: start.quote.seedCount
          });
      } catch (error) {
        if (error instanceof TaskApiError && error.status < 500 && error.status !== 408)
          throw error;
        throw new CheckStartUnconfirmed();
      }
      if (["running_visible", "running-initial"].includes(record.task.state ?? ""))
        return;
      if (record.task.state !== "pre_review")
        throw new TaskApiError(400, "任务已经结束，不会重新启动或重复扣分");
      record.task.state = "running_visible";
      record.task.reviewedAt = Date.now();
      this.recordActivity(
        id,
        "扣分并启动",
        `${start.quote.seedCount} 个种子，扣除 ${start.quote.amount} 积分`
      );
    });
  }
  private running(id: string) {
    const record = this.require(id);
    if (!["running_visible", "running-initial"].includes(record.task.state ?? ""))
      throw new Error("当前任务不在处理中");
    return record;
  }
  async closeTask(caller: TaskCaller, id: string) {
    this.owned(caller, id);
    return this.mutate(id, async () => {
      const record = this.owned(caller, id);
      const pending = await this.startObservation(caller, id);
      if (pending?.receipt.status === "not_observed") throw new CheckStartUnconfirmed();
      if (record.task.state === "terminal") return;
      if (
        !["pre_review", "running_visible", "running-initial"].includes(
          record.task.state ?? ""
        )
      )
        throw new Error("已结束或状态不可用的任务不能再次关闭");
      const cancelled = record.task.state === "pre_review";
      record.task.state = "terminal";
      this.retention.observe(record.task);
      this.states.set(id, { endReason: cancelled ? "cancelled" : "stopped" });
      this.recordActivity(
        id,
        cancelled ? "取消任务" : "中止任务",
        "保留已有结果和未完成数量"
      );
    });
  }
  private seedGroups(id: string) {
    let groups = this.imported.get(id);
    if (!groups) {
      groups = new Map();
      const record = this.require(id);
      for (let i = 0; i < (record.task.totalCount ?? 0); i++) {
        const seed =
          record.results[i]?.seed ??
          (record.task.country === "US"
            ? "+1202" + String(5500000 + i)
            : "+86138" + String(i).padStart(8, "0"));
        groups.set(JSON.stringify([seed]), {
          messageId: record.results[i]?.messageId ?? `sample-${i}`,
          seeds: [seed]
        });
      }
      this.imported.set(id, groups);
    }
    return groups;
  }
  private sampleAnswer(
    task: CheckTask,
    seed: string,
    index: number,
    messageId: string
  ): CheckResult {
    const base = { messageId, seed, resultStatus: "succeeded" as const };
    if (isMd5Contract(task.checkType)) {
      const samples: Record<string, string> = {
        a: "0cc175b9c0f1b6a831c399e269772661",
        abc: "900150983cd24fb0d6963f7d28e17f72",
        中文: "a7bac2239fcdcb3a067903d8077c4a07",
        A: "7fc56270e7a70fa81a5935b72eacbe29"
      };
      const md5 = samples[seed];
      return md5
        ? { ...base, matched: md5.startsWith("0"), details: { md5 } }
        : { ...base, resultStatus: "failed" };
    }
    if (task.checkType === PRIME_CONTRACT) {
      const samples: Record<string, boolean> = {
        "0": false,
        "1": false,
        "2": true,
        "17": true,
        "017": true,
        "49": false,
        "4294967291": true,
        "4294967295": false
      };
      return Object.hasOwn(samples, seed)
        ? { ...base, matched: samples[seed], details: { isPrime: samples[seed] } }
        : { ...base, resultStatus: "failed" };
    }
    if (isCarrierContract(task.checkType)) {
      return {
        ...base,
        matched: index % 3 === 0,
        details: {
          originalCarrier: index % 3 === 0 ? "Mock Carrier" : "",
          reason: ["IDENTIFIED", "NO_CARRIER_MAPPING", "INVALID_PHONE_NUMBER"][
            index % 3
          ]
        }
      };
    }
    if (task.checkType === REGISTRATION_CONTRACT) {
      const registered = index % 3 !== 0;
      return {
        ...base,
        matched: registered,
        details: {
          registered,
          workerId: `demo-worker-${(index % 20) + 1}`,
          workerGroupId: task.workerGroupId!
        }
      };
    }
    const parameters = simulationSchema.parse(task.contractParameters),
      bucket = index % 1000;
    if (parameters.ranges.failed[0] <= bucket && bucket < parameters.ranges.failed[1])
      return { ...base, resultStatus: "failed" };
    const registered =
      parameters.ranges.registered[0] <= bucket &&
      bucket < parameters.ranges.registered[1];
    return {
      ...base,
      matched: registered,
      details: {
        registered,
        workerId: `demo-worker-${(index % 20) + 1}`,
        workerGroupId: task.workerGroupId!,
        simulatedDelayMillis: parameters.delayMs[0]
      }
    };
  }
  private appendResults(id: string, count: number) {
    const record = this.require(id);
    this.retention.requireWritable(record.task);
    const completed = new Set(record.results.map((row) => row.messageId));
    let remaining = count,
      offset = 0;
    for (const group of this.seedGroups(id).values()) {
      if (!completed.has(group.messageId) && remaining > 0) {
        const rows = group.seeds.map((seed, index) =>
          this.sampleAnswer(record.task, seed, offset + index, group.messageId)
        );
        // One failed invocation has no successful per-seed Runtime answers.
        const failed = rows.some((row) => row.resultStatus === "failed");
        for (const row of rows)
          record.results.push(
            failed
              ? { messageId: group.messageId, seed: row.seed, resultStatus: "failed" }
              : row
          );
        remaining--;
      }
      offset += group.seeds.length;
    }
    const failedItems = new Set(
      record.results
        .filter((row) => row.resultStatus === "failed")
        .map((row) => row.messageId)
    );
    const observedItems = new Set(record.results.map((row) => row.messageId));
    record.task.failedCount = failedItems.size;
    record.task.succeededCount = observedItems.size - failedItems.size;
    record.task.activeCount = (record.task.totalCount ?? 0) - observedItems.size;
    record.resultsTruncated = record.results.length > 100;
    record.task.salt = undefined;
  }
  async advanceTask(caller: TaskCaller, id: string, late = false) {
    caller = this.directory.resolveCaller(caller, "app-checks");
    requireCallerPermission(caller, "demo.control");
    const record = this.require(id),
      state = this.states.get(id);
    if (late) {
      if (state?.endReason !== "stopped")
        throw new Error("仅中止任务可演示已有执行的后续结果");
    } else {
      this.running(id);
    }
    if (!(record.task.activeCount ?? 0)) throw new Error("没有未结束的执行项");
    this.appendResults(
      id,
      late ? 1 : Math.max(1, Math.ceil((record.task.totalCount ?? 0) / 10))
    );
    this.recordActivity(id, late ? "已有执行返回结果" : "推进一批演示结果");
    if (!record.task.activeCount && record.task.state !== "terminal") {
      record.task.state = "terminal";
      this.retention.observe(record.task);
      this.states.set(id, { endReason: "completed" });
      this.recordActivity(id, "查询结束");
    }
  }
  async completeTask(caller: TaskCaller, id: string) {
    caller = this.directory.resolveCaller(caller, "app-checks");
    requireCallerPermission(caller, "demo.control");
    const record = this.running(id);
    this.appendResults(id, record.task.totalCount ?? 0);
    record.task.state = "terminal";
    this.retention.observe(record.task);
    this.states.set(id, { endReason: "completed" });
    this.recordActivity(id, "查询结束", "剩余演示结果已生成");
  }
  async runRetention(caller: TaskCaller, id: string) {
    caller = this.directory.resolveCaller(caller, "app-checks");
    requireCallerPermission(caller, "tasks.retention.manage");
    const record = this.owned(caller, id);
    const outcome = this.retention.maintain(record.task);
    if (outcome === "archived") this.imported.delete(id);
    if (outcome === "purged") {
      for (const map of [
        this.records,
        this.reads,
        this.states,
        this.activities,
        this.imports,
        this.imported,
        this.creditStarts,
        this.activeStarts
      ])
        map.delete(id);
      for (const [key, request] of this.requests)
        if (request.taskId === id) this.requests.delete(key);
    }
    return outcome;
  }
  async loadImport(caller: TaskCaller, id: string) {
    this.owned(caller, id);
    const snapshot = this.imports.get(id);
    return snapshot ? structuredClone(snapshot) : undefined;
  }
  async loadActivity(caller: TaskCaller, id: string) {
    this.owned(caller, id);
    return structuredClone(this.activities.get(id) ?? []);
  }
  private exportRows(id: string, filter: ExportFilter) {
    return this.require(id).results.filter(
      (row) =>
        row.resultStatus === "succeeded" &&
        !row.contentError &&
        typeof row.matched === "boolean" &&
        row.seed &&
        (filter === "all" || row.matched === (filter === "matched"))
    );
  }
  async exportCounts(caller: TaskCaller, id: string) {
    this.owned(caller, id);
    return {
      all: this.exportRows(id, "all").length,
      matched: this.exportRows(id, "matched").length,
      unmatched: this.exportRows(id, "unmatched").length
    };
  }
  async exportTask(caller: TaskCaller, id: string, filter: ExportFilter) {
    const record = this.owned(caller, id);
    if (record.task.state !== "terminal") throw new Error("任务结束后可以导出演示结果");
    const rows = this.exportRows(id, filter);
    const carrier = isCarrierContract(record.task.checkType),
      phone = record.task.seedType === "phonenumber";
    const text = isMd5Contract(record.task.checkType),
      prime = record.task.checkType === PRIME_CONTRACT;
    const lines = [
      [
        "种子",
        "判定",
        "检查类型",
        "执行配置",
        ...(phone ? ["地区"] : []),
        ...(carrier
          ? ["原始运营商", "识别说明"]
          : text
            ? ["MD5 摘要"]
            : prime
              ? ["是否素数"]
              : ["注册状态"])
      ]
        .map(csvCell)
        .join(","),
      ...rows.map((row) =>
        [
          row.seed!,
          row.matched ? "满足" : "不满足",
          record.task.checkTypeLabel ?? "",
          record.task.checkLabel ?? "",
          ...(phone ? [record.task.country ?? ""] : []),
          ...(carrier
            ? [
                String(row.details?.originalCarrier ?? ""),
                String(row.details?.reason ?? "")
              ]
            : text
              ? [String(row.details?.md5 ?? "")]
              : prime
                ? [row.details?.isPrime ? "是" : "否"]
                : [row.details?.registered ? "已注册" : "未注册"])
        ]
          .map(csvCell)
          .join(",")
      )
    ];
    this.recordActivity(id, "生成结果文件", "导出 " + rows.length + " 条有效成功结果");
    return {
      blob: new Blob(["\uFEFF" + lines.join("\r\n")], {
        type: "text/csv;charset=utf-8"
      }),
      fileName: id + "-" + filter + "-demo.csv",
      count: rows.length
    };
  }
}
