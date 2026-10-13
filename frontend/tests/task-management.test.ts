import type { AxiosInstance } from "axios";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { HttpFiniteTaskClient } from "@/task-management/http-client";
import {
  buildSeedItems,
  chunkTaskItems,
  materializeTaskItems,
  MAX_TASK_SEED_LINES,
  parseSeedLines,
  parseJsonlItems,
  defaultSupply
} from "@/task-management/model";
import type {
  FiniteTaskClient,
  TaskCreateApiResponse,
  TaskExportResult,
  TaskItemApiRequest,
  TaskItemsAppendApiResponse
} from "@/task-management/types";
import { createTaskManagementStore } from "@/stores/task-management";
import { FiniteTaskError } from "@/task-management/errors";

describe("finite Task input model", () => {
  it("parses UTF-8, keeps empty lines, and creates stable five-digit IDs", () => {
    const lines = parseSeedLines(bytes("one\n\nthree\n"));
    const seeds = buildSeedItems(lines, "value");

    expect(lines).toEqual(["one", "", "three"]);
    expect(
      materializeTaskItems("task-1", "string.md5", seeds, {
        executorName: "worker.any",
        input: {}
      })
    ).toEqual([
      {
        messageId: "task-1-00001",
        eventCode: "string.md5",
        workerSelector: { executorName: "worker.any", input: {} },
        payload: { value: "one" }
      },
      {
        messageId: "task-1-00002",
        eventCode: "string.md5",
        workerSelector: { executorName: "worker.any", input: {} },
        payload: { value: "" }
      },
      {
        messageId: "task-1-00003",
        eventCode: "string.md5",
        workerSelector: { executorName: "worker.any", input: {} },
        payload: { value: "three" }
      }
    ]);
  });

  it("enforces 10,000 lines and chunks Items by 100", () => {
    expect(() =>
      parseSeedLines(
        bytes(Array.from({ length: MAX_TASK_SEED_LINES + 1 }, () => "x").join("\n"))
      )
    ).toThrow("10000 lines");
    expect(chunkTaskItems(Array.from({ length: 201 }, (_, index) => index))).toEqual([
      Array.from({ length: 100 }, (_, index) => index),
      Array.from({ length: 100 }, (_, index) => index + 100),
      [200]
    ]);
  });
});

describe("HttpFiniteTaskClient", () => {
  it("uses only the finite Task create, append, approve, and export routes", async () => {
    const download = new Blob(["{}\n"], { type: "application/x-ndjson" });
    const post = vi
      .fn()
      .mockResolvedValueOnce({ data: { taskId: "task-1" }, status: 200 })
      .mockResolvedValueOnce({
        data: { "task-1-00001": { status: "applied" } },
        status: 200
      })
      .mockResolvedValueOnce({ data: { status: "applied" }, status: 200 })
      .mockResolvedValueOnce({ data: download, status: 200, headers: {} });
    const client = new HttpFiniteTaskClient("/api", {
      post
    } as unknown as AxiosInstance);

    await client.createTask({
      projectId: "test-project",
      workerGroupId: "group-1",
      priority: 50,
      refill: [],
      maxRetryTimes: 3
    });
    await client.appendItems("task-1", [
      {
        messageId: "task-1-00001",
        eventCode: "event",
        workerSelector: { executorName: "worker.any", input: {} },
        payload: { value: "a" }
      }
    ]);
    await client.approveTask("task-1");
    await expect(client.exportResults("task-1")).resolves.toMatchObject({
      ready: true,
      fileName: "task-1-results.jsonl"
    });

    expect(post.mock.calls.map((call) => call[0])).toEqual([
      "/v1/tasks",
      "/v1/tasks/task-1/items",
      "/v1/tasks/task-1/approve",
      "/v1/tasks/task-1/results:export"
    ]);
    expect(post.mock.calls[1]?.[1][0]).not.toHaveProperty("allocationRule");
    expect(post.mock.calls[3]?.[1]).toBeUndefined();
    expect(post.mock.calls[3]?.[2]).not.toHaveProperty("timeout");
  });

  it("treats only 400/12010 as an export that can be retried", async () => {
    const post = vi.fn().mockResolvedValue({
      data: new Blob([
        JSON.stringify({
          code: 12010,
          message: "Task results are not ready",
          requestId: "request-1"
        })
      ]),
      status: 400,
      headers: {}
    });
    const client = new HttpFiniteTaskClient("/api", {
      post
    } as unknown as AxiosInstance);

    await expect(client.exportResults("task-1")).resolves.toEqual({
      ready: false
    });
    expect(post.mock.calls[0]?.[2].validateStatus(503)).toBe(true);
  });

  it("rejects legacy Action response shapes", async () => {
    const post = vi
      .fn()
      .mockResolvedValueOnce({
        data: { "message-1": { status: "succeeded" } },
        status: 200
      })
      .mockResolvedValueOnce({ data: { status: "approved" }, status: 200 });
    const client = new HttpFiniteTaskClient("/api", {
      post
    } as unknown as AxiosInstance);

    await expect(
      client.appendItems("task-1", [
        {
          messageId: "message-1",
          eventCode: "event",
          workerSelector: { executorName: "worker.any", input: {} },
          payload: {}
        }
      ])
    ).rejects.toMatchObject({ kind: "schema" });
    await expect(client.approveTask("task-1")).rejects.toMatchObject({
      kind: "schema"
    });
  });
});

