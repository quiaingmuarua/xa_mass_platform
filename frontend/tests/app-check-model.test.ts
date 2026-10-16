import { mockSimulation } from "../src/app-checks/mock-task-source";
import { MockUserDirectorySource } from "@/user-directory-source";
import { HttpFiniteTaskClient } from "@/task-management/http-client";
import { TaskApiError } from "@/task-caller";
import { CALLER } from "./app-check-fixture";
import { importedTask } from "./app-check-fixture";
import { webcrypto } from "node:crypto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { parseSimulation, rangeExamples } from "../src/app-checks/model";
import { hashValue, verifyPreview } from "../src/app-checks/verify";
import { MockAppCheckTaskSource } from "../src/app-checks/mock-task-source";
import {
  ApiAppCheckTaskSource,
  AppCheckCreationUnconfirmed
} from "../src/app-checks/task-source";
import { createAppCheckContext } from "../src/app-checks/context";
import { resultKind, filterResults } from "../src/app-checks/workbench";
import { resultSchema } from "../src/app-checks/model";

beforeEach(() => vi.stubGlobal("crypto", webcrypto));

it("classifies carrier negatives as valid answers and keeps execution/protocol errors out of CSV", async () => {
  const source = new MockAppCheckTaskSource(new MockUserDirectorySource());
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  const detail = await source.loadTask(CALLER, "check-carrier");
  expect(detail.results.map(resultKind)).toEqual([
    "matched",
    "unmatched",
    "unmatched",
    "error",
    "error"
  ]);
  expect(filterResults(detail.results, "", "unmatched")).toHaveLength(2);
  expect((await source.exportTask(CALLER, "check-carrier", "all")).count).toBe(3);
  expect((await source.exportTask(CALLER, "check-carrier", "unmatched")).count).toBe(2);
  expect(detail.task.succeededCount).toBe(4);
  expect(detail.results[0].details).not.toHaveProperty("workerId");
  expect(fetcher).not.toHaveBeenCalled();
});

it("rejects missing or contradictory business verdicts without a fourth result category", () => {
  expect(
    resultSchema.safeParse({ messageId: "m", resultStatus: "succeeded" }).success
  ).toBe(false);
  expect(
    resultSchema.safeParse({ messageId: "m", resultStatus: "failed", matched: false })
      .success
  ).toBe(false);
  expect(
    resultSchema.safeParse({
      messageId: "m",
      resultStatus: "succeeded",
      registered: true
    }).success
  ).toBe(false);
  expect(
    resultSchema.safeParse({
      messageId: "m",
      resultStatus: "succeeded",
      matched: false,
      seed: "+86123",
      details: { reason: "INVALID_PHONE_NUMBER" }
    }).success
  ).toBe(true);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
const description = (ranges = rangeExamples.混合, delayMs = [2000, 5000]) =>
  JSON.stringify({ ranges, delayMs });
const create = {
  requestId: "request",
  name: "display",
  checkId: "app-a-registration",
  country: "CN" as const,
  seeds: ["+8613800000001"]
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
    JSON.stringify({ ...mockSimulation, rate: 0.5 }),
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
    const snapshot = await new MockAppCheckTaskSource(
      new MockUserDirectorySource()
    ).loadTask(CALLER, "check-mixed");
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
    expect(snapshot.results[1].matched).toBe(false);
  });
  it("detects a success in the failure bucket, delay and Group mismatches", async () => {
    const snapshot = await new MockAppCheckTaskSource(
      new MockUserDirectorySource()
    ).loadTask(CALLER, "check-mixed");
    snapshot.results = [
      {
        ...snapshot.results[0],
        details: {
          workerId: "worker-b",
          workerGroupId: "other",
          simulatedDelayMillis: 0
        }
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
          await new MockAppCheckTaskSource(new MockUserDirectorySource()).loadTask(
            CALLER,
            "check-unregistered"
          )
        )
      ).get("r-1")?.status
    ).toBe("matched");
  });
  it("does not silently switch to remote hashing", async () => {
    vi.stubGlobal("crypto", { randomUUID: webcrypto.randomUUID });
    await expect(
      verifyPreview(
        await new MockAppCheckTaskSource(new MockUserDirectorySource()).loadTask(
          CALLER,
          "check-mixed"
        )
      )
    ).rejects.toThrow("Web Crypto");
  });
});

