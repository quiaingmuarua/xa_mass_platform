import { type TaskCaller } from "@/task-caller";

export const CALLER: TaskCaller = Object.freeze({ userId: "1000", role: "admin" });
import { Blob as NodeBlob } from "node:buffer";
import type { MockAppCheckTaskSource } from "../src/app-checks/mock-task-source";
import type { CreateCheckTask } from "../src/app-checks/model";
import type { ImportSnapshot } from "../src/app-checks/import-model";

export async function importedTask(
  source: MockAppCheckTaskSource,
  input: CreateCheckTask & {
    seeds: string[];
    sourceFile?: string;
    importSnapshot?: ImportSnapshot;
  }
) {
  const { requestId, checkId, country } = input;
  const created = await source.createTask(CALLER, {
    requestId,
    checkId,
    country
  });
  await source.importSeeds(
    CALLER,
    created.taskId,
    new NodeBlob([input.seeds.join("\n")]) as unknown as Blob,
    input.importSnapshot
  );
  return created;
}
