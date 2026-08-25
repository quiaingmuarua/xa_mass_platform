export type JsonValue =
  | null
  | boolean
  | number
  | string
  | JsonValue[]
  | { [key: string]: JsonValue };

export type RuntimeDataSourceMode = "api" | "mock";

export interface RuntimeViewerConfig {
  mode: RuntimeDataSourceMode;
  apiBaseUrl: string;
}

export interface RuntimeViewerConfigError {
  title: string;
  details: string[];
}

export type RuntimeViewerConfigResult =
  | {
      ok: true;
      value: RuntimeViewerConfig;
    }
  | {
      ok: false;
      error: RuntimeViewerConfigError;
    };

export interface WorkerGroupView {
  workerGroupId: string;
  attributes: Record<string, JsonValue>;
  eventCodes: string[];
}

export interface TaskView {
  taskId: string;
  workerGroupId: string;
  workerAllocationMechanism: "PRECOMPUTED_TASK_RULE" | "DIRECT_ITEM_RULE";
  idleDisposition: "CLOSE_WHEN_IDLE" | "PARK_WHEN_IDLE";
  allocationRule: Record<string, JsonValue> | null;
  config: Record<string, string>;
}

export type TaskScoreBand =
  | "pre_review"
  | "admission_visible"
  | "running_visible"
  | "terminal";

export interface TaskRuntimePreviewEntry {
  taskId: string;
  scoreBand: TaskScoreBand;
  task: TaskView | null;
  workerGroup: WorkerGroupView | null;
}

export interface TaskPreviewResponse {
  sampleLimit: number;
  generatedAt: string;
  entries: TaskRuntimePreviewEntry[];
}

export interface WorkerGroupPreviewResponse {
  sampleLimit: number;
  sampledCount: number;
  returnedCount: number;
  unreadableCount: number;
  generatedAt: string;
  workerGroups: WorkerGroupView[];
}

export interface WorkerView {
  workerId: string;
  workerGroupId: string;
  endpointManagerId: string;
  workerProperties: Record<string, JsonValue>;
  platformProperties: Record<string, JsonValue>;
}

export interface WorkerPreviewResponse {
  workerGroupId: string;
  sampleLimit: number;
  sampledCount: number;
  returnedCount: number;
  unreadableCount: number;
  generatedAt: string;
  workers: WorkerView[];
}

export interface RuntimeViewerDataSource {
  previewTasks(sampleLimit: number, signal?: AbortSignal): Promise<TaskPreviewResponse>;

  previewWorkerGroups(
    sampleLimit: number,
    signal?: AbortSignal
  ): Promise<WorkerGroupPreviewResponse>;

  previewWorkers(
    workerGroupId: string,
    sampleLimit: number,
    filter: null,
    signal?: AbortSignal
  ): Promise<WorkerPreviewResponse>;
}
