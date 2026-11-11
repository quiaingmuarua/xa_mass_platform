import { afterEach, expect, it, vi } from "vitest";
import {
  ApiUserDirectorySource,
  MockUserDirectorySource,
  type UserDirectory,
  type UserDirectorySource
} from "@/user-directory-source";
import { accessKeyStorageKey, createConsoleIdentity } from "@/console-identity";
import { developmentRoles, developmentUsers } from "@/development-users";
import { TaskApiError } from "@/task-caller";
import { ADMIN, devKey } from "./identity-fixture";

afterEach(() => vi.unstubAllGlobals());
it("a stale directory read cannot remove a just-confirmed user", async () => {
  const mock = new MockUserDirectorySource();
  let resolve!: (value: UserDirectory) => void;
  let hold = false;
  const initial = await mock.load(devKey("1000"));
  const source: UserDirectorySource = {
    authenticate: (key) => mock.authenticate(key),
    load: (key) =>
      hold
        ? new Promise((done) => {
            resolve = done;
          })
        : mock.load(key),
    save: async (_caller, input) => ({ ...input, hasAccessKey: false }),
    issueKey: (caller, userId) => mock.issueKey(caller, userId),
    clearKey: (caller, userId) => mock.clearKey(caller, userId)
  };
  const identity = createConsoleIdentity(undefined, [], source);
  await identity.login(devKey("1000"));
  hold = true;
  const read = identity.load(true);
  await identity.saveUser({
    userId: "userc",
    displayName: "C",
    role: "operator",
    projectIds: ["messages"]
  });
  await vi.waitFor(() => expect(resolve).toBeTypeOf("function"));
  resolve(initial);
  await read;
  expect(identity.users.some((entry) => entry.userId === "userc")).toBe(true);
});
function storage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value);
    },
    removeItem: (key: string) => {
      values.delete(key);
    }
  };
}
it("independent API clients authenticate by key and read the shared Server directory", async () => {
  const keys = new Map(
    developmentUsers.map((entry) => [devKey(entry.userId), entry.userId])
  );
  let users = developmentUsers.map((entry) => ({
    ...entry,
    projectIds: [...entry.projectIds],
    hasAccessKey: true
  }));
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    const headers = (init?.headers ?? {}) as Record<string, string>;
    const actor = keys.get(String(headers.Authorization).replace(/^Bearer /, ""));
    if (!actor) return Response.json({ code: 18008 }, { status: 401 });
    if (url.endsWith("/access-key") && init?.method === "POST") {
      const userId = url.split("/").at(-2)!;
      const accessKey = "xak_" + "k".repeat(43);
      keys.set(accessKey, userId);
      users = users.map((entry) =>
        entry.userId === userId ? { ...entry, hasAccessKey: true } : entry
      );
      return Response.json({ userId, accessKey });
    }
    if (init?.method === "POST") {
      const user = { ...JSON.parse(String(init.body)), hasAccessKey: false };
      users.push(user);
      return Response.json(user);
    }
    if (init?.method === "PUT") {
      const userId = url.split("/").at(-1);
      const changed = { userId, ...JSON.parse(String(init.body)), hasAccessKey: true };
      users = users.map((entry) => (entry.userId === userId ? changed : entry));
      return Response.json(changed);
    }
    if (url.endsWith("/me")) {
      const user = users.find((entry) => entry.userId === actor)!;
      const role = developmentRoles.find((entry) => entry.id === user.role)!;
      return Response.json({
        userId: user.userId,
        displayName: user.displayName,
        role,
        projectIds: user.projectIds
      });
    }
    return Response.json(url.endsWith("roles") ? developmentRoles : users);
  });
  vi.stubGlobal("fetch", fetcher);
  const first = createConsoleIdentity(undefined, [], new ApiUserDirectorySource());
  expect(first.users).toEqual([]);
  expect(await first.load()).toBe(true);
  expect(first.current.value).toBeNull();
  await first.login(devKey("1000"));
  const user = {
    userId: "userc",
    displayName: "C",
    role: "operator" as const,
    projectIds: ["messages"]
  };
  await first.saveUser(user);
  const key = await first.issueAccessKey("userc");
  const tabStorage = storage();
  const second = createConsoleIdentity(tabStorage, [], new ApiUserDirectorySource());
  await second.login(key);
  expect(tabStorage.getItem(accessKeyStorageKey)).toBe(key);
  const refreshed = createConsoleIdentity(tabStorage, [], new ApiUserDirectorySource());
  await refreshed.load();
  expect(refreshed.requireCurrent()).toEqual(user);
  await first.saveUser({ ...user, projectIds: ["app-checks"] }, "userc");
  await second.load(true);
  expect(second.requireCurrent().projectIds).toEqual(["messages"]);
  second.logout();
  await second.login(key);
  expect(second.requireCurrent().projectIds).toEqual(["app-checks"]);
  const writes = fetcher.mock.calls.filter(([, init]) => init?.method);
  expect(writes.map(([url, init]) => [url, init!.method])).toEqual([
    ["/api/v1/users", "POST"],
    ["/api/v1/users/userc/access-key", "POST"],
    ["/api/v1/users/userc", "PUT"]
  ]);
  expect(writes[2]![1]!.headers).toMatchObject({
    Authorization: `Bearer ${devKey("1000")}`
  });
  expect(JSON.parse(String(writes[2]![1]!.body))).not.toHaveProperty("userId");
  for (const [, init] of fetcher.mock.calls)
    expect(String(init?.body ?? "")).not.toContain("xak_");
});
it("does not substitute built-in Mock users on API failure and keeps 401 and 403 definite", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response("{}", { status: 503 }))
  );
  const restoring = storage();
  restoring.setItem(accessKeyStorageKey, devKey("1000"));
  const identity = createConsoleIdentity(restoring, [], new ApiUserDirectorySource());
  expect(await identity.load()).toBe(false);
  expect(identity.users).toEqual([]);
  expect(restoring.getItem(accessKeyStorageKey)).toBe(devKey("1000"));
  await expect(identity.login(devKey("1000"))).rejects.toMatchObject({ status: 503 });
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response("{}", { status: 401 }))
  );
  expect(await identity.load(true)).toBe(true);
  expect(identity.current.value).toBeNull();
  expect(restoring.getItem(accessKeyStorageKey)).toBeNull();
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response("{}", { status: 403 }))
  );
  await expect(
    new ApiUserDirectorySource().save(ADMIN, developmentUsers[1]!)
  ).rejects.toMatchObject({ status: 403 });
  await expect(
    new ApiUserDirectorySource().issueKey(ADMIN, "1001")
  ).rejects.toBeInstanceOf(TaskApiError);
});
