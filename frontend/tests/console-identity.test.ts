import { afterEach, describe, expect, it } from "vitest";
import {
  createConsoleIdentity,
  consoleIdentity,
  identityStorageKey
} from "@/console-identity";
import { canUseConsole, canAccessTask, consoleHome, roles } from "@/access-policy";
import { developmentUsers } from "@/development-users";
import { callerHeaders, selectTaskUser } from "@/task-caller";
import { captureTaskCaller } from "@/console-identity";
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
      {
        userId: "1003",
        displayName: "用户 C",
        role: "operator",
        projectIds: ["app-checks"]
      },
      { userId: "1004", displayName: "管理员 D", role: "admin", projectIds: [] }
    ]);
    identity.login("1003");
    expect(canUseConsole(identity.requireCurrent(), "app-checks.tasks")).toBe(true);
    expect(canUseConsole(identity.requireCurrent(), "messages.use")).toBe(false);
    expect(canAccessTask(identity.requireCurrent(), { userId: "1003" })).toBe(true);
    expect(canAccessTask(identity.requireCurrent(), { userId: "1001" })).toBe(false);
    identity.login("1004");
    expect(canUseConsole(identity.requireCurrent(), "messages.use")).toBe(true);
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
  it("keeps Mock users across logout and resets only when the Mock source is recreated", async () => {
    const storage = memoryStorage();
    const identity = createConsoleIdentity(storage);
    const user = {
      userId: "userc",
      displayName: "用户 C",
      role: "operator" as const,
      projectIds: ["messages"]
    };
    await expect(identity.saveUser(user)).rejects.toThrow("用户管理权限");
    identity.login("1000");
    await identity.saveUser(user);
    await expect(identity.saveUser(user)).rejects.toThrow("已存在");
    user.projectIds.push("app-checks");
    identity.logout();
    identity.login("userc");
    expect(identity.requireCurrent().projectIds).toEqual(["messages"]);
    expect(consoleHome(identity.requireCurrent())).toBe("/messages");
    expect(canUseConsole(identity.requireCurrent(), "messages.use")).toBe(true);
    expect(canUseConsole(identity.requireCurrent(), "app-checks.tasks")).toBe(false);
    expect(canUseConsole(identity.requireCurrent(), "users.manage")).toBe(false);
    await expect(identity.saveUser(user)).rejects.toThrow("用户管理权限");
    expect([...storage.values.keys()]).toEqual([identityStorageKey]);
    const reloaded = createConsoleIdentity(storage);
    expect(reloaded.users.some((entry) => entry.userId === "userc")).toBe(false);
    expect(reloaded.current.value).toBeNull();
  });
  it("edits role and project grants for the next login without changing caller encoding", async () => {
    const identity = createConsoleIdentity();
    identity.login("1000");
    const user = {
      userId: "1001",
      displayName: "用户 A",
      role: "operator" as const,
      projectIds: []
    };
    await identity.saveUser(user, "1001");
    identity.login("1001");
    expect(consoleHome(identity.requireCurrent())).toBe("/?view=home");
    expect(callerHeaders(identity.requireCurrent())).toEqual(callerHeaders(OPERATOR_A));
    identity.login("1000");
    await identity.saveUser({ ...user, role: "admin" }, "1001");
    identity.login("1001");
    expect(consoleHome(identity.requireCurrent())).toBe("/runtime/workers");
    expect(canUseConsole(identity.requireCurrent(), "users.manage")).toBe(true);
    await expect(
      identity.saveUser({ ...user, role: "operator" }, "1001")
    ).rejects.toThrow("当前登录用户");
    await expect(identity.saveUser({ ...user, userId: "INVALID" })).rejects.toThrow(
      "userId"
    );
    await expect(
      identity.saveUser({ ...user, userId: "userz" }, "1002")
    ).rejects.toThrow("不能修改");
  });
});
