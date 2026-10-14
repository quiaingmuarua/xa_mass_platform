import { afterEach, expect, it, vi } from "vitest";
import {
  ApiUserDirectorySource,
  type UserDirectory,
  type UserDirectorySource
} from "@/user-directory-source";
import { createConsoleIdentity, identityStorageKey } from "@/console-identity";
import { developmentRoles, developmentUsers } from "@/development-users";
import { TaskApiError } from "@/task-caller";
import { ADMIN } from "./identity-fixture";

afterEach(() => vi.unstubAllGlobals());
it("a stale directory read cannot remove a just-confirmed user", async () => {
  let resolve!: (value: UserDirectory) => void;
  const initial = { roles: developmentRoles, users: developmentUsers };
  const source: UserDirectorySource = {
    initial,
    load: () =>
      new Promise((done) => {
        resolve = done;
      }),
    save: async (_caller, input) => input
  };
  const identity = createConsoleIdentity(undefined, [], source);
  identity.login("1000");
  const read = identity.load(true);
  await identity.saveUser({
    userId: "userc",
    displayName: "C",
    role: "operator",
    projectIds: ["messages"]
  });
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
it("independent API clients and refreshed identity read the shared Server directory", async () => {
  let users = developmentUsers.map((entry) => ({
    ...entry,
    projectIds: [...entry.projectIds]
  }));
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") {
      const user = JSON.parse(String(init.body));
      users.push(user);
      return Response.json(user);
    }
    if (init?.method === "PUT") {
      const userId = url.split("/").at(-1);
      const changed = { userId, ...JSON.parse(String(init.body)) };
      users = users.map((entry) => (entry.userId === userId ? changed : entry));
      return Response.json(changed);
    }
    return Response.json(url.endsWith("roles") ? developmentRoles : users);
  });
  vi.stubGlobal("fetch", fetcher);
  const first = createConsoleIdentity(undefined, [], new ApiUserDirectorySource());
  expect(first.users).toEqual([]);
  expect(first.current.value).toBeNull();
  await first.load();
  first.login("1000");
  const user = {
    userId: "userc",
    displayName: "C",
    role: "operator" as const,
    projectIds: ["messages"]
  };
  await first.saveUser(user);
  const tabStorage = storage();
  const second = createConsoleIdentity(tabStorage, [], new ApiUserDirectorySource());
  await second.load();
  second.login("userc");
  expect(tabStorage.getItem(identityStorageKey)).toBe("userc");
  const refreshed = createConsoleIdentity(tabStorage, [], new ApiUserDirectorySource());
  await refreshed.load();
  expect(refreshed.requireCurrent()).toEqual(user);
  await first.saveUser({ ...user, projectIds: ["app-checks"] }, "userc");
  await second.load(true);
  second.logout();
  second.login("userc");
  expect(second.requireCurrent().projectIds).toEqual(["app-checks"]);
  const writes = fetcher.mock.calls.filter(([, init]) => init?.method);
  expect(writes.map(([url, init]) => [url, init!.method])).toEqual([
    ["/api/v1/users", "POST"],
    ["/api/v1/users/userc", "PUT"]
  ]);
  expect(writes[1]![1]!.headers).toMatchObject({
    "X-XA-Dev-User": "MTAwMA",
    "X-XA-Dev-Role": "admin"
  });
  expect(JSON.parse(String(writes[1]![1]!.body))).not.toHaveProperty("userId");
});
it("does not substitute built-in Mock users on API failure and keeps 403 definite", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response("{}", { status: 503 }))
  );
  const identity = createConsoleIdentity(undefined, [], new ApiUserDirectorySource());
  expect(await identity.load()).toBe(false);
  expect(identity.users).toEqual([]);
  expect(() => identity.login("1000")).toThrow("尚未加载");
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response("{}", { status: 403 }))
  );
  await expect(
    new ApiUserDirectorySource().save(ADMIN, developmentUsers[1]!)
  ).rejects.toMatchObject({ status: 403 });
  await expect(
    new ApiUserDirectorySource().save(ADMIN, developmentUsers[1]!)
  ).rejects.toBeInstanceOf(TaskApiError);
});
