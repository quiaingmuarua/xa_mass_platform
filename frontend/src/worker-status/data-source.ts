import type { RuntimeViewerConfig } from "@/runtime-viewer/types";

import { ApiWorkerStatusDataSource } from "./api-data-source";
import { MockWorkerStatusDataSource } from "./mock-data-source";
import type { WorkerStatusDataSource } from "./types";

export function createWorkerStatusDataSource(
  config: RuntimeViewerConfig
): WorkerStatusDataSource {
  return config.mode === "mock"
    ? new MockWorkerStatusDataSource()
    : new ApiWorkerStatusDataSource(config.apiBaseUrl);
}
