import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import ConsoleLayout from "@/layouts/ConsoleLayout.vue";
import RuntimeProvider from "@/layouts/RuntimeProvider.vue";

const smsPage = () => import("@/sms/SmsPage.vue");
export const consoleRoutes: RouteRecordRaw[] = [
  {
    path: "/",
    component: ConsoleLayout,
    children: [
      { path: "", redirect: "/runtime/workers" },
      {
        path: "runtime",
        component: RuntimeProvider,
        meta: { section: "Runtime" },
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
        path: "reference/error-codes",
        name: "reference-error-codes",
        component: () => import("@/views/ErrorCodeReferenceView.vue"),
        meta: { section: "Reference", title: "Diagnostic Codes" }
      },
      ...["sms", "sms/listeners", "sms/metrics"].map((path) => ({
        path,
        component: smsPage,
        meta: { section: "Business", title: "SMS" }
      }))
    ]
  },
  {
    path: "/api-reference",
    alias: "/scalar",
    name: "api-reference",
    component: () => import("@/views/ApiReferenceView.vue"),
    meta: { title: "API Reference" }
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
  scrollBehavior: () => ({ left: 0, top: 0 })
});
router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? "页面未找到")} · XA Mass`;
});
