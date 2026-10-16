import { isCarrierContract, isMd5Contract } from "./settings";
import { type TaskCaller } from "@/task-caller";
import { reactive } from "vue";

import { consoleIdentity } from "@/console-identity";
import type {
  Catalog,
  CheckTask,
  CheckResult,
  Country,
  SeedType,
  CreateCheckTask
} from "./model";
import type { ConfigurationSession } from "./settings";
import type { ImportReport } from "./import-model";

export type ExportFilter = "all" | "matched" | "unmatched";
export type ResultFilter = ExportFilter | "error";
export type TaskAction = "preview" | "import" | "approve" | "close" | "export";
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
  checkId: string;
  seedType: SeedType;
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
  submissionPhase: "create" | "import";
  busy: boolean;
  error: string;
  frozenCreate?: CreateCheckTask;
  frozenCaller?: TaskCaller;
}
export interface UnconfirmedSubmission {
  userId: string;
  requestId: string;
  knownTaskId?: string;
  checkId: string;
  country?: Country;
  expectedCount: number;
  sourceFile: string;
  submittedAt: number;
  endedAt: number;
}
export const requestIdentity = () =>
  globalThis.crypto?.randomUUID?.() ??
  "app-check-" + Date.now() + "-" + Math.random().toString(16).slice(2);
export function freshDraft(catalog?: Catalog): CheckDraft {
  return {
    createdAtMillis: Date.now(),
    checkId:
      catalog?.checks.find((check) =>
        catalog.checkTypes.some(
          (type) => type.id === check.checkType && type.seedType === "phonenumber"
        )
      )?.checkId ?? "",
    seedType: "phonenumber",
    country: catalog?.countries[0] ?? "CN",
    inputMode: "file",
    text: "",
    fileName: "",
    format: "txt",
    header: true,
    column: 0,
    requestId: requestIdentity(),
    uncertain: false,
    submissionPhase: "create",
    busy: false,
    error: ""
  };
}
export function createWorkbenchSession(
  userId = consoleIdentity.requireCurrent().userId
) {
  const unconfirmedSubmissions: UnconfirmedSubmission[] = [];
  return reactive({
    userId,
    draft: freshDraft(),
    ownerQuery: "",
    ownerFilter: "",
    knownTaskQuery: "",
    unconfirmedSubmissions,
    import: { userId, draft: freshDraft(), unconfirmedSubmissions },
    importTask: undefined as CheckTask | undefined,
    filters: {
      query: "",
      check: "all",
      seedType: "all",
      country: "all",
      state: "all",
      from: "",
      to: ""
    },
    filtersExpanded: false,
    configuration: {
      readAt: 0,
      error: "",
      notice: "",
      uncertain: false,
      stale: false,
      saving: false
    } as ConfigurationSession,
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
  if (
    row.resultStatus === "failed" ||
    row.contentError ||
    typeof row.matched !== "boolean"
  )
    return "error";
  return row.matched ? "matched" : "unmatched";
}
export const resultLabels: Record<ResultFilter, string> = {
  all: "全部结果",
  matched: "满足",
  unmatched: "不满足",
  error: "检查错误"
};
export function checkCondition(checkType?: string) {
  if (checkType === "uint32-prime-v1")
    return "演示条件：整数是否为素数。输入 1～10 位十进制数字，范围 0～4,294,967,295；0、1 和合数不满足。";
  if (isMd5Contract(checkType)) return "演示条件：文本 UTF-8 MD5 摘要以 0 开头。";
  return isCarrierContract(checkType)
    ? "检查条件：是否识别到原始运营商。基于号码库元数据，不表示当前运营商或号码实际在用。"
    : checkType === "registration-v1" || checkType === "simulated-registration-v1"
      ? "检查条件：号码是否已注册。"
      : "检查条件由任务保存的业务契约定义。";
}
export function resultDescription(row: CheckResult) {
  if (row.resultStatus === "failed") return "执行失败 · 无有效检查结论";
  if (row.contentError) return "内容异常 · " + row.contentError;
  if (typeof row.details?.registered === "boolean")
    return row.details.registered ? "已注册" : "未注册";
  if (typeof row.details?.isPrime === "boolean")
    return row.details.isPrime ? "是素数" : "不是素数";
  if (typeof row.details?.md5 === "string") return "MD5：" + row.details.md5;
  const carrier = row.details?.originalCarrier;
  if (typeof carrier === "string") {
    if (row.matched) return "原始运营商：" + carrier;
    return row.details?.reason === "NO_CARRIER_MAPPING"
      ? "号码库没有运营商映射"
      : "号码库无法识别该号码：" + String(row.details?.reason ?? "未提供原因");
  }
  return typeof row.matched === "boolean"
    ? "执行成功 · 已取得有效判定"
    : "内容异常 · 缺少有效判定";
}
export function filterResults(
  rows: CheckResult[],
  query: string,
  filter: ResultFilter
) {
  return rows.filter(
    (row) =>
      (!query.trim() || row.seed?.includes(query.trim())) &&
      (filter === "all" || resultKind(row) === filter)
  );
}
export const quantity = (value?: number) =>
  value === undefined ? "—" : value.toLocaleString("zh-CN");
export const timestamp = (value: number | null) =>
  value === null
    ? "未提供"
    : new Date(value).toLocaleString("zh-CN", { hour12: false });
export function downloadBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob),
    link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
