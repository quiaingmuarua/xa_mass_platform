import type { MockUserDirectorySource } from "@/user-directory-source";
import { HttpRuntimeViewerDataSource } from "./http-data-source";
import type { TaskCaller } from "@/task-caller";
import { MockRuntimeViewerDataSource } from "./mock-data-source";
import type { RuntimeViewerConfig, RuntimeViewerDataSource } from "./types";

export function createRuntimeViewerDataSource(
  config: RuntimeViewerConfig,
  caller: TaskCaller,
  directory?: MockUserDirectorySource
): RuntimeViewerDataSource {
  if (config.mode === "mock") {
    if (!directory) throw new Error("Mock user directory is required");
    return new MockRuntimeViewerDataSource(caller, directory);
  }
  return new HttpRuntimeViewerDataSource(config.apiBaseUrl, caller);
}
