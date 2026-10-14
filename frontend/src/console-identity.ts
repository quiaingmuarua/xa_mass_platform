import { computed, shallowRef } from "vue";
import { isRole } from "./access-policy";
import { developmentUsers, type ConsoleUser } from "./development-users";

export const identityStorageKey = "xa-mass.console-user";
type IdentityStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

export function createConsoleIdentity(
  storage?: IdentityStorage,
  users = developmentUsers
) {
  const selected = shallowRef<ConsoleUser | null>(null);
  const generation = shallowRef(0);
  const find = (id: string | null) =>
    users.find((user) => user.userId === id && isRole(user.role));
  try {
    const user = find(storage?.getItem(identityStorageKey) ?? null);
    if (user) selected.value = Object.freeze({ ...user });
    else storage?.removeItem(identityStorageKey);
  } catch {
    /* The login remains usable without browser storage. */
  }
  return {
    users,
    current: computed(() => selected.value),
    generation: computed(() => generation.value),
    requireCurrent(): ConsoleUser {
      if (!selected.value) throw new Error("请先登录控制台");
      return selected.value;
    },
    login(userId: string) {
      const user = find(userId);
      if (!user) throw new Error("请选择有效的内置用户");
      selected.value = Object.freeze({ ...user });
      generation.value++;
      try {
        storage?.setItem(identityStorageKey, user.userId);
      } catch {
        /* In-memory login still works. */
      }
    },
    logout() {
      selected.value = null;
      generation.value++;
      try {
        storage?.removeItem(identityStorageKey);
      } catch {
        /* In-memory logout still takes effect. */
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

export const consoleIdentity = createConsoleIdentity(browserStorage());
export function identityLabel(userId?: string) {
  return (
    consoleIdentity.users.find((user) => user.userId === userId)?.displayName ??
    userId ??
    "未提供"
  );
}
