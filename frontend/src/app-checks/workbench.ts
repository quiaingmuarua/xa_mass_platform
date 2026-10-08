import { reactive } from "vue";
import type { CheckTask, CheckResult, Country } from "./model";
import type { ImportReport } from "./import-model";

export type ExportFilter = "all" | "registered" | "unregistered";
export type ResultFilter = ExportFilter | "failed" | "invalid" | "unknown";
export type TaskAction = "preview" | "approve" | "close" | "export";
export interface PreviewState {
  endReason?: "completed" | "cancelled" | "stopped";
}
export interface TaskActivity {
  at: number;
  label: string;
  detail?: string;
}
export interface CheckDraft {
  createdAtMillis: number;
  appId: string;
  country: Country;
  inputMode: "file" | "paste";
  text: string;
  fileName: string;
  format: "txt" | "csv";
  header: boolean;
  column: number;
  report?: ImportReport;
  requestId: string;
  uncertain: boolean;
  knownTaskId?: string;
  busy: boolean;
  error: string;
}
export interface UnconfirmedSubmission {
  requestId: string;
  knownTaskId?: string;
  appId: string;
  country: Country;
  expectedCount: number;
  sourceFile: string;
  submittedAt: number;
  endedAt: number;
}
export const requestIdentity = () =>
  globalThis.crypto?.randomUUID?.() ??
  "app-check-" + Date.now() + "-" + Math.random().toString(16).slice(2);
export function freshDraft(): CheckDraft {
  return {
    createdAtMillis: Date.now(),
    appId: "",
    country: "CN",
    inputMode: "file",
    text: "",
    fileName: "",
    format: "txt",
    header: true,
    column: 0,
    requestId: requestIdentity(),
    uncertain: false,
    busy: false,
    error: ""
  };
}
export function createWorkbenchSession() {
  return reactive({
    draft: freshDraft(),
    unconfirmedSubmissions: [] as UnconfirmedSubmission[],
    filters: {
      query: "",
      app: "all",
      country: "all",
      state: "all",
      from: "",
      to: ""
    },
    filtersExpanded: false,
    listScroll: 0,
    listTableScrollTop: 0,
    listTableScrollLeft: 0
  });
}
export type WorkbenchSession = ReturnType<typeof createWorkbenchSession>;
export function taskStage(task: CheckTask) {
  if (task.state === "terminal") return "terminal";
  if (task.state === "pre_review") return "pre_review";
  if (task.state === "running_visible" || task.state === "running-initial")
    return "running";
  return "unknown";
}
export function taskLabel(task: CheckTask, preview?: PreviewState) {
  if (task.state === "terminal" && preview?.endReason)
    return { completed: "已完成", cancelled: "已取消", stopped: "已中止" }[
      preview.endReason
    ];
  return {
    pre_review: "待审核",
    running: "处理中",
    terminal: "已结束",
    unknown: "状态不可用"
  }[taskStage(task)];
}
export function resultKind(row: CheckResult): ResultFilter {
  if (row.resultStatus === "failed") return "failed";
  if (row.contentError) return "invalid";
  return row.registered === true
    ? "registered"
    : row.registered === false
      ? "unregistered"
      : "unknown";
}
export const resultLabels: Record<ResultFilter, string> = {
  all: "全部结果",
  registered: "已注册",
  unregistered: "未注册",
  failed: "查询失败",
  invalid: "内容异常",
  unknown: "尚无注册答案"
};
export function filterResults(
  rows: CheckResult[],
  query: string,
  filter: ResultFilter
) {
  return rows.filter(
    (row) =>
      (!query.trim() || row.number?.includes(query.trim())) &&
      (filter === "all" || resultKind(row) === filter)
  );
}
export const quantity = (value?: number) =>
  value === undefined ? "—" : value.toLocaleString("zh-CN");
export const timestamp = (value: number) =>
  new Date(value).toLocaleString("zh-CN", { hour12: false });
export function downloadBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob),
    link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
