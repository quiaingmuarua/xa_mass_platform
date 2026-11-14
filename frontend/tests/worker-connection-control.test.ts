import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, nextTick, type App } from "vue";
import { ElButton, ElDialog, ElInput } from "element-plus";
import WorkerConnectionControl from "@/components/WorkerConnectionControl.vue";
import {
  ApiWorkerConnectionControlSource,
  ConnectionControlUnknown,
  MockWorkerConnectionControlSource,
  type WorkerConnectionChange,
  type WorkerConnectionControlSource
} from "@/runtime-viewer/connection-control";
import {
  createWorkerConnectionControlSession,
  createWorkerConnectionControlState,
  workerConnectionControlKey
} from "@/runtime-viewer/connection-control-state";
import { MockUserDirectorySource } from "@/user-directory-source";
import { TaskApiError } from "@/task-caller";
import { ADMIN, OPERATOR_A } from "./identity-fixture";
import { developmentRoles } from "@/development-users";

const workers = [
  {
    workerGroupId: "group",
    workerId: "one",
    transportType: "WEBSOCKET",
    managed: true
  },
  {
    workerGroupId: "group",
    workerId: "two",
    transportType: "WEBSOCKET",
    managed: true
  },
  {
    workerGroupId: "group",
    workerId: "polling",
    transportType: "POLLING",
    managed: true
  },
  {
    workerGroupId: "group",
    workerId: "elsewhere",
    transportType: "WEBSOCKET",
    managed: false
  }
];
const mock = (now = Date.now) =>
  new MockWorkerConnectionControlSource(new MockUserDirectorySource(), now, workers);
const input = (expectedRevision: string | null = null): WorkerConnectionChange => ({
  requestId: crypto.randomUUID(),
  expectedRevision,
  cooldownUntilMillis: Date.now() + 86_400_000,
  reason: "fixture cooldown"
});
let app: App | undefined;
afterEach(() => {
  app?.unmount();
  app = undefined;
  vi.restoreAllMocks();
  document.body.replaceChildren();
});
async function flush() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
    await nextTick();
  }
}

describe("Worker connection control source", () => {
  it("Mock keeps revision after release, derives expiry and never calls a service", async () => {
    const fetcher = vi.spyOn(globalThis, "fetch");
    let now = Date.now();
    const source = mock(() => now);
    const first = input();
    const cooling = await source.save(ADMIN, "group", "one", first);
    expect(cooling.status).toBe("cooldown");
    expect(await source.save(ADMIN, "group", "one", first)).toEqual(cooling);
    now = first.cooldownUntilMillis! + 1;
    expect((await source.read(ADMIN, "group", "one")).status).toBe("allowed");
    expect((await source.save(ADMIN, "group", "one", first)).revision).toBe(
      cooling.revision
    );
    const released = await source.save(ADMIN, "group", "one", {
      ...input(cooling.revision),
      cooldownUntilMillis: null
    });
    expect(released.revision).not.toBe(cooling.revision);
    await expect(source.save(ADMIN, "group", "one", first)).rejects.toMatchObject({
      status: 409
    });
    await expect(
      source.save(OPERATOR_A, "group", "one", input())
    ).rejects.toMatchObject({ status: 403 });
    await expect(source.save(ADMIN, "group", "polling", input())).rejects.toMatchObject(
      { status: 400 }
    );
    expect((await source.read(ADMIN, "group", "elsewhere")).unsupportedReason).toBe(
      "adapter_not_running_here"
    );
    await expect(source.read(ADMIN, "wrong-group", "one")).rejects.toMatchObject({
      status: 400
    });
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("API binds target and captured key, and treats 503 or malformed success as unknown", async () => {
    const response = await mock().read(ADMIN, "group", "one");
    const fetcher = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(JSON.stringify(response)));
    const source = new ApiWorkerConnectionControlSource("/api");
    await source.read(ADMIN, "group", "one");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/worker-groups/group/workers/one/connection-control",
      expect.objectContaining({
        method: "GET",
        headers: { Authorization: "Bearer xak_dev_1000" }
      })
    );
    fetcher.mockResolvedValue(new Response("{}", { status: 503 }));
    await expect(source.save(ADMIN, "group", "one", input())).rejects.toBeInstanceOf(
      ConnectionControlUnknown
    );
    fetcher.mockResolvedValue(new Response(JSON.stringify(response)));
    await expect(source.save(ADMIN, "group", "one", input())).rejects.toBeInstanceOf(
      ConnectionControlUnknown
    );
    fetcher.mockResolvedValue(
      new Response(JSON.stringify({ ...response, workerId: "two" }))
    );
    await expect(source.read(ADMIN, "group", "one")).rejects.toThrow("身份不一致");
    fetcher.mockResolvedValue(new Response("{}", { status: 409 }));
    await expect(source.save(ADMIN, "group", "one", input())).rejects.toMatchObject({
      status: 409
    });
  });
});

