import { AxiosError, type AxiosInstance, type AxiosResponse } from "axios";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { WorkerDirectCallError } from "@/worker-direct-call/errors";
import { HttpWorkerDirectCallClient } from "@/worker-direct-call/http-client";
import {
  isWorkerDirectCallEnabled,
  validateWorkerDirectCallRequest
} from "@/worker-direct-call/model";
import { presentWorkerDirectCallTarget } from "@/worker-direct-call/presentation";
import {
  createWorkerDirectDebugStore,
  DIRECT_DEBUG_HISTORY_LIMIT
} from "@/stores/worker-direct-debug";
import type {
  WorkerDirectCallClient,
  WorkerDirectCallResult
} from "@/worker-direct-call/types";

describe("Worker Direct Call request model", () => {
  it("accepts custom Event Names and preserves the original JSON payload text", () => {
    const request = validateWorkerDirectCallRequest({
      workerGroupId: " group-a ",
      workerId: " worker-1 ",
      endpointManagerId: " adapter/a ",
      eventName: " extension.worker.custom ",
      payloadText: '  {\n  "value": "hello"\n}  ',
      waitTimeoutMillis: 3_000
    });

    expect(request).toMatchObject({
      workerGroupId: "group-a",
      workerId: "worker-1",
      endpointManagerId: "adapter/a",
      eventName: "extension.worker.custom"
    });
    expect(request.payloadText).toBe('  {\n  "value": "hello"\n}  ');
  });

  it("rejects invalid JSON, blank Events, and timeout values outside 1..10000", () => {
    expect(() =>
      validateWorkerDirectCallRequest(request({ payloadText: "{" }))
    ).toThrow("合法 JSON");
    expect(() => validateWorkerDirectCallRequest(request({ eventName: "  " }))).toThrow(
      "Event Name"
    );
    expect(() =>
      validateWorkerDirectCallRequest(request({ waitTimeoutMillis: 10_001 }))
    ).toThrow("1..10000");
    expect(() =>
      validateWorkerDirectCallRequest(request({ waitTimeoutMillis: 1.5 }))
    ).toThrow("整数");
  });

  it("enables mutations only for the real API mode", () => {
    expect(isWorkerDirectCallEnabled("api")).toBe(true);
    expect(isWorkerDirectCallEnabled("mock")).toBe(false);
  });
});

