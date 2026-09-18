import type { AppCheckTaskSource } from "./task-source";
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
  limits: { tasks: 50, items: 50000, numbersPerTask: 1000 },
  simulationExample: { ranges: rangeExamples.混合, delayMs: [2000, 5000] }
};
const epoch = Date.UTC(2026, 8, 18, 8);
function task(taskId: string, changes: Partial<CheckTask> = {}): CheckTask {
  return {
    taskId,
    name: taskId,
    createdAtMillis: epoch,
    workerGroupId: "app-a-sim",
    managed: false,
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
      task: task("check-mixed", {
        name: "混合结果与复算",
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
        name: "未注册也是执行成功",
        simulation: { ranges: rangeExamples.全未注册, delayMs: [0, 0] }
      }),
      results: [{ ...registered, registered: false, simulatedDelayMillis: 0 }],
      resultsTruncated: false
    },
    {
      task: task("check-running", {
        name: "App B · 查询进行中",
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
        name: "有界预览 · 120 个结果",
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
        name: "当前无 Result",
        succeededCount: 0,
        activeCount: 1,
        state: "running-initial"
      }),
      results: [],
      resultsTruncated: false
    },
    {
      task: task("check-read-error", { name: "读取错误恢复 · 第二次读取失败" }),
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
export class MockAppCheckTaskSource implements AppCheckTaskSource {
  readonly mode = "mock";
  private readonly records = new Map(records().map((r) => [r.task.taskId, r]));
  private readonly requests = new Map<string, { input: string; taskId: string }>();
  private readonly reads = new Map<string, number>();
  private sequence = 0;
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
  async loadTask(id: string) {
    const record = this.records.get(id);
    if (!record) throw new Error("没有找到 Mock 任务，刷新应用会重置会话数据。");
    const count = (this.reads.get(id) ?? 0) + 1;
    this.reads.set(id, count);
    if (id === "check-read-error" && count === 2)
      throw new Error("Mock 读取失败；已保留上次数据，可再次刷新。");
    return structuredClone({ ...record, results: record.results.slice(0, 100) });
  }
  async createTask(input: CreateCheckTask) {
    const prior = this.requests.get(input.requestId);
    if (prior) {
      if (prior.input !== JSON.stringify(input))
        throw new Error("requestId 已用于其他内容");
      return { taskId: prior.taskId };
    }
    const taskId = `mock-check-${++this.sequence}`;
    this.records.set(taskId, {
      task: task(taskId, {
        name: input.name,
        appId: input.appId,
        workerGroupId: `${input.appId}-sim`,
        country: input.country,
        createdAtMillis: Date.now(),
        simulation: structuredClone(input.simulation),
        salt: undefined,
        saltDate: undefined,
        state: "running_visible",
        totalCount: input.numbers.length,
        activeCount: input.numbers.length,
        succeededCount: 0
      }),
      results: [],
      resultsTruncated: false
    });
    this.requests.set(input.requestId, { input: JSON.stringify(input), taskId });
    return { taskId };
  }
}
