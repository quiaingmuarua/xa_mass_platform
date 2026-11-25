import { countFrom, type TaskRow, type TaskStats } from "@/task-management/projects";
import type { RefillTarget } from "@/runtime-viewer/types";

export type StatusTone = "info" | "primary" | "success" | "warning" | "danger";

export type FiniteStatusCategory =
  | "pre_review"
  | "running"
  | "succeeded"
  | "attention"
  | "unknown";

export type RpcStatusCategory = "running" | "pre_review" | "terminal" | "unknown";

export interface TaskStatsSummary {
  total: number;
  active: number;
  succeeded: number;
  failed: number;
  observedLater: number;
  other: number;
  completed: number;
  percent: number;
  succeededPercent: number;
  failedPercent: number;
}

export interface TaskStatusPresentation {
  label: string;
  tone: StatusTone;
  rawBand: string;
  archived: boolean;
  category: FiniteStatusCategory | RpcStatusCategory;
}

export interface TaskSourcePresentation {
  label: string;
  raw: boolean;
  owner: string | null;
  creator: string | null;
}

export interface ResultStatusPresentation {
  label: string;
  tone: StatusTone;
  raw: "succeeded" | "failed" | "not_observed";
}

export function summarizeTaskStats(stats?: TaskStats | null): TaskStatsSummary | null {
  if (!stats) return null;
  const total = stats.total;
  const active = countFrom(stats, 1);
  const failed = countFrom(stats, 5);
  const succeeded = countFrom(stats, 6, 9);
  const observedLater = countFrom(stats, 7, 9);
  const other = Math.max(0, total - active - failed - succeeded);
  const completed = Math.max(0, total - active);
  const percent = total > 0 ? Math.min(100, Math.round((completed / total) * 100)) : 0;
  const succeededPercent = total > 0 ? Math.min(100, (succeeded / total) * 100) : 0;
  const failedPercent =
    total > 0 ? Math.min(100 - succeededPercent, (failed / total) * 100) : 0;
  return {
    total,
    active,
    succeeded,
    failed,
    observedLater,
    other,
    completed,
    percent,
    succeededPercent,
    failedPercent
  };
}

export function isRunningBand(band: TaskRow["scoreBand"]): boolean {
  return band === "running-initial" || band === "running_visible";
}

export function presentFiniteStatus(row: TaskRow): TaskStatusPresentation {
  const band = row.scoreBand;
  const archived = row.retention?.phase === "RELEASED";
  const stats = summarizeTaskStats(row.stats);

  if (band === "pre_review") {
    return {
      label: "待审核",
      tone: "warning",
      rawBand: band,
      archived,
      category: "pre_review"
    };
  }
  if (band === "running-initial") {
    return {
      label: "初始化",
      tone: "primary",
      rawBand: band,
      archived,
      category: "running"
    };
  }
  if (band === "running_visible") {
    return {
      label: "运行中",
      tone: "primary",
      rawBand: band,
      archived,
      category: "running"
    };
  }
  if (band === "terminal") {
    if (!stats) {
      return {
        label: "已结束",
        tone: "info",
        rawBand: band,
        archived,
        category: "succeeded"
      };
    }
    if (stats.total === 0 || stats.active > 0) {
      return {
        label: "已终止",
        tone: "warning",
        rawBand: band,
        archived,
        category: "attention"
      };
    }
    if (stats.failed === 0 && stats.succeeded > 0) {
      return {
        label: "已完成",
        tone: "success",
        rawBand: band,
        archived,
        category: "succeeded"
      };
    }
    if (stats.succeeded === 0 && stats.failed > 0) {
      return {
        label: "已失败",
        tone: "danger",
        rawBand: band,
        archived,
        category: "attention"
      };
    }
    if (stats.failed > 0) {
      return {
        label: "部分失败",
        tone: "warning",
        rawBand: band,
        archived,
        category: "attention"
      };
    }
    return {
      label: "已结束",
      tone: "info",
      rawBand: band,
      archived,
      category: "succeeded"
    };
  }
  return {
    label: "状态不可用",
    tone: "info",
    rawBand: "unavailable",
    archived,
    category: "unknown"
  };
}

export function presentRpcStatus(row: TaskRow): TaskStatusPresentation {
  const band = row.scoreBand;
  if (band === "running_visible") {
    return {
      label: "服务中",
      tone: "success",
      rawBand: band,
      archived: false,
      category: "running"
    };
  }
  if (band === "running-initial") {
    return {
      label: "初始化",
      tone: "primary",
      rawBand: band,
      archived: false,
      category: "running"
    };
  }
  if (band === "pre_review") {
    return {
      label: "待启用",
      tone: "warning",
      rawBand: band,
      archived: false,
      category: "pre_review"
    };
  }
  if (band === "terminal") {
    return {
      label: "已停用",
      tone: "info",
      rawBand: band,
      archived: false,
      category: "terminal"
    };
  }
  return {
    label: "状态不可用",
    tone: "info",
    rawBand: "unavailable",
    archived: false,
    category: "unknown"
  };
}