describe("HttpWorkerDirectCallClient", () => {
  it("uses only the existing single-Worker Direct Call shape", async () => {
    const post = vi.fn().mockResolvedValue({
      status: 200,
      data: {
        directCallId: "call-1",
        status: "observed",
        results: {
          "worker-1": {
            status: "observed",
            outcomeCode: "200",
            opaqueResultPayload: '{"valid":true}'
          }
        }
      }
    });
    const client = new HttpWorkerDirectCallClient("/api", {
      post
    } as unknown as AxiosInstance);

    await expect(client.callWorker(request())).resolves.toMatchObject({
      directCallId: "call-1",
      status: "observed",
      target: { status: "observed", outcomeCode: "200" }
    });

    expect(post).toHaveBeenCalledWith(
      "/v1/worker-delivery/endpoint-managers/adapter-a/direct-calls",
      {
        workerGroupId: "group-a",
        workerPayloads: { "worker-1": '{"value":"hello"}' },
        messageType: "extension.worker.custom",
        waitTimeoutMillis: 3_000
      },
      {
        headers: { "X-Request-Id": expect.any(String) },
        timeout: 8_000
      }
    );
    expect(post.mock.calls[0]?.[1]).not.toHaveProperty("opaquePayload");
    expect(post.mock.calls[0]?.[1]).not.toHaveProperty("taskId");
    expect(post.mock.calls[0]?.[1]).not.toHaveProperty("allocationRule");
  });

  it("rejects missing, additional, inconsistent, and unknown response fields", async () => {
    const responses = [
      {
        directCallId: "call-1",
        status: "observed",
        results: {}
      },
      {
        directCallId: "call-1",
        status: "observed",
        results: {
          "worker-1": { status: "observed", outcomeCode: "200" },
          "worker-2": { status: "observed", outcomeCode: "200" }
        }
      },
      {
        directCallId: "call-1",
        status: "partial",
        results: { "worker-1": { status: "observed", outcomeCode: "200" } }
      },
      {
        directCallId: "call-1",
        status: "observed",
        results: {
          "worker-1": { status: "observed", outcomeCode: "200", internal: true }
        }
      }
    ];

    for (const data of responses) {
      const client = new HttpWorkerDirectCallClient("/api", {
        post: vi.fn().mockResolvedValue({ status: 200, data })
      } as unknown as AxiosInstance);
      await expect(client.callWorker(request())).rejects.toMatchObject({
        kind: "schema"
      });
    }
  });

  it("accepts all target outcome variants and keeps non-200 observed distinct", async () => {
    for (const target of [
      { status: "observed", outcomeCode: "3302", opaqueResultPayload: "{}" },
      { status: "unobserved", reason: "timeout" },
      { status: "rejected", reason: "command-slot-occupied" }
    ] as const) {
      const client = new HttpWorkerDirectCallClient("/api", {
        post: vi.fn().mockResolvedValue({
          status: 200,
          data: {
            directCallId: "call-1",
            status: target.status === "observed" ? "observed" : "partial",
            results: { "worker-1": target }
          }
        })
      } as unknown as AxiosInstance);
      await expect(client.callWorker(request())).resolves.toMatchObject({ target });
    }

    expect(
      presentWorkerDirectCallTarget({ status: "observed", outcomeCode: "3302" })
    ).toMatchObject({ tone: "warning" });
    expect(
      presentWorkerDirectCallTarget({ status: "observed", outcomeCode: "200" })
    ).toMatchObject({ tone: "success" });
  });

  it("maps Direct Call API errors to safe messages and keeps the request ID", async () => {
    const response = {
      status: 503,
      statusText: "Service Unavailable",
      headers: {},
      config: {},
      data: {
        code: 17004,
        message: "internal provider and path details",
        requestId: "request-1"
      }
    } as AxiosResponse;
    const client = new HttpWorkerDirectCallClient("/api", {
      post: vi
        .fn()
        .mockRejectedValue(
          new AxiosError("failed", "ERR_BAD_RESPONSE", undefined, undefined, response)
        )
    } as unknown as AxiosInstance);

    const error = await client.callWorker(request()).catch((cause: unknown) => cause);

    expect(error).toBeInstanceOf(WorkerDirectCallError);
    expect(error).toMatchObject({
      kind: "http",
      code: 17004,
      requestId: "request-1",
      message: "Direct Call 当前不可用。"
    });
    expect((error as Error).message).not.toContain("internal provider");
  });
});

