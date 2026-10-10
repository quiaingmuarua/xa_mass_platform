import { reactive, type InjectionKey } from "vue";
import type { Catalog } from "./api";
import type { RecipientInput } from "@/files/phone-numbers";
import type {
  CreateMessageTask,
  MessageApplication,
  MessageCountry,
  MessageImportReceipt,
  MessageTask,
  MessageTaskResult
} from "./task-source";

export const messageApplications: readonly MessageApplication[] = Object.freeze([
  { id: "demo", label: "Demo", workerGroupId: "demo-sim" },
  { id: "app-a", label: "App A", workerGroupId: "app-a-sim" },
  { id: "app-b", label: "App B", workerGroupId: "app-b-sim" }
]);
export function apiApplications(catalog?: Catalog): MessageApplication[] {
  return catalog?.applications ?? [];
}
export function applicationLabel(
  task: MessageTask,
  apps: readonly MessageApplication[]
) {
  return (
    apps.find(
      (app) => app.id === task.appId && app.workerGroupId === task.workerGroupId
    )?.label ??
    task.workerGroupId ??
    "不可用"
  );
}
export type MessageAction = "preview" | "import" | "approve" | "close";
export type MessageStage = "pre_review" | "running" | "terminal" | "unknown";
export function taskStage(task: MessageTask): MessageStage {
  return task.state === "running-initial" || task.state === "running_visible"
    ? "running"
    : (task.state ?? "unknown");
}
export function isBusiness(task: MessageTask) {
  return !task.managed && !!task.body && !!task.recipientCountry;
}
export function actionReason(
  task: MessageTask,
  action: MessageAction,
  blocked: boolean
) {
  if (action === "preview") return "";
  if (blocked) return "请先等待操作完成或刷新有效状态";
  if (!isBusiness(task) || !task.state) return "任务信息不可用";
  if (task.state === "terminal") return "调度已结束";
  if (action === "close") return "";
  if (task.state !== "pre_review") return "任务不在待审核状态";
  if (task.inputVersion !== "3") return "旧输入版本不支持导入或启动";
  if (!task.appId || task.senderPhone) return "任务发送配置不可用";
  return action === "approve" && !task.sendTotal ? "请先导入收件人" : "";
}
export const resultLabels = {
  all: "全部",
  SENT: "已发送待回执",
  DELIVERED: "已送达",
  READ: "已读",
  REPLIED: "已回复",
  EXECUTION_FAILED: "失败",
  invalid: "内容异常",
  unknown: "尚无业务结果"
};
export type ResultFilter = keyof typeof resultLabels;
export function resultKind(row: MessageTaskResult): ResultFilter {
  if (row.contentError) return "invalid";
  if (row.resultStatus === "failed") return "EXECUTION_FAILED";
  return row.status ?? "unknown";
}
export const quantity = (value?: number) =>
  value === undefined ? "—" : value.toLocaleString();
export const timestamp = (value?: number) =>
  value
    ? new Intl.DateTimeFormat("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false
      }).format(value)
    : "尚未读取";
export function describe(failure: unknown) {
  let text =
    failure instanceof Error ? failure.message : "操作失败，请刷新后核对任务。";
  if (
    failure &&
    typeof failure === "object" &&
    "confirmedAddedCount" in failure &&
    failure.confirmedAddedCount !== undefined
  ) {
    text += ` 已确认写入 ${failure.confirmedAddedCount}，已存在 ${"existingCount" in failure ? (failure.existingCount ?? "未知") : "未知"}；未确认部分请刷新实际数量核对。`;
  }
  return text;
}
export interface RecipientEditor {
  source: string | File;
  pasted: string;
  fileName: string;
  summary: RecipientInput | undefined;
  error: string;
}
export const recipientEditor = (): RecipientEditor => ({
  source: "",
  pasted: "",
  fileName: "",
  summary: undefined,
  error: ""
});
export function freshMessageDraft(appId = "") {
  return {
    appId,
    recipientCountry: "CN" as MessageCountry,
    senderCountry: "ANY" as MessageCountry | "ANY",
    body: "",
    createdAt: Date.now(),
    requestId: crypto.randomUUID(),
    editor: recipientEditor(),
    frozen: undefined as CreateMessageTask | undefined,
    knownTaskId: undefined as string | undefined,
    originalText: "",
    confirmed: false,
    uncertain: false,
    busy: false,
    error: "",
    note: "",
    inputKey: 0
  };
}
export type MessageDraft = ReturnType<typeof freshMessageDraft>;
export interface ImportSummary {
  fileName: string;
  receipt: MessageImportReceipt;
}
export function createMessageSession() {
  return reactive({
    draft: freshMessageDraft(),
    imports: {} as Record<string, ImportSummary>,
    filters: {
      query: "",
      app: "all",
      country: "all",
      state: "all",
      sender: "all",
      from: "",
      to: "",
      advanced: false
    },
    scrollTop: 0,
    scrollLeft: 0
  });
}
export type MessageSession = ReturnType<typeof createMessageSession>;
export const messageSessionKey: InjectionKey<MessageSession> = Symbol(
  "message-workbench-session"
);
