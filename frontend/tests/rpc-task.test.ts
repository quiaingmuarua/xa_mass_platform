import { consoleIdentity } from "@/console-identity";
import { beforeEach, expect, it, vi } from "vitest";
import type { AxiosInstance } from "axios";
import { ADMIN as CALLER, devKey } from "./identity-fixture";
import { rpcChannelSlotKey } from "@/task-workbench/rpc-task";
import { HttpTaskCallDebugClient } from "@/task-call-debug/http-client";
import { createTaskSession } from "@/task-workbench/session";
import type { ProjectSource } from "@/task-management/projects";

beforeEach(() => consoleIdentity.login(devKey("1000")));

it("builds canonical UI slot keys without minting external Task IDs", () => {
  expect(rpcChannelSlotKey("default", " alice ", "g")).toBe("default/alice/g");
  expect(rpcChannelSlotKey("a.b", "abcd", "d")).not.toBe(
    rpcChannelSlotKey("a", "abcd", "b.d")
  );
  for (const value of [" ", "用户.甲", "Alice", "0001", "abc", "a".repeat(10)])
    expect(() => rpcChannelSlotKey("p", value, "g")).toThrow();
});

it("provisions RPC channels via POST /v1/tasks/rpc and returns the Server-minted taskId", async () => {
  const post = vi
    .fn()
    .mockResolvedValueOnce({ status: 200, data: { taskId: "rpc-0123456789abcdef0123456789abcdef" } })
    .mockResolvedValueOnce({ status: 200, data: { taskId: "   " } });
  const client = new HttpTaskCallDebugClient("/api", {
    post
  } as unknown as AxiosInstance);
  const request = {
    projectId: "default",
    workerGroupId: "g",
    ownerUserId: "alice",
    metadata: { label: "alice" },
    refill: [{ poolName: "country", target: { country: ["CN"] }, count: 100 }]
  };
  await expect(client.prepareTask(CALLER, request)).resolves.toBe(
    "rpc-0123456789abcdef0123456789abcdef"
  );
  expect(post).toHaveBeenCalledWith(
    "/v1/tasks/rpc",
    request,
    expect.objectContaining({ timeout: 5000 })
  );
  await expect(client.prepareTask(CALLER, request)).rejects.toMatchObject({
    kind: "schema"
  });
});

it("retains the frozen request through uncertainty without automatic retries or duplicate admission", async () => {
  let finish!: (taskId: string) => void;
  const client = {
    prepareTask: vi
      .fn()
      .mockRejectedValueOnce(new Error("lost response"))
      .mockImplementationOnce(
        () =>
          new Promise<string>((resolve) => {
            finish = resolve;
          })
      )
  };
  const session = createTaskSession({} as ProjectSource, client, "api");
  const request = {
    projectId: "default",
    workerGroupId: "g",
    metadata: { label: "alice" },
    refill: [{ poolName: "country", target: { country: ["CN"] }, count: 100 }]
  };
  await session.prepareRpc("default/1000/g", request);
  request.metadata.label = "changed";
  request.refill[0]!.target.country[0] = "US";
  expect(client.prepareTask).toHaveBeenCalledTimes(1);
  expect(session.rpcPreparations["default/1000/g"]?.outcome).toBe("unconfirmed");
  const retry = session.prepareRpc("default/1000/g", request);
  await session.prepareRpc("default/1000/g", request);
  expect(client.prepareTask).toHaveBeenCalledTimes(2);
  expect(client.prepareTask.mock.calls[1]).toEqual([
    CALLER,
    {
      ...request,
      metadata: { label: "alice" },
      refill: [{ poolName: "country", target: { country: ["CN"] }, count: 100 }]
    }
  ]);
  finish("rpc-minted-1");
  await retry;
  expect(session.rpcPreparations["default/1000/g"]?.outcome).toBe("ready");
  expect(session.rpcPreparations["default/1000/g"]?.taskId).toBe("rpc-minted-1");
  const mock = createTaskSession({} as ProjectSource, client, "mock");
  await mock.prepareRpc("default/1000/mock", request);
  expect(client.prepareTask).toHaveBeenCalledTimes(2);
});
