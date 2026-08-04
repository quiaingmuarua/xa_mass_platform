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
  workerGroupIds: string[];
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

export interface WorkerGroupBatchGetResponse {
  workerGroups: WorkerGroupView[];
  missingWorkerGroupIds: string[];
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
  loadWorkerGroups(
    workerGroupIds: string[],
    signal?: AbortSignal
  ): Promise<WorkerGroupBatchGetResponse>;

  previewWorkers(
    workerGroupId: string,
    sampleLimit: number,
    filter: null,
    signal?: AbortSignal
  ): Promise<WorkerPreviewResponse>;
}
