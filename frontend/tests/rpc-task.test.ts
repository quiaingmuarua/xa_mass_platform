import { expect, it, vi } from "vitest";
import type { AxiosInstance } from "axios";
import { rpcTaskId } from "@/task-workbench/rpc-task";
import { HttpTaskCallDebugClient } from "@/task-call-debug/http-client";
import { createTaskSession } from "@/task-workbench/session";
import type { ProjectSource } from "@/task-management/projects";

it("mints stable UTF-8 segments with canonical Base36 caller attribution", () => {
  expect(rpcTaskId("default", " alice ", "g")).toBe(
    "rpc-workbench-v1.ZGVmYXVsdA.YWxpY2U.Zw"
  );
  expect(rpcTaskId("a.b", "abcd", "d")).not.toBe(rpcTaskId("a", "abcd", "b.d"));
  for (const value of [" ", "用户.甲", "Alice", "0001", "abc", "a".repeat(10)])
    expect(() => rpcTaskId("p", value, "g")).toThrow();
});

it("prepares only the requested identity and rejects a mismatched success response", async () => {
  const post = vi
    .fn()
    .mockResolvedValueOnce({ status: 200, data: { taskId: "chosen" } })
    .mockResolvedValueOnce({ status: 200, data: { taskId: "different" } });
  const client = new HttpTaskCallDebugClient("/api", {
    post
  } as unknown as AxiosInstance);
  const request = {
    projectId: "default",
    workerGroupId: "g",
    metadata: { userId: "alice" }
  };
  await client.prepareTask("chosen", request);
  expect(post).toHaveBeenCalledWith(
    "/v1/tasks/chosen/rpc:prepare",
    request,
    expect.objectContaining({ timeout: 5000 })
  );
  await expect(client.prepareTask("chosen", request)).rejects.toMatchObject({
    kind: "schema"
  });
});

it("retains the frozen request through uncertainty without automatic retries or duplicate admission", async () => {
  let finish!: () => void;
  const client = {
    prepareTask: vi
      .fn()
      .mockRejectedValueOnce(new Error("lost response"))
      .mockImplementationOnce(
        () =>
          new Promise<void>((resolve) => {
            finish = resolve;
          })
      )
  };
  const session = createTaskSession({} as ProjectSource, client, "api");
  const request = {
    projectId: "default",
    workerGroupId: "g",
    metadata: { userId: "alice" }
  };
  await session.prepareRpc("original", request);
  request.metadata.userId = "changed";
  expect(client.prepareTask).toHaveBeenCalledTimes(1);
  expect(session.rpcPreparations.original?.outcome).toBe("unconfirmed");
  const retry = session.prepareRpc("original", request);
  await session.prepareRpc("original", request);
  expect(client.prepareTask).toHaveBeenCalledTimes(2);
  expect(client.prepareTask.mock.calls[1]).toEqual([
    "original",
    { ...request, metadata: { userId: "alice" } }
  ]);
  finish();
  await retry;
  expect(session.rpcPreparations.original?.outcome).toBe("ready");
  const mock = createTaskSession({} as ProjectSource, client, "mock");
  await mock.prepareRpc("mock", request);
  expect(client.prepareTask).toHaveBeenCalledTimes(2);
});
