import type { RouteLocationNormalized, Router } from "vue-router";
import { can, roles, type Permission, type AccessSubject } from "@/access-policy";
import { consoleIdentity } from "@/console-identity";

declare module "vue-router" {
  interface RouteMeta {
    public?: boolean;
    permission?: Permission;
    viewPermissions?: Record<string, Permission>;
  }
}

export function routeAllowed(
  user: AccessSubject | null,
  route: Pick<RouteLocationNormalized, "meta" | "query">
): boolean {
  if (route.meta.public) return true;
  if (!user) return false;
  const view = typeof route.query.view === "string" ? route.query.view : "";
  return (
    (!route.meta.permission || can(user, route.meta.permission)) &&
    (!route.meta.viewPermissions?.[view] ||
      can(user, route.meta.viewPermissions[view]!))
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
      target.name !== "login" &&
      target.name !== "not-found" &&
      target.path !== "/" &&
      routeAllowed(user, target)
    )
      return target.fullPath;
  }
  return roles[user.role].home;
}

export function installConsoleAccess(router: Router) {
  router.beforeEach((to) => {
    const user = consoleIdentity.current.value;
    if (to.meta.public)
      return to.name === "login" && user
        ? loginDestination(router, to.query.redirect)
        : undefined;
    if (!user)
      return { name: "login", query: { redirect: to.fullPath }, replace: true };
    if (to.path === "/" || !routeAllowed(user, to))
      return { path: roles[user.role].home, replace: true };
    return undefined;
  });
}
