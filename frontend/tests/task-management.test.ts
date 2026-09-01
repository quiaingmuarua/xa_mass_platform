import type { AxiosInstance } from "axios";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { HttpFiniteTaskClient } from "@/task-management/http-client";
import {
  buildSeedItems,
  chunkTaskItems,
  materializeTaskItems,
  MAX_TASK_SEED_LINES,
  parseSeedLines
} from "@/task-management/model";
import type {
  FiniteTaskClient,
  TaskCreateApiResponse,
  TaskExportResult,
  TaskItemApiRequest,
  TaskItemAppendOutcome,
  TaskItemsAppendApiResponse
} from "@/task-management/types";
import { createTaskManagementStore } from "@/stores/task-management";

describe("finite Task input model", () => {
  it("parses UTF-8, keeps empty lines, and creates stable five-digit IDs", () => {
    const lines = parseSeedLines(bytes("one\n\nthree\n"));
    const seeds = buildSeedItems(lines, "value");

    expect(lines).toEqual(["one", "", "three"]);
    expect(materializeTaskItems("task-1", "string.md5", seeds)).toEqual([
      { messageId: "task-1-00001", eventCode: "string.md5", payload: { value: "one" } },
      { messageId: "task-1-00002", eventCode: "string.md5", payload: { value: "" } },
      {
        messageId: "task-1-00003",
        eventCode: "string.md5",
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
        data: { "task-1-00001": { status: "succeeded" } },
        status: 200
      })
      .mockResolvedValueOnce({ data: { status: "approved" }, status: 200 })
      .mockResolvedValueOnce({ data: download, status: 200, headers: {} });
    const client = new HttpFiniteTaskClient("/api", {
      post
    } as unknown as AxiosInstance);

    await client.createTask({
      workerGroupId: "group-1",
      allocationRule: {},
      priority: 50,
      maximumCandidateWorkers: 10,
      maxRetryTimes: 3
    });
    await client.appendItems("task-1", [
      { messageId: "task-1-00001", eventCode: "event", payload: { value: "a" } }
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
});

describe("finite Task management store", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("creates, chunks, waits for explicit approval, and manually retries export", async () => {
    const order: string[] = [];
    const client = fakeClient({
      createTask: vi.fn(async () => {
        order.push("create");
        return { taskId: "task-1" };
      }),
      appendItems: vi.fn(async (_taskId: string, items: TaskItemApiRequest[]) => {
        order.push(`append-${items.length}`);
        return appended(items.map((item) => item.messageId));
      }),
      approveTask: vi.fn(async () => {
        order.push("approve");
      }),
      exportResults: vi
        .fn()
        .mockResolvedValueOnce({ ready: false })
        .mockResolvedValueOnce({
          ready: true,
          fileName: "task-1-results.jsonl",
          blob: new Blob(["{}\n"])
        })
    });
    const store = createTaskManagementStore(catalog("api"), client);

    const task = await store.createAndAppend(
      executionRequest(
        Array.from({ length: 101 }, (_, index) => `line-${index}`).join("\n")
      )
    );

    expect(order).toEqual(["create", "append-100", "append-1"]);
    expect(task).toMatchObject({
      taskId: "task-1",
      appendedCount: 101,
      stage: "ITEMS_APPENDED"
    });
    expect(client.approveTask).not.toHaveBeenCalled();

    await expect(store.approveTask("task-1")).resolves.toBe(true);
    expect(task?.stage).toBe("APPROVED");
    await expect(store.exportTask("task-1")).resolves.toBeUndefined();
    expect(store.notice).toContain("尚未就绪");
    await expect(store.exportTask("task-1")).resolves.toMatchObject({
      fileName: "task-1-results.jsonl"
    });
    expect(task?.stage).toBe("EXPORT_READY");
  });

  it("stops after an Append failure and never approves the Task", async () => {
    const client = fakeClient({
      appendItems: vi
        .fn()
        .mockResolvedValueOnce(
          appended(
            Array.from(
              { length: 100 },
              (_, index) => `task-1-${String(index + 1).padStart(5, "0")}`
            )
          )
        )
        .mockResolvedValueOnce({
          "task-1-00101": {
            status: "failed",
            code: 12003,
            message: "Task data is temporarily unavailable."
          }
        })
    });
    const store = createTaskManagementStore(catalog("api"), client);

    await store.createAndAppend(
      executionRequest(Array.from({ length: 101 }, () => "line").join("\n"))
    );

    expect(store.tasks[0]).toMatchObject({ stage: "CREATED", appendedCount: 100 });
    expect(client.approveTask).not.toHaveBeenCalled();
    expect(store.error?.message).toContain("temporarily unavailable");
  });

  it("disables every real operation in Mock mode without client fallback", async () => {
    const client = fakeClient();
    const store = createTaskManagementStore(catalog("mock"), client);

    await store.createAndAppend(executionRequest("line"));

    expect(store.tasks).toEqual([]);
    expect(client.createTask).not.toHaveBeenCalled();
    expect(client.appendItems).not.toHaveBeenCalled();
  });
});

function catalog(mode: "api" | "mock") {
  return {
    mode,
    workerGroups: [
      {
        workerGroupId: "scenario-string-utils-workers",
        eventCodes: ["extension.worker.string.md5"]
      }
    ]
  };
}

function executionRequest(contents: string) {
  return {
    workerGroupId: "scenario-string-utils-workers",
    eventCode: "extension.worker.string.md5",
    payloadKey: "value",
    file: textFile("seed.txt", contents),
    config: { priority: 50, maximumCandidateWorkers: 10, maxRetryTimes: 3 }
  };
}

function fakeClient(overrides: Partial<FiniteTaskClient> = {}): FiniteTaskClient {
  return {
    createTask: vi.fn(
      async (): Promise<TaskCreateApiResponse> => ({
        taskId: "task-1"
      })
    ),
    appendItems: vi.fn(async (_taskId: string, items: TaskItemApiRequest[]) =>
      appended(items.map((item) => item.messageId))
    ),
    approveTask: vi.fn(async () => undefined),
    exportResults: vi.fn(async (): Promise<TaskExportResult> => ({ ready: false })),
    ...overrides
  };
}

function appended(messageIds: string[]): TaskItemsAppendApiResponse {
  return Object.fromEntries(
    messageIds.map((messageId): [string, TaskItemAppendOutcome] => [
      messageId,
      { status: "succeeded" }
    ])
  );
}

function bytes(value: string): ArrayBuffer {
  return new TextEncoder().encode(value).buffer;
}

function textFile(name: string, contents: string): File {
  const file = new File([contents], name, { type: "text/plain" });
  Object.defineProperty(file, "arrayBuffer", {
    configurable: true,
    value: vi.fn(async () => bytes(contents))
  });
  return file;
}
