import { webcrypto } from "node:crypto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { checkName, parseSimulation, rangeExamples } from "../src/app-checks/model";
import { hashValue, verifyPreview } from "../src/app-checks/verify";
import {
  MockAppCheckTaskSource,
  mockCatalog
} from "../src/app-checks/mock-task-source";
import {
  ApiAppCheckTaskSource,
  AppCheckApiError,
  AppCheckCreationUnconfirmed
} from "../src/app-checks/task-source";
import { createAppCheckContext } from "../src/app-checks/context";

beforeEach(() => vi.stubGlobal("crypto", webcrypto));
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
const description = (ranges = rangeExamples.混合, delayMs = [2000, 5000]) =>
  JSON.stringify({ ranges, delayMs });
const create = {
  requestId: "request",
  name: "display",
  appId: "app-a",
  country: "CN" as const,
  numbers: ["+8613800000001"],
  simulation: mockCatalog.simulationExample
};

describe("App Checks admission and independent recomputation", () => {
  it.each(Object.keys(rangeExamples))(
    "accepts complete %s ranges including empty intervals",
    (name) => {
      expect(parseSimulation(description(rangeExamples[name], [0, 0])).delayMs).toEqual(
        [0, 0]
      );
    }
  );
  it.each([
    "null",
    "[]",
    "{",
    "{}",
    JSON.stringify({ ...mockCatalog.simulationExample, rate: 0.5 }),
    description({ ...rangeExamples.混合, registered: [0, 501] }),
    description({ ...rangeExamples.混合, registered: [0, 499] }),
    description({ ...rangeExamples.混合, registered: [0.5, 500] }),
    description(rangeExamples.混合, [-1, 0]),
    description(rangeExamples.混合, [10, 9]),
    description(rangeExamples.混合, [0, 30001]),
    description(rangeExamples.混合, [0]),
    JSON.stringify({
      ranges: { ...rangeExamples.混合, extra: [0, 0] },
      delayMs: [0, 0]
    }),
    JSON.stringify({
      ranges: rangeExamples.混合,
      delayMs: [0, 0],
      unknown: "x".repeat(4096)
    })
  ])("rejects illegal description %#", (input) =>
    expect(() => parseSimulation(input)).toThrow()
  );
  it("uses submission local time for display name, without minting task identity", () => {
    expect(checkName("app-a", "CN", 12, new Date(2026, 8, 18, 9, 4, 2))).toBe(
      "check-app-a-CN-12-20260918-090402"
    );
  });
  it("matches Java/Python vectors, unsigned high bits and UTF-8 byte lengths", async () => {
    expect(await hashValue("outcome", "worker-a", "fixed-salt", "+8613800000001")).toBe(
      3163400851481352025n
    );
    expect(await hashValue("delay", "worker-a", "fixed-salt", "+8613800000001")).toBe(
      11415857146142714812n
    );
    expect(await hashValue("outcome", "worker-b", "fixed-salt", "+8613800000001")).toBe(
      13967144759099847917n
    );
    expect(await hashValue("outcome", "执行者甲", "盐值🌍", "+8613800000001")).toBe(
      4551276407391534456n
    );
  });
  it("checks content independently, preserving failures and missing evidence", async () => {
    const snapshot = await new MockAppCheckTaskSource().loadTask("check-mixed");
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const results = await verifyPreview(snapshot);
    expect(results.get("r-1")).toMatchObject({
      status: "matched",
      bucket: 25,
      expectedDelay: 4067
    });
    expect(results.get("tampered")?.status).toBe("mismatch");
    expect(results.get("invalid")?.status).toBe("unavailable");
    expect(results.get("failed")?.status).toBe("unavailable");
    expect(fetcher).not.toHaveBeenCalled();
    expect(snapshot.results[1].registered).toBe(false);
  });
  it("detects a success in the failure bucket, delay and Group mismatches", async () => {
    const snapshot = await new MockAppCheckTaskSource().loadTask("check-mixed");
    snapshot.results = [
      {
        ...snapshot.results[0],
        workerId: "worker-b",
        workerGroupId: "other",
        simulatedDelayMillis: 0
      }
    ];
    expect((await verifyPreview(snapshot)).get("r-1")?.reason).toBe(
      "执行 Group 不符；成功内容命中失败区间；模拟延迟不符"
    );
    snapshot.task.salt = undefined;
    expect((await verifyPreview(snapshot)).get("r-1")?.status).toBe("unavailable");
  });
  it("accepts unregistered success and a fixed zero delay", async () => {
    expect(
      (
        await verifyPreview(
          await new MockAppCheckTaskSource().loadTask("check-unregistered")
        )
      ).get("r-1")?.status
    ).toBe("matched");
  });
  it("does not silently switch to remote hashing", async () => {
    vi.stubGlobal("crypto", { randomUUID: webcrypto.randomUUID });
    await expect(
      verifyPreview(await new MockAppCheckTaskSource().loadTask("check-mixed"))
    ).rejects.toThrow("Web Crypto");
  });
});

