import type {
  MockFiniteTask,
  MockFiniteTaskPresentationStatus,
  MockFiniteTaskResultLine,
  MockFiniteTaskSeedItem,
  TaskManagementScheduler
} from "./types";

export const MAX_TASK_SEED_BYTES = 1024 * 1024;
export const MAX_TASK_SEED_LINES = 1000;
export const TASK_ADMISSION_DELAY_MILLIS = 700;
export const TASK_DISPATCH_DELAY_MILLIS = 1200;

export const browserTaskManagementScheduler: TaskManagementScheduler = {
  now: () => Date.now(),
  wait: (delayMillis) =>
    new Promise((resolve) => {
      window.setTimeout(resolve, delayMillis);
    })
};

export function presentationStatus(
  task: MockFiniteTask
): MockFiniteTaskPresentationStatus {
  if (task.lifecycleState === "TERMINAL") {
    return "closed";
  }
  if (task.lifecycleState === "RUNNING_VISIBLE") {
    return "dispatch-visible";
  }
  if (task.lifecycleState === "ADMISSION_VISIBLE") {
    return "waiting-admission";
  }
  return task.seedState === "READY" ? "awaiting-approval" : "awaiting-seeds";
}

export function presentationLabel(status: MockFiniteTaskPresentationStatus): string {
  return {
    "awaiting-seeds": "Awaiting Seeds",
    "awaiting-approval": "Awaiting Approval",
    "waiting-admission": "Waiting Admission",
    "dispatch-visible": "Dispatch Visible",
    closed: "Closed"
  }[status];
}

export function parseSeedLines(content: ArrayBuffer): string[] {
  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(content);
  } catch {
    throw new Error("The seed file must be valid UTF-8 text.");
  }

  if (text.length === 0) {
    return [];
  }
  const lines = text.split(/\r\n|\n|\r/);
  if (/\r\n$|\n$|\r$/.test(text)) {
    lines.pop();
  }
  if (lines.length > MAX_TASK_SEED_LINES) {
    throw new Error(`The seed file must not exceed ${MAX_TASK_SEED_LINES} lines.`);
  }
  if (lines.length === 0) {
    return [];
  }
  return lines;
}

export function buildSeedItems(
  lines: string[],
  payloadKey: string
): MockFiniteTaskSeedItem[] {
  return lines.map((rawLine, index) => ({
    lineNumber: index + 1,
    rawLine,
    payload: {
      [payloadKey]: rawLine
    }
  }));
}

export function buildMockResults(task: MockFiniteTask): MockFiniteTaskResultLine[] {
  const seed = task.seed;
  if (seed === undefined) {
    return [];
  }
  return seed.items.map((item) => ({
    workerGroupId: task.workerGroupId,
    messageId: `${task.taskId}-${String(item.lineNumber).padStart(4, "0")}`,
    eventCode: seed.eventCode,
    input: { ...item.payload },
    result: {
      valid: true,
      mock: true,
      lineNumber: item.lineNumber
    }
  }));
}

export function encodeMockJsonl(results: MockFiniteTaskResultLine[]): string {
  return results.length === 0
    ? ""
    : `${results.map((result) => JSON.stringify(result)).join("\n")}\n`;
}
