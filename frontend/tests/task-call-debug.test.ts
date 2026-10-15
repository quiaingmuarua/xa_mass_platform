import { consoleIdentity } from "@/console-identity";
import { callerHeaders } from "@/task-caller";
import { ADMIN as CALLER } from "./identity-fixture";
import { type AxiosInstance } from "axios";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  createTaskCallDebugStore,
  TASK_CALL_DEBUG_HISTORY_LIMIT
} from "@/stores/task-call-debug";
import { TaskCallDebugError } from "@/task-call-debug/errors";
import { HttpTaskCallDebugClient } from "@/task-call-debug/http-client";
import {
  parseRpcRequest,
  parseWorkerSelector,
  parseResultIds,
  rpcCurl
} from "@/task-call-debug/model";
import type { RpcCallRequest, TaskCallDebugResults } from "@/task-call-debug/types";
const item = (id = "message-1") => ({
  messageId: id,
  eventCode: "custom.event",
  payload: { value: "hello" },
  workerSelector: { executorName: "workerId", input: "worker-a" }
});
const request = (): RpcCallRequest => ({ items: [item()], waitTimeoutMillis: 3000 });
const success: TaskCallDebugResults = {
  "message-1": { status: "succeeded", opaqueResultPayload: "original output" }
};
beforeEach(() => {
  consoleIdentity.login("1000");
  setActivePinia(createPinia());
});

