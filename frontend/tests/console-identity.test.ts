import { describe, expect, it } from "vitest";
import {
  createConsoleIdentity,
  identityRouteAllowed,
  identityStorageKey
} from "../src/console-identity";

describe("development identity context", () => {
  it("restores only a known user ID and gets its role from the fixed definitions", () => {
    const values = new Map<string, string>();
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => {
        values.set(key, value);
      }
    };
    const first = createConsoleIdentity(storage);
    expect(first.current.value.role).toBe("admin");
    first.select("1001");
    expect([...values.entries()]).toEqual([[identityStorageKey, "1001"]]);
    expect(createConsoleIdentity(storage).current.value).toMatchObject({
      userId: "1001",
      role: "operator"
    });
    values.set(identityStorageKey, '{"userId":"1001","role":"admin"}');
    expect(createConsoleIdentity(storage).current.value.userId).toBe("1000");
  });
  it("keeps working without browser storage and limits operator SPA routes", () => {
    const identity = createConsoleIdentity({
      getItem() {
        throw new Error("blocked");
      },
      setItem() {
        throw new Error("blocked");
      }
    });
    identity.select("1002");
    expect(identity.current.value.userId).toBe("1002");
    for (const path of [
      "/runtime/tasks",
      "/runtime/workers",
      "/messages",
      "/sms",
      "/task-workbench",
      "/api-reference"
    ])
      expect(identityRouteAllowed(identity.current.value, { path, query: {} })).toBe(
        false
      );
    expect(
      identityRouteAllowed(identity.current.value, {
        path: "/app-checks",
        query: { view: "configuration" }
      })
    ).toBe(false);
    expect(
      identityRouteAllowed(identity.current.value, {
        path: "/app-checks/tasks/known",
        query: { view: "create" }
      })
    ).toBe(true);
    identity.select("1000");
    expect(
      identityRouteAllowed(identity.current.value, {
        path: "/app-checks",
        query: { view: "configuration" }
      })
    ).toBe(true);
  });
});
