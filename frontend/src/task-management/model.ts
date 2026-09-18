import type { WorkerQuery } from "@/task-call-debug/types";
import type { FiniteTaskSeedItem, FiniteTaskStage, TaskItemApiRequest } from "./types";
import { decodeUtf8, splitTextLines } from "@/files/text";

export const MAX_TASK_SEED_BYTES = 1024 * 1024;
export const MAX_TASK_SEED_LINES = 10_000;
export const TASK_APPEND_CHUNK_SIZE = 100;

export function stageLabel(stage: FiniteTaskStage): string {
  return {
    CREATED: "Created",
    ITEMS_APPENDED: "Items Appended",
    APPROVED: "Approved",
    EXPORT_READY: "Export Ready"
  }[stage];
}

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
