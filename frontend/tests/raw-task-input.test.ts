import { consoleIdentity } from "@/console-identity";
import { beforeEach } from "vitest";
beforeEach(() => consoleIdentity.login("1000"));
import { createApp, nextTick } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { ElAlert, ElButton, ElInput, ElOption, ElSelect } from "element-plus";
import { expect, it, vi } from "vitest";
import RawTaskInput from "@/components/RawTaskInput.vue";
import { taskManagementStoreKey } from "@/runtime-context";
import { createTaskManagementStore } from "@/stores/task-management";
function createInput(props: Record<string, unknown>) {
  return createApp(RawTaskInput, props);
}
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
  const app = createInput({
    projectId: "default",
    groups: [
      {
        workerGroupId: "demo-sim",
        workerGroup: {
          workerGroupId: "demo-sim",
          attributes: {},
          eventCodes: ["extension.worker.string.md5"]
        },
        matching: {
          pools: ["country"],
          functions: [
            { name: "worker.country", poolName: "country" },
            { name: "workerId", poolName: null }
          ]
        }
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
    expect(host.querySelectorAll('input[type="radio"]')).toHaveLength(7);
    expect(host.querySelector('textarea[aria-label="Item 查询参数"]')).not.toBeNull();
    expect(store.draft("default", "create").executorName).toBe("");
    store.draft("default", "create").format = "jsonl";
    await nextTick();
    expect(host.textContent).toContain("每行填写完整 Item");
  } finally {
    app.unmount();
    host.remove();
  }
});

it("validates all payload lines before creation and freezes admitted input across the create wait", async () => {
  const pinia = createPinia();
  setActivePinia(pinia);
  let release!: (value: { taskId: string }) => void;
  const client = {
    createTask: vi.fn(
      () =>
        new Promise<{ taskId: string }>((resolve) => {
          release = resolve;
        })
    ),
    appendItems: vi.fn(
      async (_caller: unknown, _task: string, items: { messageId: string }[]) =>
        Object.fromEntries(
          items.map((item: { messageId: string }) => [
            item.messageId,
            { status: "applied" as const }
          ])
        )
    ),
    approveTask: vi.fn(),
    closeTask: vi.fn(),
    ensureDirectory: vi.fn(),
    exportResults: vi.fn()
  };
  const store = createTaskManagementStore("api", client);
  const host = document.createElement("div");
  document.body.append(host);
  const app = createInput({
    projectId: "default",
    disabled: false,
    groups: [
      {
        workerGroupId: "g",
        workerGroup: { workerGroupId: "g", attributes: {}, eventCodes: ["event"] },
        matching: { pools: [], functions: [{ name: "workerId", poolName: null }] }
      }
    ]
  });
  app.use(pinia);
  [ElAlert, ElButton, ElInput, ElOption, ElSelect].forEach((c) => app.use(c));
  app.provide(taskManagementStoreKey, store);
  try {
    app.mount(host);
    await nextTick();
    const draft = store.draft("default", "create");
    Object.assign(draft, {
      eventCode: "event",
      executorName: "workerId",
      queryInput: '"w"',
      pasteText: '{"value":"你好"}\n[]'
    });
    await nextTick();
    host
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await nextTick();
    expect(client.createTask).not.toHaveBeenCalled();
    expect(host.textContent).toContain("第 2 行");
    draft.pasteText = Array.from({ length: 101 }, (_, i) =>
      JSON.stringify({ value: i, nested: { text: "你好" } })
    ).join("\n");
    await nextTick();
    host
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(client.createTask).toHaveBeenCalledTimes(1));
    draft.pasteText = '{"wrong":"later"}';
    draft.eventCode = "later-event";
    release({ taskId: "original" });
    await vi.waitFor(() =>
      expect(store.project("default").submission?.complete).toBe(true)
    );
    expect(client.appendItems).toHaveBeenCalledTimes(2);
    expect(client.appendItems.mock.calls[0]![2][0]).toMatchObject({
      messageId: "original-00001",
      eventCode: "event",
      payload: { value: 0, nested: { text: "你好" } }
    });
    expect(client.approveTask).not.toHaveBeenCalled();
  } finally {
    app.unmount();
    host.remove();
  }
});
