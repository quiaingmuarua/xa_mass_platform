import { createApp, nextTick } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { ElAlert, ElButton, ElInput, ElOption, ElSelect } from "element-plus";
import { expect, it, vi } from "vitest";
import RawTaskInput from "@/components/RawTaskInput.vue";
import { taskManagementStoreKey } from "@/runtime-context";
import { createTaskManagementStore } from "@/stores/task-management";
it("registers lazy form, numeric and radio controls using only the console global components", async () => {
  const pinia = createPinia();
  setActivePinia(pinia);
  const store = createTaskManagementStore("api", {
    createTask: vi.fn(),
    appendItems: vi.fn(),
    approveTask: vi.fn(),
    closeTask: vi.fn(),
    ensureDirectory: vi.fn(async () => {}),
    exportResults: vi.fn()
  });
  const host = document.createElement("div");
  document.body.append(host);
  const app = createApp(RawTaskInput, {
    projectId: "default",
    groups: [
      {
        workerGroupId: "demo-sim",
        workerGroup: {
          workerGroupId: "demo-sim",
          attributes: {},
          eventCodes: ["extension.worker.string.md5"]
        },
        matching: { pools: ["country"], functions: ["worker.country", "workerId"] }
      }
    ],
    disabled: false
  });
  app.use(pinia);
  [ElAlert, ElButton, ElInput, ElOption, ElSelect].forEach((c) => app.use(c));
  app.provide(taskManagementStoreKey, store);
  try {
    app.mount(host);
    await nextTick();
    expect(host.querySelector("form")).not.toBeNull();
    expect(host.querySelectorAll('input[type="number"]')).toHaveLength(2);
    expect(host.querySelectorAll('input[type="radio"]')).toHaveLength(2);
    expect(
      host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Item 查询"]')?.value
    ).toContain("worker.country");
    (host.querySelectorAll('input[type="radio"]')[1] as HTMLInputElement).click();
    await nextTick();
    expect(host.textContent).toContain("每行填写完整 Item");
  } finally {
    app.unmount();
    host.remove();
  }
});