describe("Worker connection control state", () => {
  it("a matching read can reconcile a concurrent conflict, while expired replay does not claim a new disconnect", async () => {
    let now = Date.now();
    const source = mock(() => now);
    const originalSave = source.save.bind(source);
    vi.spyOn(source, "save").mockImplementationOnce(async (...args) => {
      await originalSave(...args);
      throw new TaskApiError(409, "concurrent operation");
    });
    const state = createWorkerConnectionControlState(source, ADMIN, "group", "one");
    await state.refresh();
    await state.submit(now + 86_400_000, "original");
    expect(state.pendingStatus.value).toBe("conflict");
    await state.refresh();
    expect(state.canRetry.value).toBe(true);
    now += 86_400_001;
    await state.retry();
    expect(state.pending.value).toBeUndefined();
    expect(state.notice.value).toContain("已到期");
    expect(state.notice.value).not.toContain("路由撤销已确认");
  });
  it("requires the Server admin role even if a caller has runtime viewing permission", async () => {
    const source = mock();
    const read = vi.spyOn(source, "read");
    const save = vi.spyOn(source, "save");
    const viewer = {
      ...OPERATOR_A,
      access: {
        ...developmentRoles.find((role) => role.id === "admin")!,
        id: "operator" as const
      }
    };
    const state = createWorkerConnectionControlState(source, viewer, "group", "one");
    await state.refresh();
    await state.submit(null, "must not submit");
    expect(state.permitted).toBe(false);
    expect(read).not.toHaveBeenCalled();
    expect(save).not.toHaveBeenCalled();
  });
  it("retains the frozen request after response loss and separates stored policy from disconnection", async () => {
    const source = mock();
    const save = vi.spyOn(source, "save");
    const state = createWorkerConnectionControlState(source, ADMIN, "group", "one");
    await state.refresh();
    source.loseNextSaveResponse();
    await state.submit(Date.now() + 86_400_000, "original");
    const frozen = state.pending.value!;
    expect(Object.isFrozen(frozen)).toBe(true);
    expect(state.canWrite.value).toBe(false);
    expect(state.canRetry.value).toBe(false);
    await state.submit(null, "must not replace");
    expect(save).toHaveBeenCalledTimes(1);
    await state.refresh();
    expect(state.snapshot.value?.requestId).toBe(frozen.requestId);
    expect(state.pending.value).toBe(frozen);
    expect(state.notice.value).toContain("断连结果仍待核对");
    await state.retry();
    expect(save.mock.calls[1]?.[3]).toBe(frozen);
    expect(state.pending.value).toBeUndefined();
    expect(state.notice.value).toContain("路由撤销已确认");
  });

  it("failed reads preserve prior time, prevent edits and require a fresh state after conflict", async () => {
    const source = mock();
    const state = createWorkerConnectionControlState(source, ADMIN, "group", "one");
    await state.refresh();
    const original = state.snapshot.value;
    source.failNextRead();
    await state.refresh();
    expect(state.snapshot.value).toBe(original);
    expect(state.stale.value).toBe(true);
    expect(state.canWrite.value).toBe(false);
    await state.refresh();
    await source.save(ADMIN, "group", "one", input());
    await state.submit(Date.now() + 86_400_000, "stale edit");
    expect(state.error.value).toContain("修订冲突");
    const conflicted = state.pending.value;
    expect(conflicted?.reason).toBe("stale edit");
    expect(state.canWrite.value).toBe(false);
    await state.refresh();
    expect(state.pending.value).toBe(conflicted);
    expect(state.canRetry.value).toBe(false);
    expect(state.canWrite.value).toBe(false);
    state.discardPending();
    expect(state.canWrite.value).toBe(true);
  });

  it("a rejected retry cannot disprove an earlier unknown save", async () => {
    const source = mock();
    const state = createWorkerConnectionControlState(source, ADMIN, "group", "one");
    await state.refresh();
    source.loseNextSaveResponse();
    await state.submit(Date.now() + 86_400_000, "original");
    const pending = state.pending.value;
    await state.refresh();
    vi.spyOn(source, "save").mockRejectedValue(new TaskApiError(403, "role changed"));
    await state.retry();
    expect(state.pending.value).toBe(pending);
    expect(state.stale.value).toBe(true);
  });

  it("release reconciliation confirms only release and a newer revision fences an unknown old request", async () => {
    const source = mock();
    const state = createWorkerConnectionControlState(source, ADMIN, "group", "one");
    await state.refresh();
    source.loseNextSaveResponse();
    await state.submit(null, "release");
    await state.refresh();
    expect(state.pending.value).toBeUndefined();
    expect(state.notice.value).toContain("原解除请求已保存");
    source.loseNextSaveResponse();
    await state.submit(Date.now() + 86_400_000, "old cooldown");
    const original = state.pending.value;
    const current = await source.read(ADMIN, "group", "one");
    await source.save(ADMIN, "group", "one", {
      ...input(current.revision),
      cooldownUntilMillis: null
    });
    await state.refresh();
    expect(state.pending.value).toBe(original);
    expect(state.pendingStatus.value).toBe("superseded");
    expect(state.canRetry.value).toBe(false);
    expect(state.canWrite.value).toBe(false);
    expect(state.notice.value).toContain("是否曾生效仍未知");
    expect(state.snapshot.value?.status).toBe("allowed");
    state.discardPending();
    expect(state.pending.value).toBeUndefined();
    expect(state.canWrite.value).toBe(true);
  });

  it("separates per-Worker late reads and never follows a disposed login with new writes", async () => {
    const source = mock();
    let complete!: (value: Awaited<ReturnType<typeof source.read>>) => void;
    const first = await source.read(ADMIN, "group", "one");
    const delayed: WorkerConnectionControlSource = {
      ...source,
      read: (_caller, _group, worker) =>
        worker === "one"
          ? new Promise((resolve) => {
              complete = resolve;
            })
          : source.read(ADMIN, "group", "two"),
      save: vi.fn(source.save.bind(source))
    };
    const session = createWorkerConnectionControlSession(delayed, ADMIN);
    const a = session.state("group", "one"),
      b = session.state("group", "two");
    const pending = a.refresh();
    await b.refresh();
    complete(first);
    await pending;
    expect(b.snapshot.value?.workerId).toBe("two");
    expect(a.snapshot.value?.workerId).toBe("one");
    expect(session.state("group", "one")).toBe(a);
    session.dispose();
    await a.submit(null, "after logout");
    await a.retry();
    expect(delayed.save).not.toHaveBeenCalled();
  });

  it("late writes stay with the original Worker and dispose does not interpret their result", async () => {
    const source = mock();
    let complete!: () => void;
    const save = vi
      .spyOn(source, "save")
      .mockImplementation(async (caller, group, worker, change) => {
        await new Promise<void>((resolve) => {
          complete = resolve;
        });
        return {
          ...(await source.read(caller, group, worker)),
          requestId: change.requestId,
          reason: change.reason,
          cooldownUntilMillis: change.cooldownUntilMillis
        };
      });
    const session = createWorkerConnectionControlSession(source, ADMIN);
    const a = session.state("group", "one"),
      b = session.state("group", "two");
    await a.refresh();
    const writing = a.submit(null, "original");
    await b.refresh();
    session.dispose();
    complete();
    await writing;
    expect(save).toHaveBeenCalledTimes(1);
    expect(b.snapshot.value?.requestId).toBeNull();
    expect(a.notice.value).toBe("");
  });
});

