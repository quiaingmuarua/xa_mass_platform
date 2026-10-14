import { afterEach, describe, expect, it } from "vitest";
import {
  createConsoleIdentity,
  consoleIdentity,
  identityStorageKey
} from "@/console-identity";
import { can, canAccessTask, roles } from "@/access-policy";
import { developmentUsers } from "@/development-users";
import { captureTaskCaller, callerHeaders, selectTaskUser } from "@/task-caller";
import { ADMIN, OPERATOR_A } from "./identity-fixture";

afterEach(() => consoleIdentity.logout());
function memoryStorage() {
  const values = new Map<string, string>();
  return {
    values,
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value);
    },
    removeItem: (key: string) => {
      values.delete(key);
    }
  };
}
describe("console login and role policy", () => {
  it("starts signed out and never treats an old development selection as login", () => {
    const storage = memoryStorage();
    storage.setItem("xa-mass.development-user", "1000");
    const identity = createConsoleIdentity(storage);
    expect(identity.current.value).toBeNull();
    expect(() => identity.requireCurrent()).toThrow("请先登录");
    expect(() => captureTaskCaller()).toThrow("请先登录");
  });
  it("persists only a known user ID, resolves its role from data and clears invalid users", () => {
    const storage = memoryStorage();
    const identity = createConsoleIdentity(storage);
    identity.login("1001");
    expect([...storage.values.entries()]).toEqual([[identityStorageKey, "1001"]]);
    expect(createConsoleIdentity(storage).current.value).toMatchObject(OPERATOR_A);
    identity.logout();
    expect(createConsoleIdentity(storage).current.value).toBeNull();
    for (const invalid of ["removed", '{"userId":"1001","role":"admin"}']) {
      storage.setItem(identityStorageKey, invalid);
      expect(createConsoleIdentity(storage).current.value).toBeNull();
      expect(storage.getItem(identityStorageKey)).toBeNull();
    }
    expect(() => identity.login("removed")).toThrow("有效");
  });
  it("supports login/logout without storage and distinguishes consecutive logins as the same user", () => {
    const identity = createConsoleIdentity({
      getItem() {
        throw new Error();
      },
      setItem() {
        throw new Error();
      },
      removeItem() {
        throw new Error();
      }
    });
    identity.login("1000");
    const first = identity.current.value;
    identity.logout();
    expect(identity.current.value).toBeNull();
    identity.login("1000");
    expect(identity.current.value).toEqual(first);
    expect(identity.current.value).not.toBe(first);
  });
  it("accepts additional users through data with the same role permissions", () => {
    const identity = createConsoleIdentity(undefined, [
      ...developmentUsers,
      { userId: "1003", displayName: "用户 C", role: "operator" },
      { userId: "1004", displayName: "管理员 D", role: "admin" }
    ]);
    identity.login("1003");
    expect(can(identity.requireCurrent(), "app-checks.tasks")).toBe(true);
    expect(can(identity.requireCurrent(), "messages.use")).toBe(false);
    expect(canAccessTask(identity.requireCurrent(), { userId: "1003" })).toBe(true);
    expect(canAccessTask(identity.requireCurrent(), { userId: "1001" })).toBe(false);
    identity.login("1004");
    expect(can(identity.requireCurrent(), "messages.use")).toBe(true);
    expect(canAccessTask(identity.requireCurrent(), { userId: "1003" })).toBe(true);
    expect(roles[identity.requireCurrent().role].home).toBe("/runtime/workers");
  });
  it("keeps explicit caller headers and shared Mock ownership selection", () => {
    expect(callerHeaders(OPERATOR_A)).toEqual({
      "X-XA-Dev-User": "MTAwMQ",
      "X-XA-Dev-Role": "operator"
    });
    expect(selectTaskUser(OPERATOR_A)).toBe("1001");
    expect(selectTaskUser(OPERATOR_A, "1001")).toBe("1001");
    expect(() => selectTaskUser(OPERATOR_A, "1002")).toThrow();
    expect(selectTaskUser(ADMIN)).toBeUndefined();
    expect(selectTaskUser(ADMIN, "1002")).toBe("1002");
  });
});
