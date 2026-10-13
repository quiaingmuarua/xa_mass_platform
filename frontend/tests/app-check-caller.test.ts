import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiAppCheckTaskSource,
  AppCheckApiError,
  callerHeaders,
  type AppCheckCaller
} from "../src/app-checks/task-source";
import {
  MockAppCheckTaskSource,
  mockCatalog
} from "../src/app-checks/mock-task-source";

const a: AppCheckCaller = Object.freeze({ userId: "外部-A", role: "operator" });
const b: AppCheckCaller = Object.freeze({ userId: "external-B", role: "operator" });
const admin: AppCheckCaller = Object.freeze({ userId: "proof-admin", role: "admin" });
const input = {
  requestId: "caller-test",
  appId: "app-a",
  country: "CN" as const,
  simulation: mockCatalog.simulationExample
};
afterEach(() => vi.unstubAllGlobals());

describe("App Checks declared callers", () => {
  it("checks every direct Mock operation before inspecting inputs or results", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const source = new MockAppCheckTaskSource();
    const { taskId } = await source.createTask(a, input);
    const upload = {
      get size(): number {
        throw new Error("must not inspect upload");
      }
    } as Blob;
    const saved = await source.loadConfiguration(admin);
    for (const operation of [
      () => source.loadTask(b, taskId),
      () => source.importNumbers(b, taskId, upload),
      () => source.approveTask(b, taskId, 1),
      () => source.closeTask(b, taskId),
      () => source.exportTask(b, taskId, "all"),
      () => source.exportCounts(b, taskId),
      () => source.loadImport(b, taskId),
      () => source.loadActivity(b, taskId),
      () => source.loadConfiguration(a),
      () => source.saveConfiguration(a, 1, saved.saved.configuration),
      () => source.simulateConfigurationRestart(a),
      () => source.advanceTask(a, taskId),
      () => source.completeTask(a, taskId)
    ])
      await expect(operation()).rejects.toMatchObject({ status: 403 });
    expect(() => source.previewState(b, taskId)).toThrow(AppCheckApiError);
    expect(
      (await source.listTasks(b)).tasks.every((task) => task.userId === b.userId)
    ).toBe(true);
    expect((await source.loadTask(a, taskId)).task).toMatchObject({
      userId: a.userId,
      totalCount: 0,
      state: "pre_review"
    });
    await source.closeTask(admin, taskId);
    expect((await source.loadTask(a, taskId)).task.state).toBe("terminal");
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("preserves creator identity independently of role and rejects a body creator", async () => {
    const source = new MockAppCheckTaskSource();
    const original = await source.createTask(a, input);
    expect(await source.createTask({ ...a, role: "admin" }, input)).toEqual(original);
    await expect(source.createTask(b, input)).rejects.toThrow("requestId");
    await expect(
      source.createTask(a, { ...input, userId: b.userId } as typeof input)
    ).rejects.toMatchObject({ status: 400 });
  });
  it("sends Unicode caller headers without a creator in JSON and never retries 403", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ taskId: "created" }), { status: 201 })
      );
    vi.stubGlobal("fetch", fetcher);
    const source = new ApiAppCheckTaskSource();
    await expect(
      source.createTask(a, { ...input, userId: b.userId } as typeof input)
    ).rejects.toMatchObject({ status: 400 });
    expect(fetcher).not.toHaveBeenCalled();
    await source.createTask(a, input);
    const options = fetcher.mock.calls[0][1];
    expect(JSON.parse(options.body)).toEqual(input);
    expect(options.headers).toMatchObject(callerHeaders(a));
    expect(
      new TextDecoder().decode(
        Uint8Array.from(
          atob(options.headers["X-XA-Dev-User"].replace(/-/g, "+").replace(/_/g, "/")),
          (c) => c.charCodeAt(0)
        )
      )
    ).toBe(a.userId);
    fetcher
      .mockReset()
      .mockImplementation(() =>
        Promise.resolve(
          new Response(JSON.stringify({ message: "拒绝" }), { status: 403 })
        )
      );
    await expect(source.createTask(a, input)).rejects.toMatchObject({ status: 403 });
    await expect(
      source.importNumbers(a, "known", new Blob(["86123"]))
    ).rejects.toMatchObject({ status: 403 });
    await expect(source.exportTask(a, "known", "all")).rejects.toMatchObject({
      status: 403
    });
    expect(fetcher).toHaveBeenCalledTimes(3);
  });
});
