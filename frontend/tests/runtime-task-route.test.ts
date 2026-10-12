import { createApp, nextTick } from "vue";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import {
  ElAlert,
  ElButton,
  ElDrawer,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTabs,
  ElTabPane,
  ElTag
} from "element-plus";
import { expect, it, vi } from "vitest";
import TaskRuntimeView from "@/views/TaskRuntimeView.vue";
import {
  runtimeViewerConfigKey,
  runtimeViewerStoreKey,
  taskManagementStoreKey
} from "@/runtime-context";
import { createRuntimeViewerStore } from "@/stores/runtime-viewer";
import { createRuntimeViewerDataSource } from "@/runtime-viewer/data-source";
import { createTaskManagementStore } from "@/stores/task-management";
import { task } from "./fixtures";
it("late creation-drawer close cannot erase the newly opened Task route", async () => {
  const row = {
    taskId: "known",
    createdAtMillis: 1,
    scoreBand: "pre_review",
    task: {
      ...task("known", "g"),
      projectId: "default",
      idleDisposition: "CLOSE_WHEN_IDLE"
    }
  };
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async (url: string) =>
        new Response(
          JSON.stringify(
            url.endsWith("/worker-groups")
              ? [
                  {
                    workerGroupId: "g",
                    workerGroup: { workerGroupId: "g", attributes: {}, eventCodes: [] },
                    matching: { pools: [], functions: ["workerId"] }
                  }
                ]
              : url.endsWith("/tasks/known")
                ? row
                : url.includes("/tasks?")
                  ? { projectId: "default", truncated: false, tasks: [row] }
                  : [{ projectId: "default", managedTaskIds: { g: "rpc" } }]
          )
        )
    )
  );
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/runtime/tasks", component: TaskRuntimeView }]
  });
  await router.push("/runtime/tasks?project=default&view=create");
  const app = createApp({ template: "<router-view />" });
  app.use(createPinia());
  app.use(router);
  [
    ElAlert,
    ElButton,
    ElDrawer,
    ElInput,
    ElOption,
    ElSelect,
    ElTable,
    ElTableColumn,
    ElTabs,
    ElTabPane,
    ElTag
  ].forEach((c) => app.use(c));
  const config = { mode: "api" as const, apiBaseUrl: "/api" };
  app.provide(runtimeViewerConfigKey, config);
  app.provide(
    runtimeViewerStoreKey,
    createRuntimeViewerStore(
      config,
      createRuntimeViewerDataSource({ ...config, mode: "mock" })
    )
  );
  app.provide(
    taskManagementStoreKey,
    createTaskManagementStore("api", {
      createTask: vi.fn(),
      appendItems: vi.fn(),
      approveTask: vi.fn(),
      closeTask: vi.fn(),
      exportResults: vi.fn()
    })
  );
  const host = document.createElement("div");
  document.body.append(host);
  try {
    app.mount(host);
    await vi.waitFor(() =>
      expect(document.body.textContent).toContain("创建原始有限任务")
    );
    await router.push("/runtime/tasks?project=default&taskId=known");
    await nextTick();
    // Element Plus emits update:modelValue again after its leaving transition.
    await new Promise((resolve) => setTimeout(resolve, 450));
    expect(router.currentRoute.value.query.taskId).toBe("known");
    expect(document.body.textContent).toContain("核对并批准");
    const activeDrawer = [...document.querySelectorAll(".el-drawer")].find((d) =>
      d.textContent?.includes("核对并批准")
    );
    (activeDrawer?.querySelector(".el-drawer__close-btn") as HTMLButtonElement).click();
    await router.push("/runtime/tasks?project=default&view=create");
    await new Promise((resolve) => setTimeout(resolve, 450));
    expect(router.currentRoute.value.query.view).toBe("create");
  } finally {
    app.unmount();
    host.remove();
    vi.unstubAllGlobals();
  }
});
