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
  taskType: "TASK_DRIVEN" | "ITEM_DRIVEN";
  allocationRule: Record<string, JsonValue> | null;
  config: Record<string, string>;
  emptyCloseAtMillis: number | null;
}

export interface ConfiguredRuntimeResourceEntry {
  workerGroupId: string;
  taskId: string;
  workerGroup: WorkerGroupView | null;
  task: TaskView | null;
}

export interface ConfiguredRuntimeResourcesResponse {
  entries: ConfiguredRuntimeResourceEntry[];
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
  loadConfiguredResources(
    signal?: AbortSignal
  ): Promise<ConfiguredRuntimeResourcesResponse>;

  previewWorkers(
    workerGroupId: string,
    sampleLimit: number,
    filter: null,
    signal?: AbortSignal
  ): Promise<WorkerPreviewResponse>;
}
