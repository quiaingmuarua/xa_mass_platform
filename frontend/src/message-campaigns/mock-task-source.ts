import { inspectRecipientInput, MESSAGE_IMPORT_LIMITS } from "@/files/phone-numbers";
import type {
  CreateMessageTask,
  MessageTask,
  MessageTaskDetail,
  MessageTaskResult,
  MessageTaskSource,
  MessageDemoAction
} from "./task-source";
import { messageApplications } from "./workbench";

const epoch = Date.parse("2026-09-18T08:00:00+08:00");
const body = "您好，欢迎使用消息服务。";

function task(
  taskId: string,
  index: number,
  fields: Partial<MessageTask>
): MessageTask {
  return {
    taskId,
    createdAtMillis: epoch - index * 3_600_000,
    appId: messageApplications[index % 3].id,
    workerGroupId: messageApplications[index % 3].workerGroupId,
    managed: false,
    state: "terminal",
    inputVersion: "3",
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
        body: "您好，这是一条消息。"
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
        body: "您好，这是一条消息。"
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
        body: "您好，这是一条消息。"
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
        body: "您好，这是一条消息。"
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
  readonly applications = messageApplications;
  private readonly records: Map<string, MessageTaskDetail>;
  private readonly failedReads = new Set<string>();
  private replies = 0;
  private readonly requests = new Map<string, { input: string; taskId: string }>();
  private sequence = 0;
  private readonly recipients = new Map<string, Set<string>>();
  private readonly mutations = new Set<string>();
  private activeImports = 0;

  constructor(records: MessageTaskDetail[] = initialRecords()) {
    this.records = new Map(
      structuredClone(records).map((record) => [record.task.taskId, record])
    );
    for (const record of this.records.values()) {
      const task = record.task;
      if (!task.managed && task.body && task.recipientCountry) {
        const prefix = { CN: "+86", US: "+1", GB: "+44" }[task.recipientCountry];
        const numbers = new Set(
          record.results.flatMap((row) => (row.recipientId ? [row.recipientId] : []))
        );
        for (let i = 0; numbers.size < (task.sendTotal ?? 0); i++)
          numbers.add(`${prefix}199${String(i).padStart(8, "0")}`);
        this.recipients.set(task.taskId, numbers);
        const counts = this.counts(record.results);
        task.sentCount ??= counts.sentCount;
        task.failedCount ??= counts.failedCount;
        task.readCount ??= counts.readCount;
        task.repliedCount ??= counts.repliedCount;
      }
    }
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
    if (this.failedReads.delete(taskId))
      throw new Error("Mock 结果读取失败。已保留上次数据，可再次刷新。");
    return structuredClone({
      task: record.task,
      results: record.results.slice(0, 100),
      resultsTruncated: record.resultsTruncated || record.results.length > 100
    });
  }
  async createTask(input: CreateMessageTask) {
    const app = this.applications.find((app) => app.id === input.appId);
    if (!app) throw new Error("应用配置不可用");
    const fingerprint = JSON.stringify([
      input.appId,
      app.workerGroupId,
      input.name,
      input.recipientCountry,
      input.senderCountry ?? null,
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
        appId: input.appId,
        createdAtMillis: Math.max(Date.now(), epoch) + this.sequence,
        workerGroupId: app.workerGroupId,
        managed: false,
        state: "pre_review",
        inputVersion: "3",
        recipientCountry: input.recipientCountry,
        senderCountry: input.senderCountry,
        sendTotal: 0,
        sentCount: 0,
        readCount: 0,
        repliedCount: 0,
        failedCount: 0,
        deliveredCount: 0,
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
    if (review && record.task.inputVersion !== "3")
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
  private counts(rows: MessageTaskResult[]) {
    const successful = rows.filter(
      (row) => row.resultStatus === "succeeded" && !row.contentError
    );
    return {
      sentCount: successful.filter((row) =>
        ["SENT", "DELIVERED", "READ", "REPLIED"].includes(row.status ?? "")
      ).length,
      deliveredCount: successful.filter((row) =>
        ["DELIVERED", "READ", "REPLIED"].includes(row.status ?? "")
      ).length,
      readCount: successful.filter((row) =>
        ["READ", "REPLIED"].includes(row.status ?? "")
      ).length,
      repliedCount: successful.filter((row) => row.status === "REPLIED").length,
      failedCount: rows.filter((row) => row.resultStatus === "failed").length
    };
  }
  async demonstrate(taskId: string, action: MessageDemoAction) {
    if (action === "fail-read") {
      this.failedReads.add(taskId);
      return;
    }
    const record = this.requireTask(taskId, false);
    if (record.task.state === "pre_review") throw new Error("请先核对并启动任务");
    if (action === "advance" || action === "complete") {
      if (record.task.state === "terminal") throw new Error("调度已结束，不能继续发送");
      const produced = new Set(record.results.map((row) => row.recipientId));
      const numbers = [...(this.recipients.get(taskId) ?? [])].filter(
        (number) => !produced.has(number)
      );
      const selected = action === "complete" ? numbers : numbers.slice(0, 50);
      for (const recipient of selected)
        record.results.push({
          messageId: `${taskId}/${recipient}`,
          recipientId: recipient,
          resultStatus: "succeeded",
          status: "SENT",
          workerId: `${record.task.workerGroupId}-sender`,
          phone: "+861700000001",
          observedAtMillis: Date.now()
        });
      if (record.results.length >= (record.task.sendTotal ?? 0))
        record.task.state = "terminal";
    } else {
      const stages = ["SENT", "DELIVERED", "READ", "REPLIED"];
      const next =
        action === "delivered" ? "DELIVERED" : action === "read" ? "READ" : "REPLIED";
      if (action === "reply") this.replies++;
      for (const row of record.results) {
        if (
          row.resultStatus !== "succeeded" ||
          row.contentError ||
          !stages.includes(row.status ?? "")
        )
          continue;
        if (stages.indexOf(row.status!) > stages.indexOf(next)) continue;
        row.status = next;
        row.observedAtMillis = Math.max(Date.now(), (row.observedAtMillis ?? 0) + 1);
        if (action === "reply") row.reply = `演示回复 ${this.replies}：消息已收到。`;
      }
    }
    Object.assign(record.task, this.counts(record.results));
  }
}
