import { HttpRuntimeViewerDataSource } from "./http-data-source";
import type { TaskCaller } from "@/task-caller";
import { MockRuntimeViewerDataSource } from "./mock-data-source";
import type { RuntimeViewerConfig, RuntimeViewerDataSource } from "./types";

export function createRuntimeViewerDataSource(
  config: RuntimeViewerConfig,
  caller: TaskCaller
): RuntimeViewerDataSource {
  if (config.mode === "mock") {
    return new MockRuntimeViewerDataSource(caller);
  }
  return new HttpRuntimeViewerDataSource(config.apiBaseUrl, caller);
}
