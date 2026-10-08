import { importedTask } from "./app-check-fixture";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  MockAppCheckTaskSource,
  mockCatalog
} from "../src/app-checks/mock-task-source";
import { inspectImport, importSnapshot } from "../src/app-checks/import-model";
import { ApiAppCheckTaskSource } from "../src/app-checks/task-source";

const input = {
  requestId: "workflow",
  appId: "app-a",
  country: "CN" as const,
  numbers: Array.from(
    { length: 1001 },
    (_, i) => "+86138" + String(i).padStart(8, "0")
  ),
  simulation: mockCatalog.simulationExample
};
afterEach(() => vi.unstubAllGlobals());
describe("App Checks management prototype", () => {
  it("stops while retaining results and accepts only previously started work afterward", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const source = new MockAppCheckTaskSource(),
      { taskId } = await importedTask(source, input);
    await expect(source.advanceTask(taskId)).rejects.toThrow();
    await source.approveTask(taskId, input.numbers.length);
    await source.advanceTask(taskId);
    const partial = await source.loadTask(taskId);
    expect(partial.task.activeCount).toBe(900);
    await expect(source.advanceTask(taskId, true)).rejects.toThrow();
    await source.closeTask(taskId);
    const stopped = await source.loadTask(taskId);
    expect(stopped.task).toMatchObject({
      state: "terminal",
      activeCount: 900,
      succeededCount: 101
    });
    expect(source.previewState(taskId).endReason).toBe("stopped");
    await expect(source.advanceTask(taskId)).rejects.toThrow();
    await expect(source.completeTask(taskId)).rejects.toThrow();
    await expect(source.closeTask(taskId)).rejects.toThrow();
    await expect(source.approveTask(taskId, input.numbers.length)).rejects.toThrow();
    await source.advanceTask(taskId, true);
    expect(source.previewState(taskId).endReason).toBe("stopped");
    expect((await source.loadTask(taskId)).task.activeCount).toBe(899);
    expect((await source.exportTask(taskId, "all")).count).toBe(102);
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("cancels pending tasks without claiming that any number completed", async () => {
    const source = new MockAppCheckTaskSource(),
      { taskId } = await importedTask(source, input);
    await source.closeTask(taskId);
    expect(source.previewState(taskId).endReason).toBe("cancelled");
    expect((await source.loadTask(taskId)).task).toMatchObject({
      activeCount: 1001,
      succeededCount: 0,
      failedCount: 0
    });
    expect((await source.exportTask(taskId, "all")).count).toBe(0);
    await expect(source.advanceTask(taskId, true)).rejects.toThrow();
    await expect(source.closeTask(taskId)).rejects.toThrow();
  });
  it("bounds preview snapshots and independently exports complete valid successful answers", async () => {
    const source = new MockAppCheckTaskSource(),
      { taskId } = await importedTask(source, input);
    await expect(source.exportTask(taskId, "all")).rejects.toThrow("任务结束后");
    await source.approveTask(taskId, input.numbers.length);
    await expect(source.exportTask(taskId, "all")).rejects.toThrow("任务结束后");
    await source.completeTask(taskId);
    const snapshot = await source.loadTask(taskId);
    expect(snapshot.results).toHaveLength(100);
    expect(snapshot.resultsTruncated).toBe(true);
    expect(snapshot.results.some((row) => row.number === input.numbers[1000])).toBe(
      false
    );
    expect(snapshot.task).toMatchObject({
      totalCount: 1001,
      succeededCount: 997,
      failedCount: 4
    });
    const counts = await source.exportCounts(taskId);
    expect(counts.all).toBe(997);
    expect(counts.registered + counts.unregistered).toBe(counts.all);
    expect((await source.loadActivity(taskId)).map((row) => row.label)).toEqual([
      "创建任务",
      "导入号码",
      "核对并启动",
      "查询结束"
    ]);
    const exported = await source.exportTask(taskId, "all");
    const csv = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = reject;
      reader.readAsText(exported.blob);
    });
    expect(exported.count).toBe(997);
    expect(csv.split("\r\n")).toHaveLength(998);
    expect(csv).toContain(input.numbers[1000]);
    expect(csv).not.toContain(input.numbers[249]);
    expect((await source.exportTask("check-mixed", "all")).count).toBe(2);
    expect((await source.exportTask(taskId, "registered")).count).toBe(
      counts.registered
    );
    expect((await source.exportTask(taskId, "unregistered")).count).toBe(
      counts.unregistered
    );
  });
  it("retains only import summaries and keeps all prototype fields out of API requests", async () => {
    const source = new MockAppCheckTaskSource();
    const report = inspectImport("8613800000001\n\n+8613800000001", {
      format: "txt",
      country: "CN",
      limit: 100000,
      header: false,
      column: 0
    });
    const created = {
      ...input,
      numbers: report.numbers,
      sourceFile: "source.txt",
      importSnapshot: importSnapshot(report, "source.txt")
    };
    const { taskId } = await importedTask(source, created);
    const saved = await source.loadImport(taskId);
    expect(saved).toMatchObject({
      sourceFile: "source.txt",
      summary: {
        inputCount: 3,
        emptyCount: 1,
        validCount: 1,
        duplicateCount: 1,
        invalidCount: 0,
        blocked: false,
        overLimit: false
      }
    });
    expect(saved).not.toHaveProperty("rows");
    expect(saved).not.toHaveProperty("numbers");
    saved!.summary.validCount = 99;
    expect((await source.loadImport(taskId))?.summary.validCount).toBe(1);
    const missing = await source.createTask({
      ...input,
      requestId: "no-summary"
    });
    expect(await source.loadImport(missing.taskId)).toBeUndefined();
    const fetcher = vi.fn().mockResolvedValue(new Response('{"taskId":"remote"}'));
    vi.stubGlobal("fetch", fetcher);
    await new ApiAppCheckTaskSource().createTask(created);
    const body = JSON.parse(fetcher.mock.calls[0][1].body);
    expect(body).not.toHaveProperty("numbers");
    expect(body).not.toHaveProperty("sourceFile");
    expect(body).not.toHaveProperty("importSnapshot");
    expect(new ApiAppCheckTaskSource()).not.toHaveProperty("previewState");
  });
});
