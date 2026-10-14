import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import ConsoleSessionProvider from "@/layouts/ConsoleSessionProvider.vue";
import { installConsoleAccess } from "./access";

const smsPage = () => import("@/sms/SmsPage.vue");
const messagesPage = () => import("@/message-campaigns/MessagePage.vue");
const appChecksPage = () => import("@/app-checks/AppCheckPage.vue");
export const consoleRoutes: RouteRecordRaw[] = [
  {
    path: "/",
    name: "login",
    component: () => import("@/views/ConsoleEntryView.vue"),
    meta: {
      public: true,
      title: "控制台",
      viewPermissions: { users: "users.manage", home: "console.view" }
    }
  },
  {
    path: "/",
    component: ConsoleSessionProvider,
    children: [
      {
        path: "runtime",
        meta: { section: "Runtime", permission: "runtime.view" },
        children: [
          {
            path: "workers",
            name: "runtime-workers",
            component: () => import("@/views/WorkerRuntimeView.vue"),
            meta: { title: "Worker Runtime" }
          },
          {
            path: "tasks",
            name: "runtime-tasks",
            component: () => import("@/views/TaskRuntimeView.vue"),
            meta: { title: "Tasks" }
          }
        ]
      },
      {
        path: "task-workbench",
        name: "task-workbench",
        component: () => import("@/task-workbench/TaskWorkbench.vue"),
        meta: {
          section: "Workbench",
          title: "任务工作台",
          permission: "workbench.use"
        }
      },
      {
        path: "reference/error-codes",
        name: "reference-error-codes",
        component: () => import("@/views/ErrorCodeReferenceView.vue"),
        meta: {
          section: "Reference",
          title: "Diagnostic Codes",
          permission: "reference.view"
        }
      },
      ...["sms", "sms/metrics"].map((path) => ({
        path,
        component: smsPage,
        meta: { section: "Scenarios", title: "SMS", permission: "sms.use" as const }
      })),
      ...["messages", "messages/tasks/:taskId"].map((path) => ({
        path,
        component: messagesPage,
        meta: {
          section: "Scenarios",
          title: "Messages",
          permission: "messages.use" as const
        }
      })),
      ...["app-checks", "app-checks/tasks/:taskId"].map((path) => ({
        path,
        component: appChecksPage,
        meta: {
          section: "Scenarios",
          title: "应用注册查询",
          permission: "app-checks.tasks" as const,
          viewPermissions: { configuration: "app-checks.configuration" as const }
        }
      }))
    ]
  },
  {
    path: "/api-reference",
    alias: "/scalar",
    name: "api-reference",
    component: () => import("@/views/ApiReferenceView.vue"),
    meta: { title: "API Reference", permission: "reference.view" }
  },
  {
    path: "/:pathMatch(.*)*",
    name: "not-found",
    component: () => import("@/views/NotFoundView.vue")
  }
];
export const router = createRouter({
  history: createWebHistory(),
  routes: consoleRoutes,
  scrollBehavior: (to, from) => {
    if (
      ["/runtime/tasks", "/task-workbench"].includes(to.path) &&
      to.path === from.path
    )
      return false;
    // Messages previews remain over the mounted list or creation draft.
    if (
      (to.path === "/messages" || to.path.startsWith("/messages/tasks/")) &&
      (from.path === "/messages" || from.path.startsWith("/messages/tasks/"))
    )
      return false;
    // Task previews are overlays; their routes do not scroll the background.
    if (
      (to.path === "/app-checks" || to.path.startsWith("/app-checks/tasks/")) &&
      (from.path === "/app-checks" || from.path.startsWith("/app-checks/tasks/"))
    )
      return false;
    return { left: 0, top: 0 };
  }
});
installConsoleAccess(router);
router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? "页面未找到")} · XA Mass`;
});
