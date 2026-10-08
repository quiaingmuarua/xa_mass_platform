import { z } from "zod";
import type { ImportSnapshot } from "./import-model";

const integer = z.number().int().nonnegative();
const bounds = (max: number) =>
  z.tuple([integer.max(max), integer.max(max)]).refine(([a, b]) => a <= b);
export const simulationSchema = z
  .strictObject({
    ranges: z.strictObject({
      registered: bounds(1000),
      unregistered: bounds(1000),
      failed: bounds(1000)
    }),
    delayMs: bounds(30000)
  })
  .superRefine((value, context) => {
    const nonempty = Object.values(value.ranges)
      .filter(([a, b]) => a < b)
      .sort((a, b) => a[0] - b[0]);
    let end = 0;
    for (const [a, b] of nonempty) {
      if (a !== end) {
        context.addIssue({
          code: "custom",
          message: "三个区间必须互斥并完整覆盖 0..1000"
        });
        return;
      }
      end = b;
    }
    if (end !== 1000)
      context.addIssue({ code: "custom", message: "三个区间必须完整覆盖 0..1000" });
  });
export type Simulation = z.infer<typeof simulationSchema>;
export type Country = "CN" | "US" | "GB";
export function parseSimulation(text: string): Simulation {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new Error("模拟描述不是合法 JSON");
  }
  if (JSON.stringify(value).length > 4096)
    throw new Error("模拟描述不能超过 4096 字符");
  const result = simulationSchema.safeParse(value);
  if (!result.success)
    throw new Error(
      "模拟描述无效：需要完整且互斥的三个整数区间及 0..30000ms 延迟，不能包含未知字段。"
    );
  return result.data;
}
export const rangeExamples: Record<string, Simulation["ranges"]> = {
  混合: { registered: [0, 500], unregistered: [500, 900], failed: [900, 1000] },
  全注册: { registered: [0, 1000], unregistered: [1000, 1000], failed: [1000, 1000] },
  全未注册: { registered: [0, 0], unregistered: [0, 1000], failed: [1000, 1000] },
  全失败: { registered: [0, 0], unregistered: [0, 0], failed: [0, 1000] }
};
export const taskStateLabels = {
  pre_review: "待审核",
  "running-initial": "准备中",
  running_visible: "查询中",
  terminal: "已结束"
};
export const taskSchema = z.object({
  taskId: z.string().min(1),
  name: z.string().optional(),
  createdAtMillis: integer,
  workerGroupId: z.string().nullable(),
  managed: z.boolean(),
  state: z
    .enum(["pre_review", "running-initial", "running_visible", "terminal"])
    .nullable(),
  appId: z.string().optional(),
  country: z.enum(["CN", "US", "GB"]).optional(),
  salt: z.string().optional(),
  saltDate: z.string().optional(),
  simulation: z.unknown().optional(),
  configurationError: z.string().optional(),
  sourceFile: z.string().optional(),
  reviewedAt: integer.optional(),
  totalCount: integer.optional(),
  activeCount: integer.optional(),
  succeededCount: integer.optional(),
  failedCount: integer.optional()
});
export const resultSchema = z.object({
  messageId: z.string().min(1),
  resultStatus: z.enum(["succeeded", "failed"]),
  number: z.string().optional(),
  registered: z.boolean().optional(),
  workerId: z.string().optional(),
  workerGroupId: z.string().optional(),
  simulatedDelayMillis: integer.optional(),
  contentError: z.string().optional()
});
export const detailSchema = z.object({
  task: taskSchema,
  results: z.array(resultSchema).max(100),
  resultsTruncated: z.boolean()
});
export const listSchema = z.object({
  tasks: z.array(taskSchema).max(100),
  truncated: z.boolean()
});
export const catalogSchema = z.object({
  projectId: z.literal("app-checks"),
  name: z.string(),
  version: z.string(),
  apps: z
    .array(z.object({ appId: z.string().min(1), workerGroupId: z.string().min(1) }))
    .min(1),
  countries: z.array(z.enum(["CN", "US", "GB"])).min(1),
  limits: z.object({
    tasks: integer.positive(),
    items: integer.positive(),
    numbersPerTask: integer.positive().max(100000)
  }),
  simulationExample: simulationSchema
});
export type Catalog = z.infer<typeof catalogSchema>;
export type CheckTask = z.infer<typeof taskSchema>;
export type CheckResult = z.infer<typeof resultSchema>;
export type CheckDetail = z.infer<typeof detailSchema>;
export interface CreateCheckTask {
  requestId: string;
  name: string;
  appId: string;
  country: Country;
  numbers: string[];
  simulation: Simulation;
  sourceFile?: string;
  importSnapshot?: ImportSnapshot;
}
export const appLabel = (app?: string) =>
  ({ "app-a": "App A", "app-b": "App B" })[app ?? ""] ?? app ?? "—";
export const countryLabel = (country?: string) =>
  ({ CN: "中国", US: "美国", GB: "英国" })[country ?? ""] ?? country ?? "—";
export function taskProgress(task: CheckTask): number {
  if (
    !task.totalCount ||
    task.succeededCount === undefined ||
    task.failedCount === undefined
  )
    return 0;
  return Math.min(
    100,
    Math.round(((task.succeededCount + task.failedCount) / task.totalCount) * 100)
  );
}
export function checkName(
  app: string,
  country: string,
  count: number,
  at: Date
): string {
  const pad = (v: number) => String(v).padStart(2, "0");
  const date = [at.getFullYear(), pad(at.getMonth() + 1), pad(at.getDate())].join("-");
  const time = [pad(at.getHours()), pad(at.getMinutes()), pad(at.getSeconds())].join(
    ":"
  );
  return (
    appLabel(app) +
    " · " +
    countryLabel(country) +
    " · " +
    count.toLocaleString("zh-CN") +
    " 个号码 · " +
    date +
    " " +
    time
  ).slice(0, 128);
}
export const stateLabel = (state: CheckTask["state"]) =>
  state === null ? "状态不可用" : taskStateLabels[state];
