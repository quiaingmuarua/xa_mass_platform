import type { AppCheckTaskSource } from "./task-source";
import { csvCell, inspectImport, type ImportSnapshot } from "./import-model";
import { type PreviewState, type ExportFilter, type TaskActivity } from "./workbench";
import {
  type Catalog,
  type CheckDetail,
  type CheckTask,
  type CreateCheckTask,
  rangeExamples
} from "./model";

export const mockCatalog: Catalog = {
  projectId: "app-checks",
  name: "应用注册查询",
  version: "0.1.0-preview",
  apps: ["app-a", "app-b"].map((appId) => ({ appId, workerGroupId: `${appId}-sim` })),
  countries: ["CN", "US", "GB"],
  limits: { numbersPerImport: 100000, importFileBytes: 10 * 1024 * 1024 },
  simulationExample: { ranges: rangeExamples.混合, delayMs: [2000, 5000] }
};
const epoch = Date.UTC(2026, 9, 8, 2);
function task(taskId: string, changes: Partial<CheckTask> = {}): CheckTask {
  return {
    taskId,
    name: taskId,
    createdAtMillis: epoch,
    workerGroupId: "app-a-sim",
    managed: false,
    inputVersion: "2",
    state: "terminal",
    appId: "app-a",
    country: "CN",
    simulation: structuredClone(mockCatalog.simulationExample),
    salt: "fixed-salt",
    saltDate: "2026-09-18",
    totalCount: 1,
    activeCount: 0,
    succeededCount: 1,
    failedCount: 0,
    ...changes
  };
}
function records(): CheckDetail[] {
  // Fixed, independently checked vector shared by the Java protocol tests.
  const registered = {
    messageId: "r-1",
    resultStatus: "succeeded" as const,
    number: "+8613800000001",
    registered: true,
    workerId: "worker-a",
    workerGroupId: "app-a-sim",
    simulatedDelayMillis: 4067
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
        appId: "app-b",
        workerGroupId: "app-b-sim",
        country: "GB",
        createdAtMillis: epoch + 1_000,
        state: "running_visible",
        totalCount: 48000,
        activeCount: 16000,
        succeededCount: 31872,
        failedCount: 128,
        sourceFile: "uk_new_numbers.txt",
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
        sourceFile: "october_existing_numbers.txt",
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
        { ...registered, messageId: "tampered", registered: false },
        { ...registered, messageId: "invalid", contentError: "Mock 业务内容无法解析" },
        { messageId: "failed", resultStatus: "failed", number: "+8613800000002" }
      ],
      resultsTruncated: false
    },
    {
      task: task("check-unregistered", {
        name: "补充号码查询",
        simulation: { ranges: rangeExamples.全未注册, delayMs: [0, 0] }
      }),
      results: [{ ...registered, registered: false, simulatedDelayMillis: 0 }],
      resultsTruncated: false
    },
    {
      task: task("check-running", {
        name: "App B · 增量号码查询",
        appId: "app-b",
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
        name: "新导入号码查询",
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
function sampleRows(count: number, group: string, country: string, numbers?: string[]) {
  const prefix = country === "US" ? "+1202" : country === "GB" ? "+4477" : "+86138";
  return Array.from({ length: count }, (_, i) =>
    (i + 1) % 250 === 0
      ? {
          messageId: `sample-${i}`,
          number: numbers?.[i] ?? `${prefix}${String(i + 10000000).padStart(8, "0")}`,
          resultStatus: "failed" as const
        }
      : {
          messageId: `sample-${i}`,
          number: numbers?.[i] ?? `${prefix}${String(i + 10000000).padStart(8, "0")}`,
          resultStatus: "succeeded" as const,
          registered: i % 3 !== 0,
          workerId: `demo-worker-${(i % 20) + 1}`,
          workerGroupId: group,
          simulatedDelayMillis: 2400
        }
  );
}
export class MockAppCheckTaskSource implements AppCheckTaskSource {
  readonly mode = "mock";
  private readonly records = new Map(records().map((r) => [r.task.taskId, r]));
  private readonly requests = new Map<string, { input: string; taskId: string }>();
  private readonly reads = new Map<string, number>();
  private readonly states = new Map<string, PreviewState>();
  private readonly activities = new Map<string, TaskActivity[]>();
  private readonly imports = new Map<string, ImportSnapshot>();
  private readonly imported = new Map<string, string[]>();
  private sequence = 0;

  constructor() {
    for (const record of this.records.values()) {
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
    return structuredClone(mockCatalog);
  }
  async listTasks() {
    const tasks = [...this.records.values()]
      .map((r) => r.task)
      .sort(
        (a, b) =>
          b.createdAtMillis - a.createdAtMillis || a.taskId.localeCompare(b.taskId)
      );
    return structuredClone({
      tasks: tasks.slice(0, 100),
      truncated: tasks.length > 100
    });
  }
  private require(id: string) {
    const record = this.records.get(id);
    if (!record) throw new Error("没有找到 Mock 任务，刷新应用会重置会话数据。");
    return record;
  }
  previewState(id: string): PreviewState {
    return { ...this.states.get(id) };
  }
  private recordActivity(id: string, label: string, detail?: string) {
    const rows = this.activities.get(id) ?? [];
    rows.push({ at: Date.now(), label, detail });
    this.activities.set(id, rows.slice(-100));
  }
  async loadTask(id: string) {
    const record = this.require(id);
    const count = (this.reads.get(id) ?? 0) + 1;
    this.reads.set(id, count);
    if (id === "check-read-error" && count === 2)
      throw new Error("Mock 读取失败；已保留上次数据，可再次刷新。");
    return structuredClone({ ...record, results: record.results.slice(0, 100) });
  }
  async createTask(input: CreateCheckTask) {
    const identity = JSON.stringify(input);
    const prior = this.requests.get(input.requestId);
    if (prior) {
      if (prior.input !== identity) throw new Error("requestId 已用于其他内容");
      return { taskId: prior.taskId };
    }
    const taskId = "mock-check-" + ++this.sequence;
    this.records.set(taskId, {
      task: task(taskId, {
        name: `${input.appId} · ${input.country} · ${new Date().toISOString()}`,
        appId: input.appId,
        workerGroupId: input.appId + "-sim",
        country: input.country,
        createdAtMillis: Date.now(),
        simulation: structuredClone(input.simulation),
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
    this.imported.set(taskId, []);
    this.requests.set(input.requestId, { input: identity, taskId });
    this.recordActivity(taskId, "创建任务", "空任务已创建，等待导入号码");
    return { taskId };
  }
  async importNumbers(id: string, file: Blob, summary?: ImportSnapshot) {
    const record = this.require(id);
    if (record.task.state !== "pre_review" || record.task.inputVersion !== "2")
      throw new Error("只有新版本待审核任务支持导入");
    if (file.size > mockCatalog.limits.importFileBytes)
      throw new Error("号码文件不能超过 10 MiB");
    const text =
      typeof file.text === "function"
        ? await file.text()
        : await new Promise<string>((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve(String(reader.result));
            reader.onerror = () => reject(reader.error);
            reader.readAsText(file, "UTF-8");
          });
    if (record.task.state !== "pre_review")
      throw new Error("任务状态已变化，请刷新后核对");
    const report = inspectImport(text, {
      format: "txt",
      country: record.task.country!,
      limit: mockCatalog.limits.numbersPerImport,
      header: false,
      column: 0
    });
    if (report.blocked || report.overLimit || !report.numbers.length)
      throw new Error("号码文件校验失败，未写入任何号码");
    const prior =
      this.imported.get(id) ??
      Array.from({ length: record.task.totalCount ?? 0 }, (_, i) =>
        record.task.country === "US"
          ? "+1202" + String(5500000 + i)
          : "+86138" + String(i).padStart(8, "0")
      );
    const known = new Set(prior);
    let added = 0,
      existing = 0;
    for (const number of report.numbers) {
      if (known.has(number)) existing++;
      else {
        known.add(number);
        prior.push(number);
        added++;
      }
    }
    this.imported.set(id, prior);
    record.task.totalCount = prior.length;
    record.task.activeCount = prior.length;
    const receipt = {
      taskId: id,
      inputCount: report.inputCount,
      emptyCount: report.emptyCount,
      duplicateCount: report.duplicateCount,
      uniqueCount: report.numbers.length,
      addedCount: added,
      existingCount: existing
    };
    if (summary) this.imports.set(id, structuredClone({ ...summary, receipt }));
    this.recordActivity(id, "导入号码", `新增 ${added} 个，已存在 ${existing} 个`);
    return receipt;
  }
  async approveTask(id: string, expectedCount: number) {
    const record = this.require(id);
    if (record.task.state !== "pre_review") throw new Error("当前任务不在待审核状态");
    if (!expectedCount || expectedCount !== record.task.totalCount)
      throw new Error("号码数量已变化或为空，请重新核对");
    record.task.state = "running_visible";
    record.task.reviewedAt = Date.now();
    this.recordActivity(id, "核对并启动", "开始演示查询");
  }
  private running(id: string) {
    const record = this.require(id);
    if (!["running_visible", "running-initial"].includes(record.task.state ?? ""))
      throw new Error("当前任务不在处理中");
    return record;
  }
  async closeTask(id: string) {
    const record = this.require(id);
    if (
      !["pre_review", "running_visible", "running-initial"].includes(
        record.task.state ?? ""
      )
    )
      throw new Error("已结束或状态不可用的任务不能再次关闭");
    const cancelled = record.task.state === "pre_review";
    record.task.state = "terminal";
    this.states.set(id, { endReason: cancelled ? "cancelled" : "stopped" });
    this.recordActivity(
      id,
      cancelled ? "取消任务" : "中止任务",
      "保留已有结果和未完成数量"
    );
  }
  private appendResults(id: string, count: number) {
    const record = this.require(id),
      total = record.task.totalCount ?? 0;
    const start = record.results.length,
      end = Math.min(total, start + count);
    if (end <= start) return;
    const all = sampleRows(
      end,
      record.task.workerGroupId ?? "app-a-sim",
      record.task.country ?? "CN",
      this.imported.get(id)
    );
    record.results.push(...all.slice(start, Math.min(end, start + 1000)));
    // Avoid spreading large imports into a function's argument list.
    for (let offset = start + 1000; offset < end; offset += 1000)
      record.results.push(...all.slice(offset, Math.min(end, offset + 1000)));
    record.task.failedCount = record.results.filter(
      (row) => row.resultStatus === "failed"
    ).length;
    record.task.succeededCount = record.results.length - record.task.failedCount;
    record.task.activeCount = total - record.results.length;
    record.resultsTruncated = record.results.length > 100;
    record.task.salt = undefined;
  }
  async advanceTask(id: string, late = false) {
    const record = this.require(id),
      state = this.states.get(id);
    if (late) {
      if (state?.endReason !== "stopped")
        throw new Error("仅中止任务可演示已有执行的后续结果");
    } else {
      this.running(id);
    }
    if (!(record.task.activeCount ?? 0)) throw new Error("没有未结束的号码");
    this.appendResults(
      id,
      late ? 1 : Math.max(1, Math.ceil((record.task.totalCount ?? 0) / 10))
    );
    this.recordActivity(id, late ? "已有执行返回结果" : "推进一批演示结果");
    if (!record.task.activeCount && record.task.state !== "terminal") {
      record.task.state = "terminal";
      this.states.set(id, { endReason: "completed" });
      this.recordActivity(id, "查询结束");
    }
  }
  async completeTask(id: string) {
    const record = this.running(id);
    this.appendResults(id, record.task.totalCount ?? 0);
    record.task.state = "terminal";
    this.states.set(id, { endReason: "completed" });
    this.recordActivity(id, "查询结束", "剩余演示结果已生成");
  }
  async loadImport(id: string) {
    this.require(id);
    const snapshot = this.imports.get(id);
    return snapshot ? structuredClone(snapshot) : undefined;
  }
  async loadActivity(id: string) {
    this.require(id);
    return structuredClone(this.activities.get(id) ?? []);
  }
  private exportRows(id: string, filter: ExportFilter) {
    return this.require(id).results.filter(
      (row) =>
        row.resultStatus === "succeeded" &&
        !row.contentError &&
        typeof row.registered === "boolean" &&
        row.number &&
        (filter === "all" || row.registered === (filter === "registered"))
    );
  }
  async exportCounts(id: string) {
    return {
      all: this.exportRows(id, "all").length,
      registered: this.exportRows(id, "registered").length,
      unregistered: this.exportRows(id, "unregistered").length
    };
  }
  async exportTask(id: string, filter: ExportFilter) {
    const record = this.require(id);
    if (record.task.state !== "terminal") throw new Error("任务结束后可以导出演示结果");
    const rows = this.exportRows(id, filter);
    const lines = [
      "号码,注册状态,应用,地区",
      ...rows.map((row) =>
        [
          row.number!,
          row.registered ? "已注册" : "未注册",
          record.task.appId ?? "",
          record.task.country ?? ""
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
