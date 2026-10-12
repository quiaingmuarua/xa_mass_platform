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
vi.mock("@/components/RawTaskInput.vue", () => ({
  default: {
    emits: ["submitted"],
    template:
      "<button data-testid=\"finish-submission\" @click=\"$emit('submitted', 'known')\">Finish</button>"
  }
}));
it("late drawer and submission continuations cannot replace the current context", async () => {
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
  let delayRefresh = false;
  let releaseRefresh: ((response: Response) => void) | undefined;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      if (delayRefresh && url.includes("/tasks?")) {
        return new Promise<Response>((resolve) => {
          releaseRefresh = resolve;
        });
      }
      return new Response(
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
      );
    })
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
  const operations = createTaskManagementStore("api", {
    createTask: vi.fn(),
    appendItems: vi.fn(),
    approveTask: vi.fn(),
    closeTask: vi.fn(),
    exportResults: vi.fn()
  });
  app.provide(taskManagementStoreKey, operations);
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
    operations.submission = {
      taskId: "known",
      fileName: "old.txt",
      total: 1,
      applied: 1,
      rejected: 0,
      unconfirmed: 0,
      notSubmitted: 0,
      complete: true
    };
    delayRefresh = true;
    (
      document.querySelector('[data-testid="finish-submission"]') as HTMLButtonElement
    ).click();
    await vi.waitFor(() => expect(releaseRefresh).toBeTypeOf("function"));
    operations.finishSubmission();
    operations.submission = {
      taskId: "next",
      fileName: "new.txt",
      total: 1,
      applied: 1,
      rejected: 0,
      unconfirmed: 0,
      notSubmitted: 0,
      complete: true
    };
    await router.push("/runtime/tasks?project=elsewhere");
    releaseRefresh!(
      new Response(
        JSON.stringify({ projectId: "default", truncated: false, tasks: [row] })
      )
    );
    await nextTick();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(router.currentRoute.value.query.project).toBe("elsewhere");
    expect(router.currentRoute.value.query.taskId).toBeUndefined();
  } finally {
    app.unmount();
    host.remove();
    vi.unstubAllGlobals();
  }
});