it("panel confirms the destructive effect and retains unknown content across closing and reopening", async () => {
  const source = mock();
  const session = createWorkerConnectionControlSession(source, ADMIN);
  const mount = async () => {
    const host = document.createElement("div");
    document.body.append(host);
    app = createApp(WorkerConnectionControl, {
      workerGroupId: "group",
      workerId: "one"
    });
    app.provide(workerConnectionControlKey, session);
    for (const component of [ElButton, ElDialog, ElInput]) app.use(component);
    app.mount(host);
    await flush();
    return host;
  };
  const click = (text: string) =>
    [...document.querySelectorAll("button")]
      .find((button) => button.textContent?.trim() === text)!
      .click();
  const host = await mount();
  const reason = host.querySelector<HTMLInputElement>('[aria-label="冷却原因"]')!;
  reason.value = "unchanged text";
  reason.dispatchEvent(new Event("input", { bubbles: true }));
  await flush();
  click("设置／调整冷却");
  await flush();
  expect(document.body.textContent).toContain("已进入的 Handler 可能继续执行");
  source.loseNextSaveResponse();
  click("确认提交");
  await flush();
  expect(host.textContent).toContain("保留的原请求");
  expect(host.querySelector("fieldset")?.disabled).toBe(true);
  expect(host.textContent).toContain("unchanged text");
  const pending = session.state("group", "one").pending.value;
  app!.unmount();
  document.body.replaceChildren();
  const reopened = await mount();
  expect(session.state("group", "one").pending.value).toBe(pending);
  expect(reopened.textContent).toContain("断连结果仍待核对");
  click("显式重试原请求");
  await flush();
  expect(session.state("group", "one").pending.value).toBeUndefined();
  expect(reopened.textContent).toContain("实际网络状态需单独刷新");
});
