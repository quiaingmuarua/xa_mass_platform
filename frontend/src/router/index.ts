import { createRouter, createWebHistory } from "vue-router";

import RuntimeLayout from "@/layouts/RuntimeLayout.vue";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      component: RuntimeLayout,
      children: [
        {
          path: "",
          redirect: "/runtime/workers"
        },
        {
          path: "runtime/workers",
          name: "runtime-workers",
          component: () => import("@/views/WorkerRuntimeView.vue"),
          meta: {
            title: "Worker Runtime"
          }
        },
        {
          path: "runtime/tasks",
          name: "runtime-tasks",
          component: () => import("@/views/TaskRuntimeView.vue"),
          meta: {
            title: "Tasks"
          }
        },
        {
          path: "reference/error-codes",
          name: "reference-error-codes",
          component: () => import("@/views/ErrorCodeReferenceView.vue"),
          meta: {
            section: "Reference",
            title: "Diagnostic Codes"
          }
        }
      ]
    },
    {
      path: "/:pathMatch(.*)*",
      name: "not-found",
      component: () => import("@/views/NotFoundView.vue")
    }
  ],
  scrollBehavior: () => ({ left: 0, top: 0 })
});

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? "页面未找到")} · XA Mass`;
});
