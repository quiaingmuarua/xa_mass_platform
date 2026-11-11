import { afterEach, describe, expect, it } from "vitest";
import {
  accessKeyStorageKey,
  createConsoleIdentity,
  consoleIdentity
} from "@/console-identity";
import { canUseConsole, canAccessTask, consoleHome, roles } from "@/access-policy";
import { developmentUsers } from "@/development-users";
import { callerHeaders, selectTaskUser } from "@/task-caller";
import { captureTaskCaller } from "@/console-identity";
import { ADMIN, OPERATOR_A, devKey } from "./identity-fixture";

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
describe("console access-key login and role policy", () => {
  it("starts signed out and never treats an old user selection as login", async () => {
    const storage = memoryStorage();
    storage.setItem("xa-mass.console-user", "1000");
    const identity = createConsoleIdentity(storage);
    await identity.load();
    expect(identity.current.value).toBeNull();
    expect(() => identity.requireCurrent()).toThrow("请先登录");
    expect(() => captureTaskCaller()).toThrow("请先登录");
  });
  it("persists only the access key and resolves user and role from the directory", async () => {
    const storage = memoryStorage();
    const identity = createConsoleIdentity(storage);
    await identity.login(devKey("1001"));
    expect([...storage.values.entries()]).toEqual([
      [accessKeyStorageKey, devKey("1001")]
    ]);
    const restored = createConsoleIdentity(storage);
    expect(restored.current.value).toBeNull();
    await restored.load();
    expect(restored.current.value).toMatchObject(OPERATOR_A);
    identity.logout();
    expect(storage.getItem(accessKeyStorageKey)).toBeNull();
    for (const invalid of [
      "1001",
      "xak_dev_removed",
      '{"userId":"1001","role":"admin"}'
    ]) {
      storage.setItem(accessKeyStorageKey, invalid);
      const candidate = createConsoleIdentity(storage);
      await candidate.load();
      expect(candidate.current.value).toBeNull();
      expect(storage.getItem(accessKeyStorageKey)).toBeNull();
    }
    await expect(identity.login("1000")).rejects.toThrow("xak_");
    await expect(identity.login("xak_dev_removed")).rejects.toThrow("无效");
  });
  it("supports login/logout without storage and distinguishes consecutive logins as the same user", async () => {
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
    await identity.login(devKey("1000"));
    const first = identity.current.value;
    const generation = identity.generation.value;
    identity.logout();
    expect(identity.current.value).toBeNull();
    expect(identity.generation.value).toBe(generation + 1);
    await identity.login(` ${devKey("1000")} `);
    expect(identity.current.value).toEqual(first);
    expect(identity.current.value).not.toBe(first);
  });
  it("accepts additional users through data with the same role permissions", async () => {
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
    await identity.login(devKey("1003"));
    expect(canUseConsole(identity.requireCurrent(), "app-checks.tasks")).toBe(true);
    expect(canUseConsole(identity.requireCurrent(), "messages.use")).toBe(false);
    expect(canAccessTask(identity.requireCurrent(), { userId: "1003" })).toBe(true);
    expect(canAccessTask(identity.requireCurrent(), { userId: "1001" })).toBe(false);
    await identity.login(devKey("1004"));
    expect(canUseConsole(identity.requireCurrent(), "messages.use")).toBe(true);
    expect(canAccessTask(identity.requireCurrent(), { userId: "1003" })).toBe(true);
    expect(roles[identity.requireCurrent().role].home).toBe("/runtime/workers");
  });
  it("sends each captured login's own key and keeps it out of snapshots", async () => {
    expect(callerHeaders(OPERATOR_A)).toEqual({
      Authorization: `Bearer ${devKey("1001")}`
    });
    expect(() => callerHeaders({ userId: "1001", role: "operator" })).toThrow();
    const identity = createConsoleIdentity();
    await identity.login(devKey("1001"));
    const captured = identity.requireCaller();
    await identity.login(devKey("1000"));
    expect(callerHeaders(captured)).toEqual(callerHeaders(OPERATOR_A));
    expect(JSON.stringify(captured)).not.toContain("xak_");
    expect({ ...captured }).toEqual({ userId: "1001", role: "operator" });
    expect(selectTaskUser(OPERATOR_A)).toBe("1001");
    expect(selectTaskUser(OPERATOR_A, "1001")).toBe("1001");
    expect(() => selectTaskUser(OPERATOR_A, "1002")).toThrow();
    expect(selectTaskUser(ADMIN)).toBeUndefined();
    expect(selectTaskUser(ADMIN, "1002")).toBe("1002");
  });
  it("issues keys once, replaces and clears them, and keeps Mock users across logout", async () => {
    const storage = memoryStorage();
    const identity = createConsoleIdentity(storage);
    const user = {
      userId: "userc",
      displayName: "用户 C",
      role: "operator" as const,
      projectIds: ["messages"]
    };
    await expect(identity.saveUser(user)).rejects.toThrow("用户管理权限");
    await identity.login(devKey("1000"));
    expect((await identity.saveUser(user)).hasAccessKey).toBe(false);
    await expect(identity.saveUser(user)).rejects.toThrow("已存在");
    await expect(identity.issueAccessKey("1000")).rejects.toThrow("当前登录用户");
    const first = await identity.issueAccessKey("userc");
    expect(first).toMatch(/^xak_[A-Za-z0-9_-]{43}$/);
    expect(identity.users.find((entry) => entry.userId === "userc")?.hasAccessKey).toBe(
      true
    );
    expect(JSON.stringify(identity.users)).not.toContain(first);
    const second = await identity.issueAccessKey("userc");
    await expect(createConsoleIdentity().login(first)).rejects.toThrow("无效");
    user.projectIds.push("app-checks");
    identity.logout();
    await expect(identity.login(first)).rejects.toThrow("无效");
    await identity.login(second);
    expect(identity.requireCurrent().projectIds).toEqual(["messages"]);
    expect(consoleHome(identity.requireCurrent())).toBe("/messages");
    expect(canUseConsole(identity.requireCurrent(), "messages.use")).toBe(true);
    expect(canUseConsole(identity.requireCurrent(), "app-checks.tasks")).toBe(false);
    expect(canUseConsole(identity.requireCurrent(), "users.manage")).toBe(false);
    await expect(identity.saveUser(user)).rejects.toThrow("用户管理权限");
    expect([...storage.values.keys()]).toEqual([accessKeyStorageKey]);
    await identity.login(devKey("1000"));
    expect((await identity.clearAccessKey("userc")).hasAccessKey).toBe(false);
    await expect(identity.login(second)).rejects.toThrow("无效");
  });
  it("edits role and project grants for the next login with the same key", async () => {
    const identity = createConsoleIdentity();
    await identity.login(devKey("1000"));
    const user = {
      userId: "1001",
      displayName: "用户 A",
      role: "operator" as const,
      projectIds: []
    };
    await identity.saveUser(user, "1001");
    await identity.login(devKey("1001"));
    expect(consoleHome(identity.requireCurrent())).toBe("/?view=home");
    expect(callerHeaders(identity.requireCaller())).toEqual(callerHeaders(OPERATOR_A));
    await identity.login(devKey("1000"));
    await identity.saveUser({ ...user, role: "admin" }, "1001");
    await identity.login(devKey("1001"));
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
