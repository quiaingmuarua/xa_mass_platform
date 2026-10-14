import { webcrypto } from "node:crypto";
import {
  formatConfigurationDocument,
  settingsIdentity,
  mockConfigurationFingerprint
} from "../src/app-checks/settings";
import { MockUserDirectorySource } from "@/user-directory-source";
import { TaskApiError } from "@/task-caller";
import { CALLER } from "./app-check-fixture";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiAppCheckTaskSource,
  AppCheckConfigurationUnconfirmed
} from "../src/app-checks/task-source";
import { MockAppCheckTaskSource } from "../src/app-checks/mock-task-source";

beforeEach(() => vi.stubGlobal("crypto", webcrypto));
afterEach(() => vi.unstubAllGlobals());
describe("App Checks configuration API boundary", () => {
  it("formats numeric bounds without rewriting bracket text inside protocol strings", () => {
    const value = {
      bounds: [0, 500],
      text: "[1,2]",
      escaped: 'quote "\\n[3,4]',
      nested: { body: "模板 [5,6] " }
    };
    const formatted = formatConfigurationDocument(value);
    expect(formatted).toContain("[0, 500]");
    expect(JSON.parse(formatted)).toEqual(value);
  });
  it("uses configuration-only GET and fingerprint-checked PUT without changing Catalog", async () => {
    const view = await new MockAppCheckTaskSource(
      new MockUserDirectorySource()
    ).loadConfiguration(CALLER);
    const fetcher = vi
      .fn()
      .mockImplementation(async () => new Response(JSON.stringify(view)));
    vi.stubGlobal("fetch", fetcher);
    const source = new ApiAppCheckTaskSource();
    expect(await source.loadConfiguration(CALLER)).toEqual(view);
    expect(
      await source.saveConfiguration(
        CALLER,
        view.saved.fingerprint,
        view.saved.configuration
      )
    ).toEqual(view);
    expect(fetcher.mock.calls[0][0]).toBe("/api/v1/app-checks/configuration");
    expect(fetcher.mock.calls[1]).toEqual([
      "/api/v1/app-checks/configuration",
      {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          "X-XA-Dev-User": "MTAwMA",
          "X-XA-Dev-Role": "admin"
        },
        body: JSON.stringify({
          expectedFingerprint: view.saved.fingerprint,
          configuration: view.saved.configuration
        })
      }
    ]);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it.each([400, 403, 409, 503])(
    "preserves the %s save classification without retries",
    async (status) => {
      const view = await new MockAppCheckTaskSource(
        new MockUserDirectorySource()
      ).loadConfiguration(CALLER);
      const fetcher = vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify({ message: "rejected" }), { status })
        );
      vi.stubGlobal("fetch", fetcher);
      await expect(
        new ApiAppCheckTaskSource().saveConfiguration(
          CALLER,
          view.saved.fingerprint,
          view.saved.configuration
        )
      ).rejects.toBeInstanceOf(
        status === 503 ? AppCheckConfigurationUnconfirmed : TaskApiError
      );
      expect(fetcher).toHaveBeenCalledTimes(1);
    }
  );
  it("treats a malformed successful save response as unknown, and never fills missing reads from Mock", async () => {
    const view = await new MockAppCheckTaskSource(
      new MockUserDirectorySource()
    ).loadConfiguration(CALLER);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(async () => new Response("{}"))
    );
    const source = new ApiAppCheckTaskSource();
    await expect(
      source.saveConfiguration(CALLER, view.saved.fingerprint, view.saved.configuration)
    ).rejects.toBeInstanceOf(AppCheckConfigurationUnconfirmed);
    await expect(source.loadConfiguration(CALLER)).rejects.toThrow();
  });
});

describe("Mock configuration content guard", () => {
  it("ignores object ordering, freezes effective content, and restores the same fingerprint when content returns", async () => {
    const source = new MockAppCheckTaskSource(new MockUserDirectorySource());
    const original = (await source.loadConfiguration(CALLER)).saved;
    const reordered = JSON.parse(settingsIdentity(original.configuration));
    reordered.applications = Object.fromEntries(
      Object.entries(reordered.applications).reverse()
    );
    expect(
      (await source.saveConfiguration(CALLER, original.fingerprint, reordered)).saved
        .fingerprint
    ).toBe(original.fingerprint);
    expect((await source.catalog()).apps.map((app) => app.appId)).toEqual([
      "app-a",
      "app-b"
    ]);
    reordered.applications["app-a"].label = "Renamed";
    const changed = await source.saveConfiguration(
      CALLER,
      original.fingerprint,
      reordered
    );
    expect(changed.saved.fingerprint).not.toBe(original.fingerprint);
    expect(changed.effective.fingerprint).toBe(original.fingerprint);
    const reverted = await source.saveConfiguration(
      CALLER,
      changed.saved.fingerprint,
      original.configuration
    );
    expect(reverted.saved.fingerprint).toBe(original.fingerprint);
    expect(reverted.restartRequired).toBe(false);
  });
  it("rejects a concurrent editor rather than overwriting the first save during async hashing", async () => {
    const source = new MockAppCheckTaskSource(new MockUserDirectorySource());
    const original = (await source.loadConfiguration(CALLER)).saved;
    const a = structuredClone(original.configuration),
      b = structuredClone(original.configuration);
    a.applications["app-a"].label = "First";
    b.applications["app-b"].label = "Second";
    const results = await Promise.allSettled([
      source.saveConfiguration(CALLER, original.fingerprint, a),
      source.saveConfiguration(CALLER, original.fingerprint, b)
    ]);
    expect(results.filter((result) => result.status === "fulfilled")).toHaveLength(1);
    expect(results.filter((result) => result.status === "rejected")).toMatchObject([
      { reason: { status: 409 } }
    ]);
    expect((await source.loadConfiguration(CALLER)).saved.fingerprint).toBe(
      await mockConfigurationFingerprint(results[0].status === "fulfilled" ? a : b)
    );
  });
});