describe("Worker Direct Debug browser-memory store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it("creates one sending item and updates that item with the response", async () => {
    const pending = deferred<WorkerDirectCallResult>();
    const client: WorkerDirectCallClient = {
      callWorker: vi.fn().mockReturnValue(pending.promise)
    };
    const store = createWorkerDirectDebugStore(client, "api");

    const sending = store.send(request());

    expect(store.history("worker-1")).toHaveLength(1);
    expect(store.history("worker-1")[0]).toMatchObject({
      workerId: "worker-1",
      eventName: "extension.worker.custom",
      payloadText: '{"value":"hello"}',
      state: "sending"
    });
    pending.resolve(successResult("call-1"));
    await sending;

    expect(store.history("worker-1")).toHaveLength(1);
    expect(store.history("worker-1")[0]).toMatchObject({
      state: "completed",
      response: { directCallId: "call-1" }
    });
  });

  it("keeps Worker histories independent and clears only the selected Worker", async () => {
    const client: WorkerDirectCallClient = {
      callWorker: vi.fn().mockResolvedValue(successResult("call"))
    };
    const store = createWorkerDirectDebugStore(client, "api");

    await store.send(request());
    await store.send(request({ workerId: "worker-2" }));

    expect(store.history("worker-1")).toHaveLength(1);
    expect(store.history("worker-2")).toHaveLength(1);
    expect(store.clear("worker-1")).toBe(true);
    expect(store.history("worker-1")).toEqual([]);
    expect(store.history("worker-2")).toHaveLength(1);
  });

  it("retains only the latest completed calls per Worker", async () => {
    const client: WorkerDirectCallClient = {
      callWorker: vi.fn().mockImplementation(async () => successResult("call"))
    };
    const store = createWorkerDirectDebugStore(client, "api");

    for (let index = 0; index <= DIRECT_DEBUG_HISTORY_LIMIT; index += 1) {
      await store.send(
        request({
          payloadText: JSON.stringify({ index })
        })
      );
    }

    expect(store.history("worker-1")).toHaveLength(DIRECT_DEBUG_HISTORY_LIMIT);
    expect(store.history("worker-1")[0]?.payloadText).toBe('{"index":1}');
    expect(store.history("worker-1").at(-1)?.payloadText).toBe('{"index":20}');
  });

  it("allows only one in-flight call per Worker without blocking another Worker", async () => {
    const first = deferred<WorkerDirectCallResult>();
    const client: WorkerDirectCallClient = {
      callWorker: vi
        .fn()
        .mockReturnValueOnce(first.promise)
        .mockResolvedValueOnce(successResult("call-2"))
    };
    const store = createWorkerDirectDebugStore(client, "api");

    const workerOne = store.send(request());
    await expect(store.send(request())).rejects.toThrow("already");
    await expect(store.send(request({ workerId: "worker-2" }))).resolves.toMatchObject({
      state: "completed"
    });
    expect(store.clear("worker-1")).toBe(false);

    first.resolve(successResult("call-1"));
    await workerOne;
    expect(store.clear("worker-1")).toBe(true);
  });

  it("records safe call failures but does not record validation or Mock failures", async () => {
    const client: WorkerDirectCallClient = {
      callWorker: vi.fn().mockRejectedValue(
        new WorkerDirectCallError({
          kind: "http",
          message: "Direct Call 当前不可用。",
          requestId: "request-1"
        })
      )
    };
    const store = createWorkerDirectDebugStore(client, "api");

    await store.send(request());
    expect(store.history("worker-1")[0]).toMatchObject({
      state: "failed",
      safeError: {
        message: "Direct Call 当前不可用。",
        requestId: "request-1"
      }
    });
    await expect(store.send(request({ payloadText: "{" }))).rejects.toThrow(
      "合法 JSON"
    );
    expect(store.history("worker-1")).toHaveLength(1);

    setActivePinia(createPinia());
    const mockStore = createWorkerDirectDebugStore(client, "mock");
    await expect(mockStore.send(request())).rejects.toThrow("Mock");
    expect(mockStore.history("worker-1")).toEqual([]);
    expect(client.callWorker).toHaveBeenCalledTimes(1);
  });

  it("starts empty when a new browser-memory Store is created", async () => {
    const client: WorkerDirectCallClient = {
      callWorker: vi.fn().mockResolvedValue(successResult("call-1"))
    };
    const first = createWorkerDirectDebugStore(client, "api");
    await first.send(request());
    expect(first.history("worker-1")).toHaveLength(1);

    setActivePinia(createPinia());
    const refreshed = createWorkerDirectDebugStore(client, "api");
    expect(refreshed.history("worker-1")).toEqual([]);
  });
});

function request(overrides = {}) {
  return {
    workerGroupId: "group-a",
    workerId: "worker-1",
    endpointManagerId: "adapter-a",
    eventName: "extension.worker.custom",
    payloadText: '{"value":"hello"}',
    waitTimeoutMillis: 3_000,
    ...overrides
  };
}

function successResult(directCallId: string): WorkerDirectCallResult {
  return {
    directCallId,
    status: "observed",
    target: {
      status: "observed",
      outcomeCode: "200",
      opaqueResultPayload: '{"valid":true}'
    }
  };
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
