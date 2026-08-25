import { AxiosError, type AxiosInstance, type AxiosResponse } from "axios";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ConfiguredRuntimeResourceEntry } from "@/runtime-viewer/types";
import {
  createTaskCallDebugStore,
  TASK_CALL_DEBUG_HISTORY_LIMIT
} from "@/stores/task-call-debug";
import { TaskCallDebugError } from "@/task-call-debug/errors";
import { HttpTaskCallDebugClient } from "@/task-call-debug/http-client";
import {
  taskCallDebugAvailability,
  validateTaskCallDebugDraft
} from "@/task-call-debug/model";
import type {
  TaskCallDebugClient,
  TaskCallDebugClientRequest,
  TaskCallDebugOutcome,
  TaskCallDebugDraft
} from "@/task-call-debug/types";

describe("Task Call Debug request model", () => {
  it("accepts custom Events and parses only JSON Object inputs", () => {
    const validated = validateTaskCallDebugDraft(
      draft({
        taskId: " task-1 ",
        workerGroupId: " group-a ",
        eventName: " extension.worker.custom ",
        payloadText: '  {"value":"hello"}  ',
        allocationRuleText: '{"workerProperties.region":{"$eq":"cn"}}'
      })
    );

    expect(validated).toMatchObject({
      taskId: "task-1",
      workerGroupId: "group-a",
      eventName: "extension.worker.custom",
      payload: { value: "hello" },
      allocationRule: { "workerProperties.region": { $eq: "cn" } }
    });
    expect(validated.payloadText).toBe('  {"value":"hello"}  ');
  });

  it("rejects non-object Payloads, non-object rules, blank Events, and bad timeouts", () => {
    for (const payloadText of ["null", "[]", '"value"', "1"]) {
      expect(() => validateTaskCallDebugDraft(draft({ payloadText }))).toThrow(
        "Payload"
      );
    }
    for (const allocationRuleText of ["null", "[]", "true", "{"]) {
      expect(() => validateTaskCallDebugDraft(draft({ allocationRuleText }))).toThrow(
        "Allocation Rule"
      );
    }
    expect(() => validateTaskCallDebugDraft(draft({ eventName: "  " }))).toThrow(
      "Event Name"
    );
    expect(() =>
      validateTaskCallDebugDraft(draft({ waitTimeoutMillis: 60_001 }))
    ).toThrow("1..60000");
    expect(() => validateTaskCallDebugDraft(draft({ waitTimeoutMillis: 1.5 }))).toThrow(
      "整数"
    );
  });

  it("enables only real managed Task Call descriptors", () => {
    expect(taskCallDebugAvailability("api", entry())).toEqual({ enabled: true });
    expect(taskCallDebugAvailability("mock", entry())).toMatchObject({
      enabled: false,
      reason: expect.stringContaining("Mock")
    });
    expect(
      taskCallDebugAvailability("api", entry({ workerGroup: null }))
    ).toMatchObject({ enabled: false });
    expect(taskCallDebugAvailability("api", entry({ task: null }))).toMatchObject({
      enabled: false
    });
    expect(
      taskCallDebugAvailability(
        "api",
        entry({
          task: {
            ...entry().task!,
            workerAllocationMechanism: "PRECOMPUTED_TASK_RULE"
          }
        })
      )
    ).toMatchObject({ enabled: false });
    expect(
      taskCallDebugAvailability(
        "api",
        entry({
          task: { ...entry().task!, idleDisposition: "CLOSE_WHEN_IDLE" }
        })
      )
    ).toMatchObject({ enabled: false });
  });
});

