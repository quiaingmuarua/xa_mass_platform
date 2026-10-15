import { consoleIdentity } from "@/console-identity";
import { type TaskCaller } from "@/task-caller";
import { ADMIN as CALLER } from "./identity-fixture";
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
  suggestedSupply,
  parsePayloadLines
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

    await client.createTask(CALLER, {
      requestId: "request",
      projectId: "test-project",
      workerGroupId: "group-1",
      priority: 50,
      refill: [],
      maxRetryTimes: 3
    });
    await client.appendItems(CALLER, "task-1", [
      {
        messageId: "task-1-00001",
        eventCode: "event",
        workerSelector: { executorName: "worker.any", input: {} },
        payload: { value: "a" }
      }
    ]);
    await client.approveTask(CALLER, "task-1", 1);
    await expect(client.exportResults(CALLER, "task-1")).resolves.toMatchObject({
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

    await expect(client.exportResults(CALLER, "task-1")).resolves.toEqual({
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
      client.appendItems(CALLER, "task-1", [
        {
          messageId: "message-1",
          eventCode: "event",
          workerSelector: { executorName: "worker.any", input: {} },
          payload: {}
        }
      ])
    ).rejects.toMatchObject({ kind: "schema" });
    await expect(client.approveTask(CALLER, "task-1", 1)).rejects.toMatchObject({
      kind: "schema"
    });
  });
});

const group = {
  workerGroupId: "g",
  workerGroup: { workerGroupId: "g", eventCodes: ["event"], attributes: {} },
  matching: {
    pools: ["country"],
    functions: [
      { name: "worker.country", poolName: "country" },
      { name: "workerId", poolName: null }
    ]
  }
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
    stats: { total: 101, countsByTag: { "1": 101 } },
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
    caller: CALLER,
    projectId: "default",
    create: {
      requestId: "request",
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
  it("uses declared consumption relationships without inferring names", () => {
    expect(suggestedSupply(group, "worker.country")).toEqual([
      { poolName: "country", target: {}, count: 100 }
    ]);
    const custom = {
      ...group,
      matching: {
        pools: ["renamed"],
        functions: [
          { name: "choose", poolName: "renamed" },
          { name: "workerId", poolName: null }
        ]
      }
    };
    expect(suggestedSupply(custom, "choose")).toEqual([
      { poolName: "renamed", target: {}, count: 100 }
    ]);
    expect(suggestedSupply(custom, "workerId")).toEqual([]);
    expect(() => suggestedSupply(custom, "missing")).toThrow();
  });
});
describe("server-observed finite Task management", () => {
  beforeEach(() => {
    consoleIdentity.login("1000");
    setActivePinia(createPinia());
  });
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
    expect(client.ensureDirectory).toHaveBeenCalledExactlyOnceWith(CALLER, "known");
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
    appendItems: vi.fn(
      async (_caller: TaskCaller, _taskId: string, items: TaskItemApiRequest[]) =>
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
    createTask: vi.fn(async (_caller, request) => ({
      taskId: request.projectId + "-task"
    })),
    appendItems: vi.fn(
      async (_caller: TaskCaller, _taskId: string, items: TaskItemApiRequest[]) => {
        if (items[0]!.messageId === "m0")
          await new Promise<void>((resolve) => {
            release = resolve;
          });
        return appended(items.map((item) => item.messageId));
      }
    )
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
      .mock.calls.every((call) => call[1] === "agentforge-task")
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

it("does not start append after a disposed login's creation completes", async () => {
  setActivePinia(createPinia());
  let finish!: (value: TaskCreateApiResponse) => void;
  const client = fakeClient({
    createTask: vi.fn<FiniteTaskClient["createTask"]>(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        })
    )
  });
  const store = createTaskManagementStore("api", client);
  const pending = store.submit(request());
  store.dispose();
  finish({ taskId: "created-before-logout" });
  await expect(pending).resolves.toBe("created-before-logout");
  expect(client.appendItems).not.toHaveBeenCalled();
  expect(client.createTask).toHaveBeenCalledExactlyOnceWith(CALLER, request().create);
});
it("stops remaining append batches after logout without changing the admitted caller", async () => {
  setActivePinia(createPinia());
  let finish!: () => void;
  const client = fakeClient({
    appendItems: vi.fn<FiniteTaskClient["appendItems"]>(
      (_caller, _task, items) =>
        new Promise((resolve) => {
          finish = () => resolve(appended(items.map((item) => item.messageId)));
        })
    )
  });
  const store = createTaskManagementStore("api", client);
  const pending = store.submit(request());
  await vi.waitFor(() => expect(finish).toBeTypeOf("function"));
  store.dispose();
  finish();
  await pending;
  expect(client.appendItems).toHaveBeenCalledTimes(1);
  expect(vi.mocked(client.appendItems).mock.calls[0]![0]).toEqual(CALLER);
  expect(store.project("default").submission?.notSubmitted).toBe(1);
});

describe("payload JSONL input", () => {
  it("preserves nested payloads, physical line identities and repeated content", () => {
    const content = '{"text":"你好\\nworld","nested":[true,1]}';
    const rows = parsePayloadLines(
      bytes("\ufeff" + content + "\n \n" + content + "\n")
    );
    expect(rows.map((r) => r.lineNumber)).toEqual([1, 3]);
    expect(rows[0]?.payload).toEqual(rows[1]?.payload);
    expect(rows[0]?.payload.text).toBe("你好\nworld");
    expect(() => parsePayloadLines(bytes(content + "\n\n[]"))).toThrow("第 3 行");
    expect(() => parsePayloadLines(bytes("{}\n".repeat(10001)))).toThrow();
    expect(() =>
      parsePayloadLines(bytes('{"value":"' + "x".repeat(1024 * 1024) + '"}'))
    ).toThrow("1 MiB");
    expect(() => parsePayloadLines(new Uint8Array([255]).buffer)).toThrow("UTF-8");
  });
});
