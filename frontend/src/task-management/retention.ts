import { z } from "zod";
import { TaskApiError } from "@/task-caller";

export const taskRetentionSchema = z
  .object({
    phase: z.enum(["OPEN", "FROZEN", "RELEASED", "PURGING"]),
    terminatedAtMillis: z.number().int().positive().nullable(),
    frozenAtMillis: z.number().int().positive().nullable(),
    expiresAtMillis: z.number().int().positive().nullable()
  })
  .strict();
export type TaskRetention = z.infer<typeof taskRetentionSchema>;

export function retentionLabel(info?: TaskRetention | null): string {
  if (!info) return "";
  if (info.phase === "RELEASED") return "结果已归档";
  if (info.phase === "FROZEN") return "结果已冻结";
  if (info.phase === "PURGING") return "正在清理";
  return info.terminatedAtMillis === null ? "" : "收尾中";
}

type MockRetainedTask = {
  state: string | null;
  managed: boolean;
  retention?: TaskRetention | null;
};

/** One controllable retention policy for all finite business Mock sources. */
export class MockTaskRetention {
  constructor(private readonly now: () => number = Date.now) {}

  observe(task: MockRetainedTask) {
    if (task.managed) {
      task.retention = null;
      return;
    }
    task.retention ??= {
      phase: "OPEN",
      terminatedAtMillis: null,
      frozenAtMillis: null,
      expiresAtMillis: null
    };
    if (task.state === "terminal" && task.retention.terminatedAtMillis === null) {
      task.retention.terminatedAtMillis = this.now();
      task.retention.expiresAtMillis = task.retention.terminatedAtMillis + 7 * 86400000;
    }
  }

  maintain(task: MockRetainedTask): "unchanged" | "archived" | "purged" {
    this.observe(task);
    const info = task.retention;
    if (!info || info.terminatedAtMillis === null) return "unchanged";
    if (this.now() >= info.expiresAtMillis!) {
      info.phase = "PURGING";
      return "purged";
    }
    if (info.phase === "RELEASED" || this.now() < info.terminatedAtMillis + 3600000)
      return "unchanged";
    info.phase = "RELEASED";
    info.frozenAtMillis ??= this.now();
    return "archived";
  }

  requireWritable(task: MockRetainedTask) {
    this.observe(task);
    if (task.retention && task.retention.phase !== "OPEN")
      throw new TaskApiError(400, "结果已冻结，不再接收后续更新。");
  }
}
