import { taskRetentionSchema } from "@/task-management/retention";
import { z } from "zod";
import type { TaskPolicySnapshot } from "./configuration";

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
  retention: taskRetentionSchema.nullable().optional(),
  taskId: z.string().min(1),
  userId: z.string().min(1).optional(),
  name: z.string().optional(),
  createdAtMillis: integer.nullable(),
  workerGroupId: z.string().nullable(),
  managed: z.boolean(),
  state: z
    .enum(["pre_review", "running-initial", "running_visible", "terminal"])
    .nullable(),
  checkId: z.string().optional(),
  country: z.enum(["CN", "US", "GB"]).optional(),
  salt: z.string().optional(),
  saltDate: z.string().optional(),
  checkType: z.string().optional(),
  checkLabel: z.string().optional(),
  checkTypeLabel: z.string().optional(),
  seedType: z.enum(["phonenumber", "text"]).optional(),
  contractParameters: z.unknown().optional(),
  eventCode: z.string().optional(),
  workerSelector: z.unknown().optional(),
  refill: z.unknown().optional(),
  configurationError: z.string().optional(),
  sourceFile: z.string().optional(),
  inputVersion: z.string().optional(),
  reviewedAt: integer.optional(),
  totalCount: integer.optional(),
  activeCount: integer.optional(),
  succeededCount: integer.optional(),
  failedCount: integer.optional()
});
export const resultSchema = z
  .strictObject({
    messageId: z.string().min(1),
    resultStatus: z.enum(["succeeded", "failed"]),
    seed: z.string().optional(),
    matched: z.boolean().optional(),
    details: z.record(z.string(), z.unknown()).optional(),
    contentError: z.string().optional()
  })
  .superRefine((row, context) => {
    const valid = row.resultStatus === "succeeded" && !row.contentError;
    if (
      valid
        ? typeof row.seed !== "string" ||
          row.seed.length === 0 ||
          typeof row.matched !== "boolean" ||
          !row.details
        : row.matched !== undefined || row.details !== undefined
    )
      context.addIssue({ code: "custom", message: "检查判定与执行结果不一致" });
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
export const checkTypeSchema = z.object({
  id: z.string().min(1),
  label: z.string().min(1),
  seedType: z.enum(["phonenumber", "text"]),
  description: z.string(),
  parameterExample: z.record(z.string(), z.unknown()),
  inputExample: z.record(z.string(), z.unknown()),
  resultExample: z.record(z.string(), z.unknown())
});
export type SeedType = "phonenumber" | "text";
export const executionSchema = z.strictObject({
  eventCode: z.string().min(1),
  refill: z.array(
    z.strictObject({
      poolName: z.string().min(1),
      target: z.unknown(),
      count: z.number().int().positive()
    })
  ),
  workerSelector: z.strictObject({
    executorName: z.string().min(1),
    input: z.unknown()
  })
});
export const catalogSchema = z.object({
  projectId: z.literal("app-checks"),
  name: z.string(),
  version: z.string(),
  checkTypes: z.array(checkTypeSchema).min(1),
  checks: z
    .array(
      z.object({
        checkId: z.string().min(1),
        label: z.string().min(1),
        workerGroupId: z.string().min(1),
        checkType: z.string().min(1),
        execution: executionSchema
      })
    )
    .min(1),
  countries: z.array(z.enum(["CN", "US", "GB"])).min(1),
  limits: z.object({
    seedsPerImport: integer.positive().max(100000),
    importFileBytes: integer.positive()
  })
});
// Local Mock snapshots are not part of the API schema; API parsing strips them.
export type Catalog = z.infer<typeof catalogSchema>;
export type CheckTask = z.infer<typeof taskSchema> & {
  mockPolicy?: TaskPolicySnapshot;
};
export type CheckResult = z.infer<typeof resultSchema>;
export type CheckDetail = Omit<z.infer<typeof detailSchema>, "task"> & {
  task: CheckTask;
};
export interface CreateCheckTask {
  requestId: string;
  checkId: string;
  country?: Country;
}
export const checkLabel = (app?: string, catalog?: Catalog) =>
  catalog?.checks.find((candidate) => candidate.checkId === app)?.label ?? app ?? "—";
export const checkTypeLabel = (type?: string, catalog?: Catalog) =>
  catalog?.checkTypes.find((item) => item.id === type)?.label ??
  {
    "simulated-registration-v1": "模拟注册",
    "registration-v1": "注册检查",
    "original-carrier-v1": "原始运营商识别",
    "text-md5-proof-v1": "文本机制证明"
  }[type ?? ""] ??
  type ??
  "—";
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
export const stateLabel = (state: CheckTask["state"]) =>
  state === null ? "状态不可用" : taskStateLabels[state];
