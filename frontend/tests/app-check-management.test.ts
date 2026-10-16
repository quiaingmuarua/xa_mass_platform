import { MockUserDirectorySource } from "@/user-directory-source";
import { CALLER } from "./app-check-fixture";
import { importedTask } from "./app-check-fixture";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MockAppCheckTaskSource } from "../src/app-checks/mock-task-source";
import { inspectImport, importSnapshot } from "../src/app-checks/import-model";
import { ApiAppCheckTaskSource } from "../src/app-checks/task-source";

const input = {
  requestId: "workflow",
  checkId: "app-a-registration",
  country: "CN" as const,
  seeds: Array.from({ length: 1001 }, (_, i) => "+86138" + String(i).padStart(8, "0"))
};
afterEach(() => vi.unstubAllGlobals());
describe("App Checks management prototype", () => {
  it("rejects malformed UTF-8 before adding any seed in Mock", async () => {
    const source = new MockAppCheckTaskSource(new MockUserDirectorySource());
    const { taskId } = await source.createTask(CALLER, {
      requestId: "invalid-utf8",
      checkId: "app-a-registration",
      country: "CN"
    });
    await expect(
      source.importSeeds(
        CALLER,
        taskId,
        new Blob([new Uint8Array([0x38, 0x36, 0x31, 0x33, 0x0a, 0xc3, 0x28])])
      )
    ).rejects.toThrow("UTF-8");
    expect((await source.loadTask(CALLER, taskId)).task.totalCount).toBe(0);
  });
  it("stops while retaining results and accepts only previously started work afterward", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const source = new MockAppCheckTaskSource(new MockUserDirectorySource()),
      { taskId } = await importedTask(source, input);
    await expect(source.advanceTask(CALLER, taskId)).rejects.toThrow();
    await source.approveTask(CALLER, taskId, input.seeds.length);
    await source.approveTask(CALLER, taskId, 1); // Already-approved requests are unchanged, even with a stale positive count.
    await source.advanceTask(CALLER, taskId);
    const partial = await source.loadTask(CALLER, taskId);
    expect(partial.task.activeCount).toBe(900);
    await expect(source.advanceTask(CALLER, taskId, true)).rejects.toThrow();
    await source.closeTask(CALLER, taskId);
    const stopped = await source.loadTask(CALLER, taskId);
    expect(stopped.task).toMatchObject({
      state: "terminal",
      activeCount: 900,
      succeededCount: 101
    });
    expect(source.previewState(CALLER, taskId).endReason).toBe("stopped");
    await expect(source.advanceTask(CALLER, taskId)).rejects.toThrow();
    await expect(source.completeTask(CALLER, taskId)).rejects.toThrow();
    await expect(source.closeTask(CALLER, taskId)).resolves.toBeUndefined();
    await expect(
      source.approveTask(CALLER, taskId, input.seeds.length)
    ).rejects.toThrow();
    await source.advanceTask(CALLER, taskId, true);
    expect(source.previewState(CALLER, taskId).endReason).toBe("stopped");
    expect((await source.loadTask(CALLER, taskId)).task.activeCount).toBe(899);
    expect((await source.exportTask(CALLER, taskId, "all")).count).toBe(102);
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("cancels pending tasks without claiming that any number completed", async () => {
    const source = new MockAppCheckTaskSource(new MockUserDirectorySource()),
      { taskId } = await importedTask(source, input);
    await source.closeTask(CALLER, taskId);
    expect(source.previewState(CALLER, taskId).endReason).toBe("cancelled");
    expect((await source.loadTask(CALLER, taskId)).task).toMatchObject({
      activeCount: 1001,
      succeededCount: 0,
      failedCount: 0
    });
    expect((await source.exportTask(CALLER, taskId, "all")).count).toBe(0);
    await expect(source.advanceTask(CALLER, taskId, true)).rejects.toThrow();
    await expect(source.closeTask(CALLER, taskId)).resolves.toBeUndefined();
  });
  it("bounds preview snapshots and independently exports complete valid successful answers", async () => {
    const source = new MockAppCheckTaskSource(new MockUserDirectorySource()),
      { taskId } = await importedTask(source, input);
    await expect(source.exportTask(CALLER, taskId, "all")).rejects.toThrow(
      "任务结束后"
    );
    await source.approveTask(CALLER, taskId, input.seeds.length);
    await expect(source.exportTask(CALLER, taskId, "all")).rejects.toThrow(
      "任务结束后"
    );
    await source.completeTask(CALLER, taskId);
    const snapshot = await source.loadTask(CALLER, taskId);
    expect(snapshot.results).toHaveLength(100);
    expect(snapshot.resultsTruncated).toBe(true);
    expect(snapshot.results.some((row) => row.seed === input.seeds[1000])).toBe(false);
    expect(snapshot.task).toMatchObject({
      totalCount: 1001,
      succeededCount: 901,
      failedCount: 100
    });
    const counts = await source.exportCounts(CALLER, taskId);
    expect(counts.all).toBe(901);
    expect(counts.matched + counts.unmatched).toBe(counts.all);
    expect((await source.loadActivity(CALLER, taskId)).map((row) => row.label)).toEqual(
      ["创建任务", "导入种子", "核对并启动", "查询结束"]
    );
    const exported = await source.exportTask(CALLER, taskId, "all");
    const csv = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = reject;
      reader.readAsText(exported.blob);
    });
    expect(exported.count).toBe(901);
    expect(csv.split("\r\n")).toHaveLength(902);
    expect(csv).toContain(input.seeds[1000]);
    expect(csv).not.toContain(input.seeds[900]);
    expect((await source.exportTask(CALLER, "check-mixed", "all")).count).toBe(2);
    expect((await source.exportTask(CALLER, taskId, "matched")).count).toBe(
      counts.matched
    );
    expect((await source.exportTask(CALLER, taskId, "unmatched")).count).toBe(
      counts.unmatched
    );
  });
  it("retains only import summaries and keeps all prototype fields out of API requests", async () => {
    const source = new MockAppCheckTaskSource(new MockUserDirectorySource());
    const report = inspectImport("8613800000001\n\n+8613800000001", {
      seedType: "phonenumber" as const,
      format: "txt",
      country: "CN",
      limit: 100000,
      header: false,
      column: 0
    });
    const created = {
      ...input,
      seeds: report.seeds,
      sourceFile: "source.txt",
      importSnapshot: importSnapshot(report, "source.txt")
    };
    const { taskId } = await importedTask(source, created);
    const saved = await source.loadImport(CALLER, taskId);
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
    expect((await source.loadImport(CALLER, taskId))?.summary.validCount).toBe(1);
    const missing = await source.createTask(CALLER, {
      ...input,

      requestId: "no-summary"
    });
    expect(await source.loadImport(CALLER, missing.taskId)).toBeUndefined();
    const fetcher = vi.fn().mockResolvedValue(new Response('{"taskId":"remote"}'));
    vi.stubGlobal("fetch", fetcher);
    await new ApiAppCheckTaskSource().createTask(CALLER, created);
    const body = JSON.parse(fetcher.mock.calls[0][1].body);
    expect(body).not.toHaveProperty("numbers");
    expect(body).not.toHaveProperty("sourceFile");
    expect(body).not.toHaveProperty("importSnapshot");
    expect(new ApiAppCheckTaskSource()).not.toHaveProperty("previewState");
  });
});