describe("App Checks data boundary", () => {
  it("connects file import, quantity approval, close and CSV without a separate count scan", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(new Response('{"taskId":"t"}'))
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            taskId: "t",
            inputCount: 1,
            emptyCount: 0,
            duplicateCount: 0,
            uniqueCount: 1,
            itemCount: 1,
            addedCount: 1,
            existingCount: 0
          })
        )
      )

      .mockResolvedValueOnce(
        new Response("号码,判定,检查项,地区\n'86123,满足,app-a,CN", {
          headers: { "X-Export-Count": "1" }
        })
      );
    vi.stubGlobal("fetch", fetcher);
    const source = new ApiAppCheckTaskSource();
    await source.createTask(CALLER, create);
    const file = new Blob(["86123"]);
    await source.importSeeds(CALLER, "t", file);
    const approve = vi
      .spyOn(HttpFiniteTaskClient.prototype, "approveTask")
      .mockResolvedValue();
    const close = vi
      .spyOn(HttpFiniteTaskClient.prototype, "closeTask")
      .mockResolvedValue();
    await source.approveTask(CALLER, "t", 1);
    await source.closeTask(CALLER, "t");
    expect(await source.exportTask(CALLER, "t", "matched")).toMatchObject({
      fileName: "t-matched.csv",
      count: 1
    });
    expect(fetcher.mock.calls.map((call) => call[0])).toEqual([
      "/api/v1/app-checks/tasks",
      "/api/v1/app-checks/tasks/t/seeds:import",
      "/api/v1/app-checks/tasks/t/results:export?filter=matched"
    ]);
    expect(fetcher.mock.calls[1][1]).toMatchObject({
      method: "POST",
      body: file,
      headers: { "Content-Type": "text/plain;charset=UTF-8" }
    });
    expect(approve).toHaveBeenCalledWith(CALLER, "t", 1);
    expect(close).toHaveBeenCalledWith(CALLER, "t");
  });
  it("keeps prototype review and complete CSV export inside Mock", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const source = new MockAppCheckTaskSource(new MockUserDirectorySource());
    const seeds = Array.from(
      { length: 1001 },
      (_, i) => `+86138${String(i).padStart(8, "0")}`
    );
    const { taskId } = await importedTask(source, { ...create, seeds });
    expect((await source.loadTask(CALLER, taskId)).task.state).toBe("pre_review");
    await expect(source.exportTask(CALLER, taskId, "all")).rejects.toThrow();
    await source.approveTask(CALLER, taskId, seeds.length);
    await source.completeTask(CALLER, taskId);
    expect((await source.loadTask(CALLER, taskId)).results).toHaveLength(100);
    const exported = await source.exportTask(CALLER, taskId, "all");
    expect(exported.count).toBe(901);
    expect(
      (await source.exportTask(CALLER, taskId, "matched")).count +
        (await source.exportTask(CALLER, taskId, "unmatched")).count
    ).toBe(exported.count);
    expect(fetcher).not.toHaveBeenCalled();
    expect(new ApiAppCheckTaskSource()).toHaveProperty("approveTask");
    expect(new ApiAppCheckTaskSource()).toHaveProperty("exportTask");
  });
  it("does not send prototype file metadata to the unchanged API", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('{"taskId":"task"}'));
    vi.stubGlobal("fetch", fetcher);
    await new ApiAppCheckTaskSource().createTask(CALLER, create);
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({
      requestId: create.requestId,
      checkId: create.checkId,
      country: create.country
    });
  });
  it("Mock catalog, creation, list and refresh are network-free, bounded and session-local", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const source = new MockAppCheckTaskSource(new MockUserDirectorySource());
    expect((await source.catalog()).projectId).toBe("app-checks");
    const { taskId } = await source.createTask(CALLER, create);
    expect(await source.createTask(CALLER, create)).toEqual({ taskId });
    await expect(
      source.createTask(CALLER, { ...create, country: "US" })
    ).rejects.toThrow();
    expect((await source.listTasks(CALLER)).tasks[0].taskId).toBe(taskId);
    expect((await source.loadTask(CALLER, taskId)).results).toEqual([]);
    expect((await source.loadTask(CALLER, "check-preview")).results).toHaveLength(100);
    expect((await source.loadTask(CALLER, "check-preview")).resultsTruncated).toBe(
      true
    );
    await expect(
      new MockAppCheckTaskSource(new MockUserDirectorySource()).loadTask(CALLER, taskId)
    ).rejects.toThrow();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("uses the existing API and checks requested identity", async () => {
    const detail = {
      taskId: "another",
      createdAtMillis: 1,
      scoreBand: "pre_review",
      retention: null,
      task: null
    };
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify(detail)));
    vi.stubGlobal("fetch", fetcher);
    await expect(
      new ApiAppCheckTaskSource().loadTask(CALLER, "wrong/id")
    ).rejects.toThrow("identity");
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/v1/projects/app-checks/tasks/wrong%2Fid"
    );
  });
  it.each([400, 409, 429])(
    "keeps an explicit %i rejection distinct from unknown submission",
    async (status) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(new Response('{"message":"rejected"}', { status }))
      );
      await expect(
        new ApiAppCheckTaskSource().createTask(CALLER, create)
      ).rejects.toBeInstanceOf(TaskApiError);
    }
  );
  it("preserves generated task ID on unknown writes and never retries", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        new Response('{"message":"unknown","taskId":"retained"}', { status: 503 })
      );
    vi.stubGlobal("fetch", fetcher);
    await expect(
      new ApiAppCheckTaskSource().createTask(CALLER, create)
    ).rejects.toMatchObject({
      taskId: "retained"
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
    fetcher.mockResolvedValue(new Response("broken", { status: 201 }));
    await expect(
      new ApiAppCheckTaskSource().createTask(CALLER, create)
    ).rejects.toBeInstanceOf(AppCheckCreationUnconfirmed);
    fetcher.mockRejectedValue(new TypeError("network"));
    await expect(
      new ApiAppCheckTaskSource().createTask(CALLER, create)
    ).rejects.toBeInstanceOf(AppCheckCreationUnconfirmed);
  });
  it("catalog 404 disables only this scene; transient failure allows explicit retry", async () => {
    const source = new MockAppCheckTaskSource(new MockUserDirectorySource());
    const read = vi
      .spyOn(source, "catalog")
      .mockRejectedValueOnce(new TaskApiError(404, "absent"));
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
