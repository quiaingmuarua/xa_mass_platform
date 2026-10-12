import { z } from "zod";
import { jsonValueSchema } from "@/runtime-viewer/schemas";
import { parseWorkerSelector } from "@/task-call-debug/model";
import type { ProjectGroup } from "./projects";
import type { WorkerQuery } from "@/task-call-debug/types";
import type { FiniteTaskSeedItem, TaskItemApiRequest } from "./types";
import { decodeUtf8, splitTextLines } from "@/files/text";

export const MAX_TASK_SEED_BYTES = 1024 * 1024;
export const MAX_TASK_SEED_LINES = 10_000;
export const TASK_APPEND_CHUNK_SIZE = 100;

export function parseSeedLines(content: ArrayBuffer): string[] {
  const lines = splitTextLines(decodeUtf8(content));
  if (lines.length > MAX_TASK_SEED_LINES) {
    throw new Error(`The input file must not exceed ${MAX_TASK_SEED_LINES} lines.`);
  }
  return lines;
}

export function buildSeedItems(
  lines: string[],
  payloadKey: string
): FiniteTaskSeedItem[] {
  return lines.map((line, index) => ({
    lineNumber: index + 1,
    payload: { [payloadKey]: line }
  }));
}

export function materializeTaskItems(
  taskId: string,
  eventCode: string,
  seeds: FiniteTaskSeedItem[],
  workerSelector: WorkerQuery
): TaskItemApiRequest[] {
  return seeds.map((seed) => ({
    messageId: `${taskId}-${String(seed.lineNumber).padStart(5, "0")}`,
    eventCode,
    workerSelector,
    payload: { ...seed.payload }
  }));
}

export function chunkTaskItems<T>(items: T[], size = TASK_APPEND_CHUNK_SIZE): T[][] {
  const chunks: T[][] = [];
  for (let index = 0; index < items.length; index += size) {
    chunks.push(items.slice(index, index + size));
  }
  return chunks;
}

const itemSchema = z
  .object({
    messageId: z.string().refine((s) => s.trim().length > 0),
    eventCode: z.string().refine((s) => s.trim().length > 0),
    payload: z.record(z.string(), jsonValueSchema),
    workerSelector: z.unknown(),
    priority: z.number().int().min(0).max(10).optional(),
    ttlMillis: z.number().int().positive().optional()
  })
  .strict();

export function parseJsonlItems(
  content: ArrayBuffer,
  group: ProjectGroup
): TaskItemApiRequest[] {
  const identities = new Set<string>();
  return parseSeedLines(content).map((line, index) => {
    try {
      const parsed = itemSchema.parse(JSON.parse(line));
      if (identities.has(parsed.messageId)) throw new Error("messageId 重复");
      identities.add(parsed.messageId);
      const workerSelector = parseWorkerSelector(JSON.stringify(parsed.workerSelector));
      requireItemCapability(group, parsed.eventCode, workerSelector.executorName);
      return { ...parsed, workerSelector };
    } catch (error) {
      const reason =
        error instanceof z.ZodError
          ? (error.issues[0]?.path.join(".") || "Item") +
            " 缺失、包含未支持字段或格式不正确"
          : error instanceof Error
            ? error.message
            : "JSONL 无效";
      throw new Error("第 " + (index + 1) + " 行无效：" + reason);
    }
  });
}
export function requireItemCapability(
  group: ProjectGroup,
  event: string,
  executor: string
): void {
  if (!group.workerGroup?.eventCodes.includes(event))
    throw new Error("事件未由当前 Group 声明");
  if (!group.matching.functions.includes(executor))
    throw new Error("查询函数未由当前 Group 启用");
}
export function defaultSupply(group: ProjectGroup) {
  const pool = ["any", "country"].find(
    (name) =>
      group.matching.pools.includes(name) &&
      group.matching.functions.includes("worker." + name)
  );
  return pool
    ? {
        refill: [{ poolName: pool, target: {}, count: 100 }],
        selector: { executorName: "worker." + pool, input: {} }
      }
    : { refill: [], selector: undefined };
}