const group = {
  workerGroupId: "g",
  workerGroup: { workerGroupId: "g", eventCodes: ["event"], attributes: {} },
  matching: { pools: ["country"], functions: ["worker.country", "workerId"] }
};
function item(id: string): TaskItemApiRequest {
  return {
    messageId: id,
    eventCode: "event",
    payload: { nested: { text: "你好", array: [1, true] } },
    workerSelector: { executorName: "worker.country", input: {} }
  };
}
function observed(band: "pre_review" | "terminal" = "pre_review") {
  return {
    taskId: "task-1",
    createdAtMillis: 1,
    scoreBand: band,
    task: {
      taskId: "task-1",
      projectId: "default",
      workerGroupId: "g",
      idleDisposition: "CLOSE_WHEN_IDLE" as const,
      config: {},
      refill: [],
      name: null,
      metadata: {}
    }
  };
}
function request(count = 101) {
  return {
    projectId: "default",
    create: {
      projectId: "default",
      workerGroupId: "g",
      priority: 50,
      maxRetryTimes: 3,
      refill: []
    },
    fileName: "input.jsonl",
    total: count,
    items: () => Array.from({ length: count }, (_, i) => item("m" + i))
  };
}
describe("raw JSONL", () => {
  it("preserves complete nested Items and validates the whole file including its last line", () => {
    const input = { ...item("one"), priority: 7, ttlMillis: 1234 };
    expect(parseJsonlItems(bytes(JSON.stringify(input)), group)).toEqual([input]);
    expect(() => parseJsonlItems(bytes(JSON.stringify(input) + "\n{}"), group)).toThrow(
      "第 2 行"
    );
    expect(() =>
      parseJsonlItems(
        bytes(JSON.stringify(input) + "\n" + JSON.stringify(input)),
        group
      )
    ).toThrow("重复");
    expect(() =>
      parseJsonlItems(bytes(JSON.stringify({ ...input, eventCode: "missing" })), group)
    ).toThrow("事件");
    expect(() =>
      parseJsonlItems(
        bytes(
          JSON.stringify({
            ...input,
            workerSelector: { executorName: "worker.any", input: {} }
          })
        ),
        group
      )
    ).toThrow("函数");
  });
  it("selects only available built-in supply, never a business rule fallback", () => {
    expect(defaultSupply(group)).toEqual({
      refill: [{ poolName: "country", target: {}, count: 100 }],
      selector: { executorName: "worker.country", input: {} }
    });
    expect(
      defaultSupply({
        ...group,
        matching: { pools: ["custom"], functions: ["custom.query", "workerId"] }
      })
    ).toEqual({ refill: [], selector: undefined });
  });
});
describe("server-observed finite Task management", () => {
  beforeEach(() => setActivePinia(createPinia()));
  it("chunks without approval and manages rediscovered Tasks without a submission ledger", async () => {
    const client = fakeClient();
    const store = createTaskManagementStore("api", client);
    await store.submit(request());
    expect(client.appendItems).toHaveBeenCalledTimes(2);
    expect(store.project("default").submission).toMatchObject({
      taskId: "task-1",
      total: 101,
      applied: 101,
      complete: true
    });
    expect(client.approveTask).not.toHaveBeenCalled();
    await expect(store.submit(request())).rejects.toThrow("上一次");
    store.finishSubmission("default");
    await expect(store.mutate("default", observed(), "approve", true)).resolves.toBe(
      true
    );
    await expect(store.mutate("default", observed(), "close", true)).resolves.toBe(
      true
    );
    await expect(store.mutate("default", observed(), "approve", false)).rejects.toThrow(
      "快照"
    );
    await expect(
      store.mutate(
        "default",
        { ...observed(), task: { ...observed().task, projectId: "messages" } },
        "close",
        true
      )
    ).rejects.toThrow("快照");
  });
  it("counts confirmed entries in a partially rejected batch and never continues or recreates", async () => {
    const client = fakeClient({
      appendItems: vi.fn(async () => ({
        m0: { status: "applied" as const },
        m1: { status: "rejected" as const, code: 12001, message: "invalid" }
      }))
    });
    const store = createTaskManagementStore("api", client);
    await store.submit(request(201));
    expect(store.project("default").submission).toMatchObject({
      applied: 1,
      rejected: 1,
      unconfirmed: 98,
      notSubmitted: 101,
      complete: false
    });
    expect(client.appendItems).toHaveBeenCalledTimes(1);
    await expect(store.submit(request())).rejects.toThrow("上一次");
    expect(client.createTask).toHaveBeenCalledTimes(1);
  });
  it("preserves unknown creation or append results without retry", async () => {
    const client = fakeClient({
      createTask: vi.fn(async () => {
        throw new Error("response lost");
      })
    });
    const store = createTaskManagementStore("api", client);
    await store.submit(request());
    expect(store.project("default").submission).toMatchObject({
      complete: false,
      notSubmitted: 101,
      error: "response lost"
    });
    expect(client.appendItems).not.toHaveBeenCalled();
    await expect(store.submit(request())).rejects.toThrow("上一次");
  });
  it("disables every real operation in Mock", async () => {
    const client = fakeClient();
    const store = createTaskManagementStore("mock", client);
    await expect(store.submit(request())).rejects.toThrow("Mock");
    await expect(store.mutate("default", observed(), "approve", true)).rejects.toThrow(
      "Mock"
    );
    expect(client.createTask).not.toHaveBeenCalled();
  });
  it("retains a known creation identity and only ensures the directory on explicit recovery", async () => {
    const client = fakeClient({
      createTask: vi.fn(async () => {
        throw new FiniteTaskError({
          kind: "http",
          status: 503,
          taskId: "known",
          message: "Directory not confirmed"
        });
      })
    });
    const store = createTaskManagementStore("api", client);
    await store.submit(request());
    expect(store.project("default").submission).toMatchObject({
      taskId: "known",
      complete: false,
      notSubmitted: 101
    });
    expect(client.ensureDirectory).not.toHaveBeenCalled();
    expect(await store.ensureDirectory("default")).toBe(true);
    expect(client.ensureDirectory).toHaveBeenCalledExactlyOnceWith("known");
    expect(client.createTask).toHaveBeenCalledTimes(1);
    expect(client.appendItems).not.toHaveBeenCalled();
    expect(client.approveTask).not.toHaveBeenCalled();
  });
});
function fakeClient(overrides: Partial<FiniteTaskClient> = {}): FiniteTaskClient {
  return {
    createTask: vi.fn(
      async (): Promise<TaskCreateApiResponse> => ({ taskId: "task-1" })
    ),
    appendItems: vi.fn(async (_taskId: string, items: TaskItemApiRequest[]) =>
      appended(items.map((i) => i.messageId))
    ),
    approveTask: vi.fn(async () => undefined),
    closeTask: vi.fn(async () => undefined),
    ensureDirectory: vi.fn(async () => {}),
    exportResults: vi.fn(async (): Promise<TaskExportResult> => ({ ready: false })),
    ...overrides
  };
}
function appended(ids: string[]): TaskItemsAppendApiResponse {
  return Object.fromEntries(ids.map((id) => [id, { status: "applied" }]));
}
function bytes(value: string): ArrayBuffer {
  return new TextEncoder().encode(value).buffer;
}

