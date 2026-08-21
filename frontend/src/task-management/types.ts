import type { JsonValue } from "@/runtime-viewer/types";

export type MockFiniteTaskLifecycleState =
  | "PRE_REVIEW"
  | "ADMISSION_VISIBLE"
  | "RUNNING_VISIBLE"
  | "TERMINAL";

export type MockFiniteTaskSeedState = "MISSING" | "READY";

export interface MockFiniteTaskConfig {
  priority: number;
  maximumCandidateWorkers: number;
  maxRetryTimes: number;
}

export interface MockFiniteTaskSeedItem {
  lineNumber: number;
  rawLine: string;
  payload: Record<string, string>;
}

export interface MockFiniteTaskSeed {
  originalFileName: string;
  byteCount: number;
  lineCount: number;
  eventCode: string;
  payloadKey: string;
  items: MockFiniteTaskSeedItem[];
}

export interface MockFiniteTaskResultLine {
  workerGroupId: string;
  messageId: string;
  eventCode: string;
  input: Record<string, string>;
  result: {
    valid: true;
    mock: true;
    lineNumber: number;
  };
}

export interface MockFiniteTask {
  taskId: string;
  workerGroupId: string;
  lifecycleState: MockFiniteTaskLifecycleState;
  seedState: MockFiniteTaskSeedState;
  allocationRule: Record<string, JsonValue>;
  config: MockFiniteTaskConfig;
  seed?: MockFiniteTaskSeed;
  results: MockFiniteTaskResultLine[];
  createdAt: string;
  updatedAt: string;
  approvedAt?: string;
  closedAt?: string;
  outputFile?: string;
}

export interface CreateMockFiniteTaskRequest {
  taskId: string;
  workerGroupId: string;
  config: MockFiniteTaskConfig;
}

export interface AttachMockFiniteTaskSeedRequest {
  taskId: string;
  eventCode: string;
  payloadKey: string;
  file: File;
}

export type MockFiniteTaskPresentationStatus =
  | "awaiting-seeds"
  | "awaiting-approval"
  | "waiting-admission"
  | "dispatch-visible"
  | "closed";

export interface TaskManagementScheduler {
  now(): number;
  wait(delayMillis: number): Promise<void>;
}

export interface TaskManagementCatalog {
  readonly entries: Array<{
    workerGroupId: string;
    workerGroup: {
      eventCodes: string[];
    } | null;
  }>;
}

export interface MockTaskDownload {
  fileName: string;
  blob: Blob;
}