describe("App Checks data boundary", () => {
  it("Mock catalog, creation, list and refresh are network-free, bounded and session-local", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const source = new MockAppCheckTaskSource();
    expect((await source.catalog()).projectId).toBe("app-checks");
    const { taskId } = await source.createTask(create);
    expect(await source.createTask(create)).toEqual({ taskId });
    await expect(source.createTask({ ...create, country: "US" })).rejects.toThrow();
    expect((await source.listTasks()).tasks[0].taskId).toBe(taskId);
    expect((await source.loadTask(taskId)).results).toEqual([]);
    expect((await source.loadTask("check-preview")).results).toHaveLength(100);
    expect((await source.loadTask("check-preview")).resultsTruncated).toBe(true);
    await expect(new MockAppCheckTaskSource().loadTask(taskId)).rejects.toThrow();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("uses the existing API and checks requested identity", async () => {
    const detail = await new MockAppCheckTaskSource().loadTask("check-mixed");
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify(detail)));
    vi.stubGlobal("fetch", fetcher);
    await expect(new ApiAppCheckTaskSource().loadTask("wrong/id")).rejects.toThrow(
      "身份"
    );
    expect(fetcher.mock.calls[0][0]).toBe("/api/v1/app-checks/tasks/wrong%2Fid");
  });
  it.each([400, 409, 429])(
    "keeps an explicit %i rejection distinct from unknown submission",
    async (status) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(new Response('{"message":"rejected"}', { status }))
      );
      await expect(
        new ApiAppCheckTaskSource().createTask(create)
      ).rejects.toBeInstanceOf(AppCheckApiError);
    }
  );
  it("preserves generated task ID on unknown writes and never retries", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        new Response('{"message":"unknown","taskId":"retained"}', { status: 503 })
      );
    vi.stubGlobal("fetch", fetcher);
    await expect(new ApiAppCheckTaskSource().createTask(create)).rejects.toMatchObject({
      taskId: "retained"
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
    fetcher.mockResolvedValue(new Response("broken", { status: 201 }));
    await expect(new ApiAppCheckTaskSource().createTask(create)).rejects.toBeInstanceOf(
      AppCheckCreationUnconfirmed
    );
    fetcher.mockRejectedValue(new TypeError("network"));
    await expect(new ApiAppCheckTaskSource().createTask(create)).rejects.toBeInstanceOf(
      AppCheckCreationUnconfirmed
    );
  });
  it("catalog 404 disables only this scene; transient failure allows explicit retry", async () => {
    const source = new MockAppCheckTaskSource();
    const read = vi
      .spyOn(source, "catalog")
      .mockRejectedValueOnce(new AppCheckApiError(404, "absent"));
    const context = createAppCheckContext(source);
    await context.load();
    expect(context.state.value.status).toBe("disabled");
    read.mockRejectedValueOnce(new Error("network"));
    await context.load(true);
    expect(context.state.value.status).toBe("unavailable");
    await context.load(true);
    expect(context.state.value.status).toBe("enabled");
    context.dispose();
  });
});
