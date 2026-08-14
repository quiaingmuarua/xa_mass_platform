import type { AxiosInstance } from "axios";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { HttpTaskBatchClient } from "@/task-batch/http-client";
import { taskBatchRunResponseSchema } from "@/task-batch/schemas";
import type { TaskBatchClient } from "@/task-batch/types";
import { createRuntimeViewerStore } from "@/stores/runtime-viewer";
import { createTaskBatchStore } from "@/stores/task-batch";

const apiConfig = { mode: "api" as const, apiBaseUrl: "/api" };

describe("Task Batch schema", () => {
  it("rejects Task, Worker, and inconsistent result fields", () => {
    expect(
      taskBatchRunResponseSchema.safeParse({
        ...runResponse(),
        taskId: "internal-task"
      }).success
    ).toBe(false);
    expect(
      taskBatchRunResponseSchema.safeParse({
        ...runResponse(),
        resultCount: 0,
        remainingCount: 0
      }).success
    ).toBe(false);
  });
});

describe("HttpTaskBatchClient", () => {
  it("uses upload, direct run, and download routes", async () => {
    const blob = new Blob(["{}\n"], { type: "application/x-ndjson" });
    const post = vi
      .fn()
      .mockResolvedValueOnce({
        data: { fileName: "seed.txt", byteCount: 4, lineCount: 1 }
      })
      .mockResolvedValueOnce({ data: runResponse() });
    const get = vi.fn().mockResolvedValueOnce({ data: blob });
    const client = new HttpTaskBatchClient("/api", {
      post,
      get
    } as unknown as AxiosInstance);
    const bytes = new TextEncoder().encode("seed").buffer;

    await client.uploadInput("seed.txt", bytes);
    await client.run(runRequest("seed.txt"));
    await expect(client.downloadOutput("task-batch-1.jsonl")).resolves.toBe(blob);

    expect(post.mock.calls[0]?.[0]).toBe("/v1/task-batches/input-files/seed.txt");
    expect(post.mock.calls[0]?.[1]).toBe(bytes);
    expect(post.mock.calls[1]?.slice(0, 2)).toEqual([
      "/v1/task-batches/runs",
      runRequest("seed.txt")
    ]);
    expect(post.mock.calls[1]?.[1]).not.toHaveProperty("taskId");
    expect(post.mock.calls[1]?.[1]).not.toHaveProperty("scenarioType");
    expect(post.mock.calls[1]?.[2].timeout).toBe(60_000);
    expect(get.mock.calls[0]?.[0]).toBe(
      "/v1/task-batches/output-files/task-batch-1.jsonl"
    );
  });
});

describe("Task Batch store", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("uses configured resources and executes upload then run", async () => {
    const order: string[] = [];
    const runtime = runtimeStore();
    await runtime.initialize();
    const client = fakeClient({
      uploadInput: vi.fn(async (fileName, content) => {
        order.push("upload");
        return { fileName, byteCount: content.byteLength, lineCount: 2 };
      }),
      run: vi.fn(async (request) => {
        order.push("run");
        return runResponse(request.inputFile);
      })
    });
    const store = createTaskBatchStore(apiConfig, client, runtime);

    await store.execute({
      workerGroupId: "scenario-string-utils-workers",
      eventCode: "string.md5",
      payloadKey: "value",
      file: textFile("seed.txt", "one\ntwo\n"),
      maximumWaitMillis: 30_000
    });

    expect(order).toEqual(["upload", "run"]);
    expect(store.runs).toHaveLength(1);
    expect(store.runs[0]?.runId).toBe("task-batch-1");
    expect(vi.mocked(client.run).mock.calls[0]?.[0]).toMatchObject({
      workerGroupId: "scenario-string-utils-workers",
      eventCode: "string.md5",
      payloadKey: "value"
    });
  });

  it("uses advisory events only as frontend selection and disables Mock calls", async () => {
    const runtime = runtimeStore();
    await runtime.initialize();
    const client = fakeClient();
    const invalid = createTaskBatchStore(apiConfig, client, runtime);
    await invalid.execute({
      workerGroupId: "scenario-string-utils-workers",
      eventCode: "not-listed",
      payloadKey: "value",
      file: textFile("seed.txt", "one"),
      maximumWaitMillis: 30_000
    });
    expect(invalid.error?.message).toContain("configured EventCode");
    expect(client.uploadInput).not.toHaveBeenCalled();

    setActivePinia(createPinia());
    const mockStore = createTaskBatchStore(
      { mode: "mock", apiBaseUrl: "/api" },
      client,
      runtime
    );
    await mockStore.execute({
      workerGroupId: "scenario-string-utils-workers",
      eventCode: "string.md5",
      payloadKey: "value",
      file: textFile("seed.txt", "one"),
      maximumWaitMillis: 30_000
    });
    expect(client.run).not.toHaveBeenCalled();
  });
});

function runtimeStore() {
  return createRuntimeViewerStore(apiConfig, {
    loadConfiguredResources: vi.fn(async () => ({
      entries: [
        {
          workerGroupId: "scenario-string-utils-workers",
          taskId: "internal-task",
          workerGroup: {
            workerGroupId: "scenario-string-utils-workers",
            attributes: {},
            eventCodes: ["string.md5", "string.sha1"]
          },
          task: null
        }
      ]
    })),
    previewWorkers: vi.fn()
  });
}

function fakeClient(overrides: Partial<TaskBatchClient> = {}): TaskBatchClient {
  return {
    uploadInput: vi.fn(async (fileName, content) => ({
      fileName,
      byteCount: content.byteLength,
      lineCount: 1
    })),
    run: vi.fn(async (request) => runResponse(request.inputFile)),
    downloadOutput: vi.fn(async () => new Blob(["{}\n"])),
    ...overrides
  };
}

function runRequest(inputFile: string) {
  return {
    workerGroupId: "scenario-string-utils-workers",
    eventCode: "string.md5",
    payloadKey: "value",
    inputFile,
    maximumWaitMillis: 30_000
  };
}

function runResponse(inputFile = "seed.txt") {
  return {
    runId: "task-batch-1",
    workerGroupId: "scenario-string-utils-workers",
    eventCode: "string.md5",
    payloadKey: "value",
    status: "succeeded" as const,
    inputFile,
    inputCount: 1,
    resultCount: 1,
    remainingCount: 0,
    loadRounds: 1,
    durationMillis: 10,
    outputFile: "task-batch-1.jsonl"
  };
}

function textFile(name: string, contents: string): File {
  const file = new File([contents], name, { type: "text/plain" });
  Object.defineProperty(file, "arrayBuffer", {
    configurable: true,
    value: vi.fn(async () => new TextEncoder().encode(contents).buffer)
  });
  return file;
}