describe("HttpTaskCallDebugClient", () => {
  it("sends exactly one ordinary Item through the existing managed Task Call", async () => {
    const post = vi.fn().mockResolvedValue({
      status: 200,
      data: {
        results: {
          "message-1": {
            status: "succeeded",
            opaqueResultPayload: '{"valid":true}'
          }
        }
      }
    });
    const client = new HttpTaskCallDebugClient("/api", {
      post
    } as unknown as AxiosInstance);

    await expect(client.callTask(clientRequest())).resolves.toEqual({
      status: "succeeded",
      opaqueResultPayload: '{"valid":true}'
    });

    expect(post).toHaveBeenCalledWith(
      "/v1/tasks/task-1/items:call",
      {
        items: [
          {
            messageId: "message-1",
            eventCode: "extension.worker.custom",
            payload: { value: "hello" },
            priority: 5,
            allocationRule: { region: { $eq: "cn" } }
          }
        ],
        waitTimeoutMillis: 3_000
      },
      {
        headers: { "X-Request-Id": expect.any(String) },
        timeout: 8_000
      }
    );
    const body = post.mock.calls[0]?.[1];
    expect(body).not.toHaveProperty("workerId");
    expect(body.items[0]).not.toHaveProperty("ttlMillis");
    expect(body.items[0]).not.toHaveProperty("taskAllocationRule");
  });

  it("accepts succeeded and not_observed but rejects the wrong Result identity", async () => {
    for (const outcome of [
      { status: "succeeded", opaqueResultPayload: "" },
      { status: "not_observed" }
    ] as const) {
      const client = httpClient({ results: { "message-1": outcome } });
      await expect(client.callTask(clientRequest())).resolves.toEqual(outcome);
    }

    for (const results of [
      {},
      { "message-2": { status: "not_observed" } },
      {
        "message-1": { status: "not_observed" },
        "message-2": { status: "not_observed" }
      },
      { "message-1": { status: "not_observed", internal: true } }
    ]) {
      const client = httpClient({ results });
      await expect(client.callTask(clientRequest())).rejects.toMatchObject({
        kind: "schema"
      });
    }
  });

  it("loads only the requested Message ID and accepts an absent Result", async () => {
    const post = vi
      .fn()
      .mockResolvedValueOnce({ status: 200, data: { results: {} } })
      .mockResolvedValueOnce({
        status: 200,
        data: { results: { "message-1": '{"valid":true}' } }
      });
    const client = new HttpTaskCallDebugClient("/api", {
      post
    } as unknown as AxiosInstance);

    await expect(client.loadResult("task/a", "message-1")).resolves.toBeUndefined();
    await expect(client.loadResult("task/a", "message-1")).resolves.toBe(
      '{"valid":true}'
    );
    expect(post).toHaveBeenLastCalledWith(
      "/v1/tasks/task%2Fa/results:load",
      { messageIds: ["message-1"] },
      {
        headers: { "X-Request-Id": expect.any(String) },
        timeout: 5_000
      }
    );

    const wrongIdentity = httpClient({
      results: { "message-2": '{"valid":true}' }
    });
    await expect(wrongIdentity.loadResult("task-1", "message-1")).rejects.toMatchObject(
      { kind: "schema" }
    );
  });

  it("maps Task API failures to safe messages and keeps the request ID", async () => {
    const response = {
      status: 503,
      statusText: "Service Unavailable",
      headers: {},
      config: {},
      data: {
        code: 12003,
        message: "private owner and Redis details",
        requestId: "request-1"
      }
    } as AxiosResponse;
    const client = new HttpTaskCallDebugClient("/api", {
      post: vi
        .fn()
        .mockRejectedValue(
          new AxiosError("failed", "ERR_BAD_RESPONSE", undefined, undefined, response)
        )
    } as unknown as AxiosInstance);

    const error = await client.callTask(clientRequest()).catch((cause) => cause);

    expect(error).toBeInstanceOf(TaskCallDebugError);
    expect(error).toMatchObject({
      kind: "http",
      code: 12003,
      requestId: "request-1",
      message: "Task Owner 暂时不可用。"
    });
    expect((error as Error).message).not.toContain("private owner");
  });
});

