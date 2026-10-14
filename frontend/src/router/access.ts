import type { RouteLocationNormalized, Router } from "vue-router";
import {
  canUseConsole,
  consoleHome,
  type Permission,
  type ProjectAccessSubject
} from "@/access-policy";
import { consoleIdentity } from "@/console-identity";

declare module "vue-router" {
  interface RouteMeta {
    public?: boolean;
    permission?: Permission;
    viewPermissions?: Record<string, Permission>;
  }
}

export function isPublicRoute(route: Pick<RouteLocationNormalized, "meta" | "query">) {
  const view = typeof route.query.view === "string" ? route.query.view : "";
  return !!route.meta.public && !route.meta.viewPermissions?.[view];
}

export function routeAllowed(
  user: ProjectAccessSubject | null,
  route: Pick<RouteLocationNormalized, "meta" | "query">
): boolean {
  if (isPublicRoute(route)) return true;
  if (!user) return false;
  const view = typeof route.query.view === "string" ? route.query.view : "";
  return (
    (!route.meta.permission || canUseConsole(user, route.meta.permission)) &&
    (!route.meta.viewPermissions?.[view] ||
      canUseConsole(user, route.meta.viewPermissions[view]!))
  );
}

export function loginDestination(router: Router, requested: unknown): string {
  const user = consoleIdentity.requireCurrent();
  if (
    typeof requested === "string" &&
    requested.startsWith("/") &&
    !requested.startsWith("//")
  ) {
    const target = router.resolve(requested);
    if (
      target.name !== "not-found" &&
      !isPublicRoute(target) &&
      routeAllowed(user, target)
    )
      return target.fullPath;
  }
  return consoleHome(user);
}

export function installConsoleAccess(router: Router) {
  router.beforeEach(async (to) => {
    await consoleIdentity.load();
    const user = consoleIdentity.current.value;
    if (isPublicRoute(to))
      return to.name === "login" && user
        ? loginDestination(router, to.query.redirect)
        : undefined;
    if (!user)
      return { name: "login", query: { redirect: to.fullPath }, replace: true };
    if (!routeAllowed(user, to))
      return { ...router.resolve(consoleHome(user)), replace: true };
    return undefined;
  });
}
