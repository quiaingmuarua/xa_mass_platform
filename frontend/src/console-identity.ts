import { computed, shallowRef } from "vue";
import { requireCaller, type TaskCaller } from "./task-caller";
import { canUseConsole, installRoles, isRole } from "./access-policy";
import { developmentUsers, type ConsoleUser } from "./development-users";
import {
  ApiUserDirectorySource,
  MockUserDirectorySource,
  type UserDirectorySource
} from "./user-directory-source";

export const identityStorageKey = "xa-mass.console-user";
type IdentityStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

export function createConsoleIdentity(
  storage?: IdentityStorage,
  initialUsers = developmentUsers,
  source: UserDirectorySource = new MockUserDirectorySource(initialUsers)
) {
  const freezeUser = (user: ConsoleUser): ConsoleUser =>
    Object.freeze({ ...user, projectIds: Object.freeze([...user.projectIds]) });
  const users = shallowRef<readonly ConsoleUser[]>([]);
  const selected = shallowRef<ConsoleUser | null>(null);
  const generation = shallowRef(0);
  const ready = shallowRef(false);
  const loading = shallowRef(false);
  const error = shallowRef("");
  let pending: Promise<boolean> | undefined;
  let directoryGeneration = 0;
  let restoredId: string | null = null;
  try {
    restoredId = storage?.getItem(identityStorageKey) ?? null;
  } catch {
    /* Storage is optional. */
  }
  const find = (id: string | null) =>
    users.value.find((entry) => entry.userId === id && isRole(entry.role));
  function apply(snapshot: Awaited<ReturnType<UserDirectorySource["load"]>>) {
    installRoles(snapshot.roles);
    users.value = Object.freeze(snapshot.users.map(freezeUser));
    if (restoredId !== null) {
      const user = find(restoredId);
      selected.value = user ? freezeUser(user) : null;
      restoredId = null;
      if (!user)
        try {
          storage?.removeItem(identityStorageKey);
        } catch {
          /* Storage is optional. */
        }
    }
    ready.value = true;
  }
  if (source.initial) apply(source.initial);
  async function load(force = false): Promise<boolean> {
    if (pending) return pending;
    if (ready.value && !force) return true;
    loading.value = true;
    error.value = "";
    const observed = directoryGeneration;
    pending = (async () => {
      try {
        const snapshot = await source.load();
        if (observed === directoryGeneration) apply(snapshot);
        return true;
      } catch (cause) {
        if (observed === directoryGeneration)
          error.value = cause instanceof Error ? cause.message : "无法读取用户目录";
        return false;
      } finally {
        loading.value = false;
        pending = undefined;
      }
    })();
    return pending;
  }
  return {
    get users() {
      return users.value;
    },
    ready: computed(() => ready.value),
    loading: computed(() => loading.value),
    error: computed(() => error.value),
    load,
    async saveUser(input: ConsoleUser, existingUserId?: string) {
      const actor = selected.value;
      if (!actor || !canUseConsole(actor, "users.manage"))
        throw new Error("当前用户没有用户管理权限");
      const saved = await source.save(
        { userId: actor.userId, role: actor.role },
        input,
        existingUserId
      );
      if (selected.value === actor) {
        directoryGeneration++;
        users.value = Object.freeze([
          ...users.value.filter((entry) => entry.userId !== saved.userId),
          freezeUser(saved)
        ]);
      }
      return saved;
    },
    current: computed(() => selected.value),
    generation: computed(() => generation.value),
    requireCurrent(): ConsoleUser {
      if (!selected.value) throw new Error("请先登录控制台");
      return selected.value;
    },
    login(userId: string) {
      if (!ready.value) throw new Error("用户目录尚未加载");
      const user = find(userId);
      if (!user) throw new Error("请选择有效的用户");
      selected.value = freezeUser(user);
      generation.value++;
      try {
        storage?.setItem(identityStorageKey, user.userId);
      } catch {
        /* In-memory login still works. */
      }
    },
    logout() {
      selected.value = null;
      restoredId = null;
      generation.value++;
      try {
        storage?.removeItem(identityStorageKey);
      } catch {
        /* Logout still takes effect. */
      }
    }
  };
}
function browserStorage() {
  try {
    return typeof window === "undefined" ? undefined : window.sessionStorage;
  } catch {
    return undefined;
  }
}
export const consoleUserSource =
  import.meta.env.VITE_RUNTIME_DATA_SOURCE === "mock"
    ? new MockUserDirectorySource()
    : new ApiUserDirectorySource();
export const consoleIdentity = createConsoleIdentity(
  browserStorage(),
  [],
  consoleUserSource
);
export function identityLabel(userId?: string) {
  return (
    consoleIdentity.users.find((entry) => entry.userId === userId)?.displayName ??
    userId ??
    "未提供"
  );
}
export function captureTaskCaller(): TaskCaller {
  return requireCaller(consoleIdentity.requireCurrent());
}