describe("Task Call Debug browser-memory store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it("creates one sending record and updates the same record on success", async () => {
    const pending = deferred<TaskCallDebugOutcome>();
    const client: TaskCallDebugClient = {
      callTask: vi.fn().mockReturnValue(pending.promise),
      loadResult: vi.fn()
    };
    const fixedNow = () => new Date("2026-08-25T08:00:00.123Z");
    const store = createTaskCallDebugStore(client, "api", fixedNow);

    const sending = store.send(draft());
    expect(store.history("task-1")).toHaveLength(1);
    expect(store.history("task-1")[0]).toMatchObject({
      messageId: "task-debug-1787644800123-1",
      state: "sending",
      payloadText: '{"value":"hello"}',
      allocationRuleText: "{}"
    });

    pending.resolve({
      status: "succeeded",
      opaqueResultPayload: '{"valid":true}'
    });
    await sending;
    expect(store.history("task-1")).toHaveLength(1);
    expect(store.history("task-1")[0]).toMatchObject({
      state: "succeeded",
      opaqueResultPayload: '{"valid":true}'
    });
  });

  it("keeps not_observed as accepted evidence and loads its Result manually", async () => {
    const client: TaskCallDebugClient = {
      callTask: vi.fn().mockResolvedValue({ status: "not_observed" }),
      loadResult: vi.fn().mockResolvedValue('{"valid":true}')
    };
    const store = createTaskCallDebugStore(client, "api");

    const accepted = await store.send(draft());
    expect(accepted.state).toBe("not_observed");
    expect(client.loadResult).not.toHaveBeenCalled();

    await store.loadResult("task-1", accepted.localId);
    expect(client.loadResult).toHaveBeenCalledWith("task-1", accepted.messageId);
    expect(accepted).toMatchObject({
      state: "succeeded",
      opaqueResultPayload: '{"valid":true}',
      lastCheckedAt: expect.any(String)
    });
  });

  it("keeps not_observed when a manual load is absent or fails", async () => {
    const client: TaskCallDebugClient = {
      callTask: vi.fn().mockResolvedValue({ status: "not_observed" }),
      loadResult: vi
        .fn()
        .mockResolvedValueOnce(undefined)
        .mockRejectedValueOnce(
          new TaskCallDebugError({
            kind: "http",
            message: "Task Owner 暂时不可用。",
            requestId: "request-1"
          })
        )
    };
    const store = createTaskCallDebugStore(client, "api");
    const accepted = await store.send(draft());

    await store.loadResult("task-1", accepted.localId);
    expect(accepted).toMatchObject({
      state: "not_observed",
      lastCheckedAt: expect.any(String)
    });
    await store.loadResult("task-1", accepted.localId);
    expect(accepted).toMatchObject({
      state: "not_observed",
      resultLoadError: {
        message: "Task Owner 暂时不可用。",
        requestId: "request-1"
      }
    });
  });

  it("isolates Tasks, permits different Task calls, and limits each history", async () => {
    const client: TaskCallDebugClient = {
      callTask: vi.fn().mockResolvedValue({
        status: "succeeded",
        opaqueResultPayload: "{}"
      }),
      loadResult: vi.fn()
    };
    const store = createTaskCallDebugStore(client, "api");

    await store.send(draft());
    await store.send(draft({ taskId: "task-2", workerGroupId: "group-b" }));
    expect(store.history("task-1")).toHaveLength(1);
    expect(store.history("task-2")).toHaveLength(1);

    for (let index = 1; index <= TASK_CALL_DEBUG_HISTORY_LIMIT; index += 1) {
      await store.send(draft({ payloadText: JSON.stringify({ index }) }));
    }
    expect(store.history("task-1")).toHaveLength(TASK_CALL_DEBUG_HISTORY_LIMIT);
    expect(store.history("task-1")[0]?.payloadText).toBe('{"index":1}');
    expect(store.clear("task-1")).toBe(true);
    expect(store.history("task-1")).toEqual([]);
    expect(store.history("task-2")).toHaveLength(1);
  });

  it("allows one operation per Task without blocking another Task", async () => {
    const first = deferred<TaskCallDebugOutcome>();
    const client: TaskCallDebugClient = {
      callTask: vi
        .fn()
        .mockReturnValueOnce(first.promise)
        .mockResolvedValueOnce({ status: "not_observed" }),
      loadResult: vi.fn()
    };
    const store = createTaskCallDebugStore(client, "api");

    const taskOne = store.send(draft());
    await expect(store.send(draft())).rejects.toThrow("already");
    await expect(
      store.send(draft({ taskId: "task-2", workerGroupId: "group-b" }))
    ).resolves.toMatchObject({ state: "not_observed" });
    expect(store.clear("task-1")).toBe(false);

    first.resolve({ status: "succeeded", opaqueResultPayload: "{}" });
    await taskOne;
    expect(store.clear("task-1")).toBe(true);
  });

  it("does not record validation or Mock-mode failures and starts fresh", async () => {
    const client: TaskCallDebugClient = {
      callTask: vi.fn().mockResolvedValue({ status: "not_observed" }),
      loadResult: vi.fn()
    };
    const apiStore = createTaskCallDebugStore(client, "api");
    await expect(apiStore.send(draft({ payloadText: "[]" }))).rejects.toThrow(
      "Payload"
    );
    expect(apiStore.history("task-1")).toEqual([]);

    setActivePinia(createPinia());
    const mockStore = createTaskCallDebugStore(client, "mock");
    await expect(mockStore.send(draft())).rejects.toThrow("Mock");
    expect(mockStore.history("task-1")).toEqual([]);
    expect(client.callTask).not.toHaveBeenCalled();

    setActivePinia(createPinia());
    const refreshed = createTaskCallDebugStore(client, "api");
    expect(refreshed.history("task-1")).toEqual([]);
  });
});

function entry(
  overrides: Partial<ConfiguredRuntimeResourceEntry> = {}
): ConfiguredRuntimeResourceEntry {
  return {
    workerGroupId: "group-a",
    taskId: "task-1",
    workerGroup: {
      workerGroupId: "group-a",
      attributes: {},
      eventCodes: ["extension.worker.custom"]
    },
    task: {
      taskId: "task-1",
      workerGroupId: "group-a",
      workerAllocationMechanism: "DIRECT_ITEM_RULE",
      idleDisposition: "PARK_WHEN_IDLE",
      allocationRule: null,
      config: {
        priority: "0",
        maximumCandidateWorkers: "1",
        maxRetryTimes: "3"
      }
    },
    ...overrides
  };
}

function draft(overrides: Partial<TaskCallDebugDraft> = {}): TaskCallDebugDraft {
  return {
    taskId: "task-1",
    workerGroupId: "group-a",
    eventName: "extension.worker.custom",
    payloadText: '{"value":"hello"}',
    allocationRuleText: "{}",
    waitTimeoutMillis: 3_000,
    ...overrides
  };
}

function clientRequest(
  overrides: Partial<TaskCallDebugClientRequest> = {}
): TaskCallDebugClientRequest {
  return {
    taskId: "task-1",
    messageId: "message-1",
    eventName: "extension.worker.custom",
    payload: { value: "hello" },
    allocationRule: { region: { $eq: "cn" } },
    waitTimeoutMillis: 3_000,
    ...overrides
  };
}

function httpClient(data: unknown): HttpTaskCallDebugClient {
  return new HttpTaskCallDebugClient("/api", {
    post: vi.fn().mockResolvedValue({ status: 200, data })
  } as unknown as AxiosInstance);
}

function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
} {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((fulfill) => {
    resolve = fulfill;
  });
  return { promise, resolve };
}
