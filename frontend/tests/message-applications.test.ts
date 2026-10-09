import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiMessageTaskSource } from "../src/message-campaigns/api-task-source";
import {
  apiApplications,
  applicationLabel,
  actionReason,
  resultKind
} from "../src/message-campaigns/workbench";
import { loadCatalog, type Catalog } from "../src/message-campaigns/api";
import type {
  CreateMessageTask,
  MessageTask
} from "../src/message-campaigns/task-source";
const catalog: Catalog = {
  projectId: "messages",
  version: "0.3.0-preview",
  applications: [
    { id: "demo", label: "Demo", workerGroupId: "demo-sim" },
    { id: "app-a", label: "App A", workerGroupId: "app-a-sim" },
    { id: "app-b", label: "App B", workerGroupId: "app-b-sim" }
  ],
  countries: ["CN", "US", "GB"].map((id) => ({ id })) as Catalog["countries"],
  limits: { recipientsPerImport: 100000, importFileBytes: 10485760 }
};
afterEach(() => vi.unstubAllGlobals());
describe("Messages application boundary", () => {
  it("uses ordered Catalog applications without deriving identities for historical Tasks", () => {
    expect(apiApplications(catalog)).toEqual(catalog.applications);
    expect(apiApplications()).toEqual([]);
    expect(
      applicationLabel(
        { appId: "app-a", workerGroupId: "app-a-sim" } as MessageTask,
        catalog.applications
      )
    ).toBe("App A");
    expect(
      applicationLabel(
        { workerGroupId: "app-a-sim" } as MessageTask,
        catalog.applications
      )
    ).toBe("app-a-sim");
    expect(
      applicationLabel(
        { appId: "app-a", workerGroupId: "old-group" } as MessageTask,
        catalog.applications
      )
    ).toBe("old-group");
  });
  it("rejects ambiguous or missing Catalog application evidence", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    for (const applications of [
      undefined,
      [],
      [catalog.applications[0], catalog.applications[0]],
      [
        catalog.applications[0],
        { ...catalog.applications[1], workerGroupId: "demo-sim" }
      ]
    ]) {
      fetcher.mockResolvedValueOnce(
        new Response(JSON.stringify({ ...catalog, applications }), { status: 200 })
      );
      await expect(loadCatalog()).rejects.toThrow();
    }
  });
  it("sends the selected app while excluding senderPhone and client Group overrides", async () => {
    const fetcher = vi.fn(
      async (_url: string, _options?: RequestInit) =>
        new Response(JSON.stringify({ taskId: "known" }), { status: 201 })
    );
    vi.stubGlobal("fetch", fetcher);
    const source = new ApiMessageTaskSource(() => catalog);
    const input: CreateMessageTask = {
      appId: "demo",
      requestId: "id",
      name: "name",
      recipientCountry: "CN",
      senderCountry: null,
      body: "{}"
    };
    await expect(source.createTask({ ...input, appId: "unknown" })).rejects.toThrow(
      "应用配置不可用"
    );
    expect(fetcher).not.toHaveBeenCalled();
    await source.createTask({
      ...input,
      appId: "app-b",
      senderPhone: "hidden",
      workerGroupId: "wrong"
    } as CreateMessageTask);
    expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body))).toEqual({
      appId: "app-b",
      requestId: "id",
      name: "name",
      recipientCountry: "CN",
      senderCountry: null,
      body: "{}"
    });
  });
  it("keeps old input restrictions and separates malformed results from delivery", () => {
    const task = {
      taskId: "old",
      managed: false,
      body: "{}",
      recipientCountry: "CN",
      state: "pre_review",
      inputVersion: "1",
      sendTotal: 2
    } as MessageTask;
    expect(actionReason(task, "approve", false)).toContain("旧输入版本");
    expect(actionReason(task, "close", false)).toBe("");
    expect(actionReason({ ...task, managed: true }, "close", false)).toContain(
      "不可用"
    );
    expect(
      resultKind({
        messageId: "x",
        resultStatus: "succeeded",
        status: "REPLIED",
        contentError: "bad"
      })
    ).toBe("invalid");
    expect(resultKind({ messageId: "x", resultStatus: "failed" })).toBe(
      "EXECUTION_FAILED"
    );
    expect(resultKind({ messageId: "x", resultStatus: "succeeded" })).toBe("unknown");
  });
});