export function presentTaskSource(row: TaskRow): TaskSourcePresentation {
  const metadata = row.task?.metadata;
  const owner = metadata?.["server.inputOwner"]?.trim() || null;
  const creator = metadata?.userId?.trim() || null;
  if (!row.task) {
    return { label: "描述符缺失", raw: false, owner: null, creator: null };
  }
  if (owner === "raw") {
    return { label: "Raw", raw: true, owner, creator };
  }
  if (owner) {
    return { label: `Scenario · ${owner}`, raw: false, owner, creator };
  }
  return { label: "未声明来源", raw: false, owner: null, creator };
}

export function presentResultStatus(
  status: "succeeded" | "failed" | "not_observed"
): ResultStatusPresentation {
  if (status === "succeeded") {
    return { label: "执行成功", tone: "success", raw: status };
  }
  if (status === "failed") {
    return { label: "执行失败", tone: "danger", raw: status };
  }
  return { label: "尚未观测", tone: "info", raw: status };
}

export function formatOutcomeItems(stats?: TaskStats | null): {
  text: string;
  tone: "normal" | "danger" | "muted";
}[] {
  const summary = summarizeTaskStats(stats);
  if (!summary) return [];
  if (summary.total === 0) return [{ text: "无执行项", tone: "muted" }];
  const items: { text: string; tone: "normal" | "danger" | "muted" }[] = [];
  if (summary.succeeded > 0)
    items.push({ text: `${summary.succeeded.toLocaleString()} 成功`, tone: "normal" });
  if (summary.failed > 0)
    items.push({ text: `${summary.failed.toLocaleString()} 失败`, tone: "danger" });
  if (summary.active > 0)
    items.push({ text: `${summary.active.toLocaleString()} 待执行`, tone: "muted" });
  if (summary.other > 0)
    items.push({ text: `${summary.other.toLocaleString()} 其他`, tone: "muted" });
  return items;
}

export function formatRpcWindowOutcomes(stats?: TaskStats | null): {
  text: string;
  tone: "normal" | "danger" | "muted";
}[] {
  const summary = summarizeTaskStats(stats);
  if (!summary) return [];
  const items: { text: string; tone: "normal" | "danger" | "muted" }[] = [];
  if (summary.succeeded > 0)
    items.push({ text: `${summary.succeeded.toLocaleString()} 成功`, tone: "normal" });
  if (summary.failed > 0)
    items.push({ text: `${summary.failed.toLocaleString()} 失败`, tone: "danger" });
  if (summary.other > 0)
    items.push({ text: `${summary.other.toLocaleString()} 其他`, tone: "muted" });
  return items;
}

export function rpcCallEndpointPath(taskId: string): string {
  return `/api/v1/tasks/${encodeURIComponent(taskId)}/items:call`;
}

export function formatDateTime(value?: string | number | null, empty = "—"): string {
  if (value == null) return empty;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return empty;
  return date.toLocaleString();
}

export function formatCompactTime(value?: string | number | null, empty = "—"): string {
  if (value == null) return empty;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return empty;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function formatDuration(
  startMillis?: number | null,
  endMillis?: number | null
): string | null {
  if (startMillis == null || endMillis == null) return null;
  const delta = endMillis - startMillis;
  if (!Number.isFinite(delta) || delta < 0) return null;
  if (delta < 1000) return "<1s";
  const totalSeconds = Math.floor(delta / 1000);
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (days > 0) return hours > 0 ? `${days}d ${hours}h` : `${days}d`;
  if (hours > 0) return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  if (minutes > 0) return seconds > 0 ? `${minutes}m ${seconds}s` : `${minutes}m`;
  return `${seconds}s`;
}

export function splitTaskMetadata(metadata?: Record<string, string> | null): {
  custom: [string, string][];
  system: [string, string][];
} {
  const custom: [string, string][] = [];
  const system: [string, string][] = [];
  if (!metadata) return { custom, system };
  for (const [key, value] of Object.entries(metadata).sort(([a], [b]) =>
    a.localeCompare(b)
  )) {
    if (key === "userId" || key.startsWith("server.")) system.push([key, value]);
    else custom.push([key, value]);
  }
  return { custom, system };
}

export function formatRefillList(refill?: RefillTarget[] | null): string[] {
  if (!refill?.length) return [];
  return refill.map((item) => `${item.poolName} × ${item.count}`);
}
