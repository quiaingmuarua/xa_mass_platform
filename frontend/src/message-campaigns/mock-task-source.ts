import { inspectRecipientInput, MESSAGE_IMPORT_LIMITS } from "@/files/phone-numbers";
import type {
  CreateMessageTask,
  MessageTask,
  MessageTaskDetail,
  MessageTaskResult,
  MessageTaskSource
} from "./task-source";

const epoch = Date.parse("2026-09-18T08:00:00+08:00");
const body = JSON.stringify(
  {
    receipts_status: ["read", "replied"],
    delayMs: [1000, 4000],
    probability: 0.5,
    text: "收到了"
  },
  null,
  2
);

function task(
  taskId: string,
  index: number,
  fields: Partial<MessageTask>
): MessageTask {
  return {
    taskId,
    createdAtMillis: epoch - index * 3_600_000,
    workerGroupId: "demo-sim",
    managed: false,
    state: "terminal",
    inputVersion: "2",
    ...fields
  };
}
function results(count: number): MessageTaskResult[] {
  return Array.from({ length: count }, (_, i) => ({
    messageId: `message-${String(i + 1).padStart(4, "0")}`,
    resultStatus: i % 11 === 0 ? "failed" : "succeeded",
    recipientId: `+86138${String(i + 1).padStart(8, "0")}`,
    status:
      i % 11 === 0
        ? "EXECUTION_FAILED"
        : i % 3 === 0
          ? "REPLIED"
          : i % 3 === 1
            ? "READ"
            : "DELIVERED",
    ...(i % 11 === 0
      ? {}
      : {
          phone: "+12025550123",
          workerId: `worker-us-${String((i % 8) + 1).padStart(2, "0")}`,
          ...(i % 3 === 0 ? { reply: "收到了，谢谢通知。" } : {})
        }),
    observedAtMillis: epoch + i * 1000
  }));
}
function initialRecords(): MessageTaskDetail[] {
  return [
    {
      task: task("msg-autumn-cn", 0, {
        name: "秋季活动 · 国内收件人",
        recipientCountry: "CN",
        senderCountry: "US",
        sendTotal: 280,
        deliveredCount: 254,
        body
      }),
      results: results(280),
      resultsTruncated: false
    },
    {
      task: task("msg-follow-up", 1, {
        name: "订单通知 · 持续回执",
        recipientCountry: "CN",
        senderCountry: null,
        sendTotal: 1,
        deliveredCount: 0,
        body: "{}"
      }),
      results: [{ ...results(2)[1], status: "SENT" }],
      resultsTruncated: false
    },
    {
      task: task("msg-uk-running", 2, {
        name: "英国收件人 · 发送中",
        state: "running_visible",
        recipientCountry: "GB",
        senderCountry: "GB",
        sendTotal: 650,
        deliveredCount: 3,
        body
      }),
      results: results(4).map((r, i) => ({
        ...r,
        recipientId: `+447700900${100 + i}`,
        phone: "+447700900001",
        workerId: "worker-gb-01"
      })),
      resultsTruncated: false
    },
    {
      task: task("msg-any-waiting", 3, {
        name: "预约提醒 · ANY 发送",
        state: "running-initial",
        recipientCountry: "US",
        senderCountry: null,
        sendTotal: 80,
        deliveredCount: 0,
        body: "{}"
      }),
      results: [],
      resultsTruncated: false
    },
    {
      task: task("msg-cn-running", 4, {
        name: "服务通知 · 发送中",
        state: "running_visible",
        recipientCountry: "CN",
        senderCountry: "CN",
        sendTotal: 120,
        deliveredCount: 0,
        body: "{}"
      }),
      results: [],
      resultsTruncated: false
    },
    {
      task: task("msg-restored-task", 5, {}),
      results: results(3),
      resultsTruncated: false
    },
    {
      task: task("msg-read-error", 6, {
        name: "结果读取失败 · 恢复样例",
        recipientCountry: "CN",
        senderCountry: null,
        sendTotal: 3,
        deliveredCount: 2,
        body: "{}"
      }),
      results: results(3),
      resultsTruncated: false
    },
    {
      task: task("msg-unknown-state", 7, { name: "任务状态暂不可用", state: null }),
      results: [],
      resultsTruncated: false
    },
    {
      task: task("project-managed-messages", 8, {
        name: "Project managed Task",
        managed: true,
        state: "running-initial"
      }),
      results: [],
      resultsTruncated: false
    }
  ];
}

/** Explicit, process-local UI samples. No network, storage, timers or execution simulation. */
export class MockMessageTaskSource implements MessageTaskSource {
  readonly mode = "mock";
  private readonly records: Map<string, MessageTaskDetail>;
  private readonly reads = new Map<string, number>();
  private readonly requests = new Map<string, { input: string; taskId: string }>();
  private sequence = 0;
  private readonly recipients = new Map<string, Set<string>>();
  private readonly mutations = new Set<string>();
  private activeImports = 0;