describe("batch RPC request", () => {
  it("accepts 1..100 items, preserves payloads and duplicated identities", () => {
    const body = { items: [item(), { ...item(), payload: { value: "last\n你好" } }] };
    expect(parseRpcRequest(JSON.stringify(body))).toEqual(body);
    expect(
      parseRpcRequest(
        JSON.stringify({
          items: Array.from({ length: 100 }, (_, n) => item(String(n)))
        })
      ).items
    ).toHaveLength(100);
    for (const items of [[], Array.from({ length: 101 }, (_, n) => item(String(n)))])
      expect(() => parseRpcRequest(JSON.stringify({ items }))).toThrow();
  });
  it("validates the entire input including overwritten duplicates without changing IDs", () => {
    for (const bad of [
      { ...item(), payload: [] },
      { ...item(), messageId: " " },
      { ...item(), priority: 11 },
      { ...item(), ttlMillis: 0 },
      { ...item(), extra: true },
      { ...item(), workerSelector: null }
    ]) {
      expect(() => parseRpcRequest(JSON.stringify({ items: [bad, item()] }))).toThrow(
        "items.0"
      );
    }
    for (const timeout of [0, 60001, 1.5, "3000"])
      expect(() =>
        parseRpcRequest(JSON.stringify({ ...request(), waitTimeoutMillis: timeout }))
      ).toThrow();
    expect(() => parseRpcRequest("{")).toThrow();
    expect(() =>
      parseRpcRequest(JSON.stringify({ ...request(), extra: true }))
    ).toThrow();
  });
  it("keeps non-null JSON query shapes and owner-local bounds", () => {
    for (const input of [{}, "worker", [1, null], 7, true])
      expect(
        parseWorkerSelector(JSON.stringify({ executorName: "custom", input })).input
      ).toEqual(input);
    let nested: unknown = true;
    for (let n = 0; n < 8; n++) nested = [nested];
    expect(
      parseWorkerSelector(JSON.stringify({ executorName: "x", input: nested })).input
    ).toEqual(nested);
    for (const input of [null, [nested], Array(101).fill(null), "x".repeat(65535)])
      expect(() =>
        parseWorkerSelector(JSON.stringify({ executorName: "x", input }))
      ).toThrow();
    expect(() => parseWorkerSelector('{"executorName":"x","input":1e999}')).toThrow();
    expect(() =>
      parseWorkerSelector('{"executorName":"x","input":{},"extra":true}')
    ).toThrow();
  });
  it("validates known IDs and produces shell-quoted code requests", () => {
    expect(parseResultIds("a\nb\na")).toEqual(["a", "b"]);
    expect(() => parseResultIds("")).toThrow();
    const body = {
      ...request(),
      items: [{ ...item(), payload: { value: "it's \n你好" } }]
    };
    const curl = rpcCurl("http://localhost/api", "task/a", CALLER, body);
    expect(curl).toContain("/api/v1/tasks/task%2Fa/items:call");
    expect(curl).toContain("'\\''");
    expect(curl).toContain("X-XA-Dev-User");
  });
});
describe("HTTP batch adapter", () => {
  it("sends one batch without rewriting Item fields and verifies deduplicated response identities", async () => {
    const results = {
      a: { status: "succeeded", opaqueResultPayload: "raw" },
      b: { status: "failed" },
      c: { status: "not_observed" }
    };
    const post = vi.fn().mockResolvedValue({ status: 200, data: results });
    const client = new HttpTaskCallDebugClient("/api", {
      post
    } as unknown as AxiosInstance);
    const body = {
      items: [item("a"), item("b"), item("c"), item("a")],
      waitTimeoutMillis: 3000
    };
    await expect(
      client.callTask({ caller: CALLER, taskId: "task/a", request: body })
    ).resolves.toEqual(results);
    expect(post).toHaveBeenCalledExactlyOnceWith(
      "/v1/tasks/task%2Fa/items:call",
      body,
      expect.objectContaining({
        timeout: 8000,
        headers: expect.objectContaining(callerHeaders(CALLER))
      })
    );
    for (const data of [
      { a: results.a },
      { ...results, other: results.a },
      {
        a: results.a,
        b: { status: "failed", opaqueResultPayload: "wrong" },
        c: results.c
      }
    ]) {
      post.mockResolvedValueOnce({ status: 200, data });
      await expect(
        client.callTask({ caller: CALLER, taskId: "task", request: body })
      ).rejects.toMatchObject({ kind: "schema" });
    }
  });
  it("loads a known batch and keeps a sufficient client timeout for a server-default wait", async () => {
    const post = vi.fn().mockResolvedValue({ status: 200, data: success });
    const client = new HttpTaskCallDebugClient("/api", {
      post
    } as unknown as AxiosInstance);
    await expect(
      client.takeResults(CALLER, "task", ["message-1", "message-1"])
    ).resolves.toEqual(success);
    expect(post).toHaveBeenLastCalledWith(
      "/v1/tasks/task/results:take",
      ["message-1"],
      expect.any(Object)
    );
    await client.callTask({
      caller: CALLER,
      taskId: "task",
      request: { items: [item()] }
    });
    expect(post).toHaveBeenLastCalledWith(
      "/v1/tasks/task/items:call",
      { items: [item()] },
      expect.objectContaining({ timeout: 65000 })
    );
  });
});
describe("session request evidence", () => {
  it("keeps denied takes distinct from uncertain consumption without retrying execution", async () => {
    const client = {
      callTask: vi.fn().mockResolvedValue(success),
      takeResults: vi.fn().mockRejectedValue(
        new TaskCallDebugError({
          kind: "http",
          status: 403,
          message: "访问被拒绝，请刷新页面或重新登录。"
        })
      )
    };
    const store = createTaskCallDebugStore(client, "api");
    const denied = await store.takeResults("task", ["message-1"]);
    expect(denied.state).toBe("request_rejected");
    expect(denied.resultTakeError?.rejected).toBe(true);
    expect(denied.results).toBeUndefined();
    expect(client.callTask).not.toHaveBeenCalled();
    const original = await store.send("task", JSON.stringify(request()));
    await store.takeResults("task", original.messageIds, original.localId);
    expect(original.state).toBe("observed");
    expect(original.results).toEqual(success);
    expect(original.resultTakeError?.rejected).toBe(true);
    expect(client.callTask).toHaveBeenCalledTimes(1);
    expect(client.takeResults).toHaveBeenCalledTimes(2);
  });
  it("takes only pending IDs for follow-up and never erases received content with an empty take", async () => {
    const client = {
      callTask: vi
        .fn()
        .mockResolvedValue({ ...success, pending: { status: "not_observed" } }),
      takeResults: vi
        .fn()
        .mockResolvedValueOnce({ pending: { status: "failed" } })
        .mockResolvedValueOnce({
          "message-1": { status: "not_observed" },
          pending: { status: "not_observed" }
        })
    };
    const store = createTaskCallDebugStore(client, "api");
    const record = await store.send(
      "task",
      JSON.stringify({ items: [item(), item("pending")] })
    );
    await store.takeResults("task", record.messageIds, record.localId, true);
    expect(client.takeResults).toHaveBeenLastCalledWith(CALLER, "task", ["pending"]);
    await store.takeResults("task", record.messageIds, record.localId);
    expect(record.results).toEqual({ ...success, pending: { status: "failed" } });
    await store.takeResults("task", record.messageIds, record.localId, true);
    expect(client.takeResults).toHaveBeenCalledTimes(2);
    expect(client.callTask).toHaveBeenCalledTimes(1);
  });
  it("freezes caller and input, blocks double submit and attributes late responses to the original Task", async () => {
    let release!: (value: TaskCallDebugResults) => void;
    const client = {
      callTask: vi.fn(
        () =>
          new Promise<TaskCallDebugResults>((r) => {
            release = r;
          })
      ),
      takeResults: vi.fn().mockResolvedValue(success)
    };
    const store = createTaskCallDebugStore(client, "api");
    store.draft("task").requestText = JSON.stringify(request());
    const pending = store.send("task", store.draft("task").requestText);
    store.draft("task").requestText = "{}";
    await expect(store.send("task", JSON.stringify(request()))).rejects.toThrow(
      "进行中"
    );
    expect(store.clear("task")).toBe(false);
    consoleIdentity.login("1001");
    release(success);
    const record = await pending;
    expect(record.caller).toEqual(CALLER);
    expect(record.request).toEqual(request());
    expect(store.history("other")).toEqual([]);
    await store.takeResults("task", record.messageIds, record.localId);
    expect(client.takeResults).toHaveBeenCalledWith(CALLER, "task", ["message-1"]);
  });
  it("preserves mixed results and last successful read on later read failure", async () => {
    const mixed: TaskCallDebugResults = {
      ...success,
      waiting: { status: "not_observed" },
      failure: { status: "failed" }
    };
    const client = {
      callTask: vi.fn().mockResolvedValue(mixed),
      takeResults: vi.fn().mockRejectedValue(new Error("down"))
    };
    const store = createTaskCallDebugStore(client, "api");
    const record = await store.send(
      "task",
      JSON.stringify({ items: [item(), item("waiting"), item("failure")] })
    );
    const time = record.readAt;
    await store.takeResults("task", record.messageIds, record.localId);
    expect(record.results).toEqual(mixed);
    expect(record.readAt).toBe(time);
    expect(record.resultTakeError).toBeDefined();
    expect(client.callTask).toHaveBeenCalledTimes(1);
  });
  it("keeps uncertain calls and HTTP rejection separate from per-Item failure without automatic resend", async () => {
    const client = {
      callTask: vi
        .fn()
        .mockRejectedValueOnce(
          new TaskCallDebugError({ kind: "network", message: "lost" })
        )
        .mockRejectedValueOnce(
          new TaskCallDebugError({ kind: "http", status: 400, message: "rejected" })
        ),
      takeResults: vi.fn().mockResolvedValue(success)
    };
    const store = createTaskCallDebugStore(client, "api");
    const unknown = await store.send("task", JSON.stringify(request()));
    expect(unknown.state).toBe("unconfirmed");
    expect(unknown.results).toBeUndefined();
    await store.takeResults("task", unknown.messageIds, unknown.localId);
    expect(unknown.results).toEqual(success);
    const rejected = await store.send("task", JSON.stringify(request()));
    expect(rejected.state).toBe("request_rejected");
    await store.takeResults("task", rejected.messageIds, rejected.localId);
    expect(client.callTask).toHaveBeenCalledTimes(2);
  });
  it("supports reads without browser history and bounds requests per Task", async () => {
    const client = {
      callTask: vi.fn().mockResolvedValue(success),
      takeResults: vi.fn().mockResolvedValue(success)
    };
    const store = createTaskCallDebugStore(client, "api");
    const read = await store.takeResults("known", ["message-1"]);
    expect(read.request).toBeUndefined();
    for (let n = 0; n < 22; n++) await store.send("task", JSON.stringify(request()));
    expect(store.history("task")).toHaveLength(TASK_CALL_DEBUG_HISTORY_LIMIT);
    expect(store.history("known")).toHaveLength(1);
    expect(store.clear("task")).toBe(true);
  });
  it("Mock cannot call or read through the network client", async () => {
    const client = { callTask: vi.fn(), takeResults: vi.fn() };
    const store = createTaskCallDebugStore(client, "mock");
    await expect(store.send("task", JSON.stringify(request()))).rejects.toThrow("Mock");
    await expect(store.takeResults("task", ["message-1"])).rejects.toThrow("Mock");
    expect(client.callTask).not.toHaveBeenCalled();
    expect(client.takeResults).not.toHaveBeenCalled();
  });
});
