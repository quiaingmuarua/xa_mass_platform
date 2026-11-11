import { computed, shallowRef } from "vue";
import {
  requireCaller,
  TaskApiError,
  withAccessKey,
  type TaskCaller
} from "./task-caller";
import { canUseConsole, installRoles } from "./access-policy";
import { isAccessKey, setSessionAccessKey } from "./access-session";
import { developmentUsers, type ConsoleUser } from "./development-users";
import {
  ApiUserDirectorySource,
  MockUserDirectorySource,
  type UserDirectory,
  type UserDirectorySource
} from "./user-directory-source";

/** Session-scoped access key of this tab; userIds and roles are never trusted from storage. */
export const accessKeyStorageKey = "xa-mass.console-access-key";
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
  let accessKey: string | null = null;
  let pending: Promise<boolean> | undefined;
  // Advanced by login, logout and local writes; a late directory read from an older state is discarded.
  let epoch = 0;
  try {
    const restored = storage?.getItem(accessKeyStorageKey) ?? null;
    if (isAccessKey(restored)) accessKey = restored;
    else if (restored !== null) storage?.removeItem(accessKeyStorageKey);
  } catch {
    /* Storage is optional. */
  }
  const remember = (key: string | null) => {
    accessKey = key;
    setSessionAccessKey(key);
    try {
      if (key) storage?.setItem(accessKeyStorageKey, key);
      else storage?.removeItem(accessKeyStorageKey);
    } catch {
      /* In-memory login still works. */
    }
  };
  function applyDirectory(snapshot: UserDirectory) {
    installRoles(snapshot.roles);
    users.value = Object.freeze(snapshot.users.map(freezeUser));
  }
  /**
   * Resolves the key and loads the directory; nothing is applied when a newer login or logout happened.
   * A refresh keeps the current login snapshot; a new login or a restored session replaces it.
   */
  async function admit(key: string, observed: number, replaceLogin: boolean) {
    const admitted = await source.authenticate(key);
    const snapshot = await source.load(key);
    if (observed !== epoch) return false;
    applyDirectory(snapshot);
    remember(key);
    if (replaceLogin || selected.value?.userId !== admitted.user.userId)
      selected.value = freezeUser(admitted.user);
    return true;
  }
  function forget() {
    epoch++;
    remember(null);
    const wasLoggedIn = selected.value !== null;
    selected.value = null;
    users.value = Object.freeze([]);
    // A revoked or logged-out login tears down every view that captured it.
    if (wasLoggedIn) generation.value++;
  }
  async function load(force = false): Promise<boolean> {
    if (pending) return pending;
    if (ready.value && !force) return true;
    loading.value = true;
    error.value = "";
    const observed = epoch;
    pending = (async () => {
      try {
        if (accessKey) await admit(accessKey, observed, false);
        ready.value = true;
        return true;
      } catch (cause) {
        if (cause instanceof TaskApiError && cause.status === 401) {
          if (observed === epoch) forget();
          ready.value = true;
          return true;
        }
        if (observed === epoch)
          error.value = cause instanceof Error ? cause.message : "无法读取用户目录";
        return false;
      } finally {
        loading.value = false;
        pending = undefined;
      }
    })();
    return pending;
  }
  function actor() {
    const current = selected.value;
    if (!current || !accessKey || !canUseConsole(current, "users.manage"))
      throw new Error("当前用户没有用户管理权限");
    return {
      current,
      caller: requireCaller(
        withAccessKey({ userId: current.userId, role: current.role }, accessKey)
      )
    };
  }
  function replaceLocal(saved: ConsoleUser) {
    epoch++;
    users.value = Object.freeze([
      ...users.value.filter((entry) => entry.userId !== saved.userId),
      freezeUser(saved)
    ]);
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
      const { current, caller } = actor();
      const saved = await source.save(caller, input, existingUserId);
      if (selected.value === current) replaceLocal(saved);
      return saved;
    },
    /** Returns the new key once; it is not retained by the store. */
    async issueAccessKey(userId: string) {
      const { current, caller } = actor();
      const issued = await source.issueKey(caller, userId);
      const target = users.value.find((entry) => entry.userId === userId);
      if (selected.value === current && target)
        replaceLocal({ ...target, hasAccessKey: true });
      return issued;
    },
    async clearAccessKey(userId: string) {
      const { current, caller } = actor();
      const saved = await source.clearKey(caller, userId);
      if (selected.value === current) replaceLocal(saved);
      return saved;
    },
    current: computed(() => selected.value),
    generation: computed(() => generation.value),
    requireCurrent(): ConsoleUser {
      if (!selected.value) throw new Error("请先登录控制台");
      return selected.value;
    },
    /** The captured caller authenticates with this login's key, even after a later login. */
    requireCaller(): TaskCaller {
      if (!selected.value || !accessKey) throw new Error("请先登录控制台");
      return requireCaller(
        withAccessKey(
          { userId: selected.value.userId, role: selected.value.role },
          accessKey
        )
      );
    },
    async login(key: string) {
      const candidate = key.trim();
      if (!isAccessKey(candidate)) throw new Error("请输入以 xak_ 开头的有效访问密钥");
      const observed = ++epoch;
      loading.value = true;
      error.value = "";
      try {
        if (!(await admit(candidate, observed, true)))
          throw new Error("登录已被新的操作替代");
        ready.value = true;
        generation.value++;
      } catch (cause) {
        if (cause instanceof TaskApiError && cause.status === 401)
          throw new Error("访问密钥无效或已被清除", { cause });
        throw cause;
      } finally {
        loading.value = false;
      }
    },
    logout() {
      forget();
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
  return consoleIdentity.requireCaller();
}