  constructor(records: MessageTaskDetail[] = initialRecords()) {
    this.records = new Map(
      structuredClone(records).map((record) => [record.task.taskId, record])
    );
  }
  async listTasks() {
    const tasks = [...this.records.values()]
      .map(({ task }) => task)
      .sort(
        (a, b) =>
          b.createdAtMillis - a.createdAtMillis || a.taskId.localeCompare(b.taskId)
      );
    return structuredClone({
      tasks: tasks.slice(0, 100),
      truncated: tasks.length > 100
    });
  }
  async loadTask(taskId: string): Promise<MessageTaskDetail> {
    const record = this.records.get(taskId);
    if (!record)
      throw new Error("没有找到这个 Mock 任务。刷新页面会重置本地创建的任务。");
    const read = (this.reads.get(taskId) ?? 0) + 1;
    this.reads.set(taskId, read);
    // A fixed second-read failure makes preservation and manual recovery inspectable.
    if (taskId === "msg-read-error" && read === 2)
      throw new Error("Mock 结果读取失败。已保留上次数据，可再次刷新。");
    if (taskId === "msg-follow-up") {
      // READ and REPLIED both establish delivery of the same message, counted once.
      record.task.deliveredCount = read === 1 ? 0 : 1;
      record.results[0] = {
        ...record.results[0],
        status: read === 1 ? "SENT" : read === 2 ? "READ" : "REPLIED",
        observedAtMillis: epoch + Math.min(read, 3) * 1000,
        ...(read >= 3 ? { reply: "已确认，明天会到。" } : {})
      };
    }
    return structuredClone({
      task: record.task,
      results: record.results.slice(0, 100),
      resultsTruncated: record.resultsTruncated || record.results.length > 100
    });
  }
  async createTask(input: CreateMessageTask) {
    const fingerprint = JSON.stringify([
      input.name,
      input.recipientCountry,
      input.senderCountry ?? null,
      input.senderPhone?.trim() ? input.senderPhone : null,
      input.body
    ]);
    const prior = this.requests.get(input.requestId);
    if (prior) {
      if (prior.input !== fingerprint)
        throw new Error("这个申请已用于不同的任务内容。");
      return { taskId: prior.taskId };
    }
    const taskId = `mock-message-task-${++this.sequence}`;
    this.records.set(taskId, {
      task: {
        taskId,
        name: input.name,
        createdAtMillis: Math.max(Date.now(), epoch) + this.sequence,
        workerGroupId: "demo-sim",
        managed: false,
        state: "pre_review",
        inputVersion: "2",
        recipientCountry: input.recipientCountry,
        senderCountry: input.senderCountry,
        sendTotal: 0,
        sentCount: 0,
        readCount: 0,
        repliedCount: 0,
        failedCount: 0,
        deliveredCount: 0,
        senderPhone: input.senderPhone,
        body: input.body
      },
      results: [],
      resultsTruncated: false
    });
    this.requests.set(input.requestId, { input: fingerprint, taskId });
    return { taskId };
  }
  private requireTask(taskId: string, review: boolean) {
    const record = this.records.get(taskId);
    if (
      !record ||
      record.task.managed ||
      !record.task.body ||
      !record.task.recipientCountry
    )
      throw new Error("只支持 Messages 有限任务");
    if (record.task.state === null) throw new Error("任务状态不可用");
    if (review && record.task.inputVersion !== "2")
      throw new Error("旧输入版本仅支持读取和关闭");
    if (review && record.task.state !== "pre_review")
      throw new Error("任务不在待审核状态");
    return record;
  }
  async importRecipients(taskId: string, text: string) {
    if (this.activeImports >= 2) throw new Error("导入繁忙，请稍后重试");
    if (this.mutations.has(taskId)) throw new Error("任务有其他操作正在进行");
    this.mutations.add(taskId);
    this.activeImports++;
    try {
      const record = this.requireTask(taskId, true);
      const checked = await inspectRecipientInput(
        text,
        record.task.recipientCountry!,
        MESSAGE_IMPORT_LIMITS
      );
      if (checked.issues.length || !checked.validCount)
        throw new Error(checked.issues[0]?.message ?? "号码文件不能为空");
      const numbers = this.recipients.get(taskId) ?? new Set<string>();
      let existingCount = 0;
      for (const number of checked.recipients) {
        if (numbers.has(number)) existingCount++;
        else numbers.add(number);
      }
      this.recipients.set(taskId, numbers);
      record.task.sendTotal = numbers.size;
      return {
        taskId,
        inputCount: checked.inputCount,
        emptyCount: checked.emptyCount,
        duplicateCount: checked.duplicateCount,
        uniqueCount: checked.validCount,
        confirmedAddedCount: checked.validCount - existingCount,
        existingCount
      };
    } finally {
      this.mutations.delete(taskId);
      this.activeImports--;
    }
  }
  async approveTask(taskId: string, expectedCount: number) {
    if (this.mutations.has(taskId)) throw new Error("任务有其他操作正在进行");
    const record = this.requireTask(taskId, true);
    if (!expectedCount || expectedCount !== record.task.sendTotal)
      throw new Error("收件人数为空或已变化，请刷新后重新核对");
    record.task.state = "running_visible";
  }
  async closeTask(taskId: string) {
    if (this.mutations.has(taskId)) throw new Error("任务有其他操作正在进行");
    this.requireTask(taskId, false).task.state = "terminal";
  }
}
