import { TaskApiError } from "@/task-caller";
import { CALLER } from "./app-check-fixture";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiAppCheckTaskSource,
  AppCheckConfigurationUnconfirmed
} from "../src/app-checks/task-source";
import { MockAppCheckTaskSource } from "../src/app-checks/mock-task-source";

afterEach(() => vi.unstubAllGlobals());
describe("App Checks configuration API boundary", () => {
  it("uses configuration-only GET and revision-checked PUT without changing Catalog", async () => {
    const view = await new MockAppCheckTaskSource().loadConfiguration(CALLER);
    const fetcher = vi
      .fn()
      .mockImplementation(async () => new Response(JSON.stringify(view)));
    vi.stubGlobal("fetch", fetcher);
    const source = new ApiAppCheckTaskSource();
    expect(await source.loadConfiguration(CALLER)).toEqual(view);
    expect(await source.saveConfiguration(CALLER, 1, view.saved.configuration)).toEqual(
      view
    );
    expect(fetcher.mock.calls[0][0]).toBe("/api/v1/app-checks/configuration");
    expect(fetcher.mock.calls[1]).toEqual([
      "/api/v1/app-checks/configuration",
      {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          "X-XA-Dev-User": "cHJvb2Y",
          "X-XA-Dev-Role": "admin"
        },
        body: JSON.stringify({
          expectedRevision: 1,
          configuration: view.saved.configuration
        })
      }
    ]);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it.each([400, 403, 409, 503])(
    "preserves the %s save classification without retries",
    async (status) => {
      const view = await new MockAppCheckTaskSource().loadConfiguration(CALLER);
      const fetcher = vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify({ message: "rejected" }), { status })
        );
      vi.stubGlobal("fetch", fetcher);
      await expect(
        new ApiAppCheckTaskSource().saveConfiguration(
          CALLER,
          1,
          view.saved.configuration
        )
      ).rejects.toBeInstanceOf(
        status === 503 ? AppCheckConfigurationUnconfirmed : TaskApiError
      );
      expect(fetcher).toHaveBeenCalledTimes(1);
    }
  );
  it("treats a malformed successful save response as unknown, and never fills missing reads from Mock", async () => {
    const view = await new MockAppCheckTaskSource().loadConfiguration(CALLER);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(async () => new Response("{}"))
    );
    const source = new ApiAppCheckTaskSource();
    await expect(
      source.saveConfiguration(CALLER, 1, view.saved.configuration)
    ).rejects.toBeInstanceOf(AppCheckConfigurationUnconfirmed);
    await expect(source.loadConfiguration(CALLER)).rejects.toThrow();
  });
});
