import { computed, shallowRef } from "vue";
import type { RouteLocationNormalized } from "vue-router";

export interface ConsoleIdentity {
  readonly userId: string;
  readonly displayName: string;
  readonly role: "admin" | "operator";
}

export const developmentIdentities: readonly ConsoleIdentity[] = Object.freeze([
  Object.freeze({ userId: "admin", displayName: "管理员", role: "admin" as const }),
  Object.freeze({ userId: "user-a", displayName: "用户 A", role: "operator" as const }),
  Object.freeze({ userId: "user-b", displayName: "用户 B", role: "operator" as const })
]);
export const identityStorageKey = "xa-mass.development-user";

export function createConsoleIdentity(storage?: Pick<Storage, "getItem" | "setItem">) {
  let initial = developmentIdentities[0]!;
  try {
    initial =
      developmentIdentities.find(
        (entry) => entry.userId === storage?.getItem(identityStorageKey)
      ) ?? initial;
  } catch {
    /* Identity selection still works when browser storage is unavailable. */
  }
  const selected = shallowRef(initial);
  return {
    current: computed(() => selected.value),
    select(userId: string) {
      const next = developmentIdentities.find((entry) => entry.userId === userId);
      if (!next || next === selected.value) return;
      selected.value = next;
      try {
        storage?.setItem(identityStorageKey, next.userId);
      } catch {
        /* Session-only selection. */
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

// UI development context only: it is neither authentication nor an API credential.
export const consoleIdentity = createConsoleIdentity(browserStorage());
export function identityLabel(userId?: string) {
  return (
    developmentIdentities.find((entry) => entry.userId === userId)?.displayName ??
    userId ??
    "未提供"
  );
}
export function identityRouteAllowed(
  identity: ConsoleIdentity,
  route: Pick<RouteLocationNormalized, "path" | "query">
) {
  return (
    identity.role === "admin" ||
    (/^\/app-checks(?:\/tasks\/[^/]+)?\/?$/.test(route.path) &&
      (route.query.view === undefined || route.query.view === "create"))
  );
}
