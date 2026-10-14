import { webcrypto } from "node:crypto";
import { MockUserDirectorySource } from "@/user-directory-source";
import { TaskApiError, callerHeaders, type TaskCaller } from "@/task-caller";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { ApiAppCheckTaskSource } from "../src/app-checks/task-source";
import { MockAppCheckTaskSource } from "../src/app-checks/mock-task-source";

const a: TaskCaller = Object.freeze({ userId: "testa", role: "operator" });
const b: TaskCaller = Object.freeze({ userId: "testb", role: "operator" });
const admin: TaskCaller = Object.freeze({ userId: "proofadm", role: "admin" });
function directory() {
  return new MockUserDirectorySource(
    [a, b, admin].map((caller) => ({
      ...caller,
      displayName: caller.userId,
      projectIds: caller.role === "operator" ? ["app-checks"] : []
    }))
  );
}
const input = {
  requestId: "caller-test",
  appId: "app-a",
  country: "CN" as const
};
beforeEach(() => vi.stubGlobal("crypto", webcrypto));
afterEach(() => vi.unstubAllGlobals());

describe("App Checks declared callers", () => {
  it("selects personal score ranges before limiting, including admin owner filters", async () => {
    const source = new MockAppCheckTaskSource(directory());
    for (let i = 0; i < 105; i++)
      await source.createTask(a, { ...input, requestId: `other-${i}` });
    const target = await source.createTask(b, {
      ...input,
      requestId: "personal-target"
    });
    expect(
      (await source.listTasks(admin)).tasks.some(
        (task) => task.taskId === target.taskId
      )
    ).toBe(false);
    expect((await source.listTasks(b)).tasks.map((task) => task.taskId)).toEqual([
      target.taskId
    ]);
    expect(await source.listTasks(admin, b.userId)).toEqual(await source.listTasks(b));
    const first = await source.listTasks(a);
    expect(first.tasks).toHaveLength(100);
    expect(first.truncated).toBe(true);
    await expect(source.listTasks(a, b.userId)).rejects.toMatchObject({ status: 403 });
  });
  it("checks every direct Mock operation before inspecting inputs or results", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const source = new MockAppCheckTaskSource(directory());
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
      () =>
        source.saveConfiguration(a, saved.saved.fingerprint, saved.saved.configuration),
      () => source.simulateConfigurationRestart(a),
      () => source.advanceTask(a, taskId),
      () => source.completeTask(a, taskId)
    ])
      await expect(operation()).rejects.toMatchObject({ status: 403 });
    expect(() => source.previewState(b, taskId)).toThrow(TaskApiError);
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
    const users = directory();
    const source = new MockAppCheckTaskSource(users);
    const original = await source.createTask(a, input);
    await users.save(
      admin,
      { ...a, role: "admin", displayName: "A", projectIds: [] },
      a.userId
    );
    expect(await source.createTask({ ...a, role: "admin" }, input)).toEqual(original);
    expect((await source.createTask(b, input)).taskId).not.toBe(original.taskId);
    await expect(
      source.createTask({ ...a, role: "admin" }, {
        ...input,
        userId: b.userId
      } as typeof input)
    ).rejects.toMatchObject({ status: 400 });
  });
  it("sends Base36 caller headers without a creator in JSON and never retries 403", async () => {
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
