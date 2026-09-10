import { createRouter, createWebHistory } from "vue-router";

export const router = createRouter({
    history: createWebHistory(),
    routes: [
        { path: "/", redirect: "/sms" },
        ...["/sms", "/sms/listeners", "/sms/metrics"].map((path) => ({
            path,
            component: () => import("@/features/reception/SmsWorkspace.vue")
        }))
    ],
    scrollBehavior: () => ({ left: 0, top: 0 })
});