it("finite submissions keep their captured Project across batches and isolate recovery without increasing concurrency", async () => {
  setActivePinia(createPinia());
  let release!: () => void;
  const client = fakeClient({
    createTask: vi.fn(async (request) => ({ taskId: request.projectId + "-task" })),
    appendItems: vi.fn(async (_taskId: string, items: TaskItemApiRequest[]) => {
      if (items[0]!.messageId === "m0")
        await new Promise<void>((resolve) => {
          release = resolve;
        });
      return appended(items.map((item) => item.messageId));
    })
  });
  const store = createTaskManagementStore("api", client);
  const a = {
    ...request(),
    projectId: "agentforge",
    create: { ...request().create, projectId: "agentforge" }
  };
  const b = {
    ...request(),
    projectId: "messages",
    create: { ...request().create, projectId: "messages" }
  };
  const pending = store.submit(a);
  await vi.waitFor(() => expect(release).toBeTypeOf("function"));
  expect(store.project("messages").submission).toBeUndefined();
  await expect(store.submit(b)).rejects.toThrow("操作进行中");
  release();
  await pending;
  expect(
    vi
      .mocked(client.appendItems)
      .mock.calls.every((call) => call[0] === "agentforge-task")
  ).toBe(true);
  expect(store.project("agentforge").submission).toMatchObject({
    applied: 101,
    complete: true
  });
  vi.mocked(client.createTask).mockRejectedValueOnce(new Error("unconfirmed"));
  await store.submit(b);
  expect(store.project("messages").submission).toMatchObject({
    applied: 0,
    notSubmitted: 101,
    complete: false
  });
  expect(store.project("agentforge").submission?.complete).toBe(true);
  store.finishSubmission("agentforge");
  expect(store.project("messages").submission).toBeDefined();
  await expect(store.mutate("messages", observed(), "close", true)).rejects.toThrow(
    "快照"
  );
});
