import { type TaskCaller } from "@/task-caller";

export const CALLER: TaskCaller = Object.freeze({ userId: "1000", role: "admin" });
import { Blob as NodeBlob } from "node:buffer";
import type { MockAppCheckTaskSource } from "../src/app-checks/mock-task-source";
import type { CreateCheckTask } from "../src/app-checks/model";
import type { ImportSnapshot } from "../src/app-checks/import-model";

export async function importedTask(
  source: MockAppCheckTaskSource,
  input: CreateCheckTask & {
    numbers: string[];
    sourceFile?: string;
    importSnapshot?: ImportSnapshot;
  }
) {
  const { requestId, appId, country, simulation } = input;
  const created = await source.createTask(CALLER, {
    requestId,
    appId,
    country,
    simulation
  });
  await source.importNumbers(
    CALLER,
    created.taskId,
    new NodeBlob([input.numbers.join("\n")]) as unknown as Blob,
    input.importSnapshot
  );
  return created;
}
