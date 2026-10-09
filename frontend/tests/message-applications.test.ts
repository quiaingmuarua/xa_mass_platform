import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiMessageTaskSource } from "../src/message-campaigns/api-task-source";
import {
  apiApplications,
  actionReason,
  resultKind
} from "../src/message-campaigns/workbench";
import type { Catalog } from "../src/message-campaigns/api";
import type {
  CreateMessageTask,
  MessageTask
} from "../src/message-campaigns/task-source";
const catalog: Catalog = {
  projectId: "messages",
  version: "0.2.0-preview",
  countries: ["CN", "US", "GB"].map((id) => ({
    id,
    workerGroupId: "demo-sim"
  })) as Catalog["countries"],
  limits: { recipientsPerImport: 100000, importFileBytes: 10485760 }
};
afterEach(() => vi.unstubAllGlobals());
describe("Messages application boundary", () => {
  it("derives one API application, and rejects ambiguous or absent Group evidence", () => {
    expect(apiApplications(catalog)).toEqual([
      { id: "demo", label: "Demo", workerGroupId: "demo-sim" }
    ]);
    expect(apiApplications()).toEqual([]);
    expect(
      apiApplications({
        ...catalog,
        countries: catalog.countries.map((entry, i) => ({
          ...entry,
          workerGroupId: i ? "demo-sim" : "other"
        }))
      })
    ).toEqual([]);
    expect(
      apiApplications({
        ...catalog,
        countries: catalog.countries.map((entry) => ({
          ...entry,
          workerGroupId: "external-group"
        }))
      })[0].label
    ).toBe("external-group");
  });
  it("serializes the unchanged wire contract and cannot silently route a Mock app to the API Group", async () => {
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
    await expect(source.createTask({ ...input, appId: "app-a" })).rejects.toThrow(
      "应用配置不可用"
    );
    expect(fetcher).not.toHaveBeenCalled();
    await source.createTask({
      ...input,
      senderPhone: "hidden",
      workerGroupId: "wrong"
    } as CreateMessageTask);
    expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body))).toEqual({
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
