import { afterEach, expect, it, vi } from "vitest";
import { createApp, nextTick, type App } from "vue";
import { ElButton } from "element-plus";
import ProjectTasksPanel from "@/components/ProjectTasksPanel.vue";
import { loadProjectTasks } from "@/task-management/projects";

let app: App | undefined;
function mountPanel(props: {
  projectId: string;
  baseUrl: string;
  disabled?: boolean;
  onSelected?: (...args: unknown[]) => void;
}) {
  const host = document.createElement("div");
  document.body.append(host);
  app = createApp(ProjectTasksPanel, props);
  app.component("ElButton", ElButton).mount(host);
  return host;
}
afterEach(() => {
  app?.unmount();
  document.body.replaceChildren();
  vi.unstubAllGlobals();
});

it("loads only on demand, preserves missing projections and shows truncation", async () => {
  const selected = vi.fn();
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({ projectId: "messages", managedTaskIds: { group: "managed" } })
      )
    )
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          projectId: "messages",
          truncated: true,
          tasks: [
            {
              taskId: "closed-task",
              createdAtMillis: 1,
              task: null,
              scoreBand: "terminal"
            }
          ]
        })
      )
    );
  vi.stubGlobal("fetch", fetcher);
  const host = mountPanel({
    projectId: "messages",
    baseUrl: "/api",
    onSelected: selected
  });
  await nextTick();
  expect(fetcher).not.toHaveBeenCalled();
  host
    .querySelector("form")!
    .dispatchEvent(new Event("submit", { cancelable: true, bubbles: true }));
  await vi.waitFor(() => expect(host.textContent).toContain("closed-task"));
  expect(host.textContent).toContain("列表已截断");
  expect(host.textContent).toContain("terminal");
  expect(fetcher.mock.calls.map((call) => call[0])).toEqual([
    "/api/v1/projects/messages",
    "/api/v1/projects/messages/tasks?limit=100"
  ]);
  expect(selected).toHaveBeenCalledWith({
    projectId: "messages",
    managedTaskIds: { group: "managed" }
  });
});

it("does not query in Mock mode", async () => {
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  const host = mountPanel({
    projectId: "demo",
    baseUrl: "/api",
    disabled: true
  });
  host
    .querySelector("form")!
    .dispatchEvent(new Event("submit", { cancelable: true, bubbles: true }));
  await nextTick();
  expect(fetcher).not.toHaveBeenCalled();
});

it("preserves request failures instead of substituting a sampled global list", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response("{}", { status: 503 }))
  );
  await expect(loadProjectTasks("/api", "messages")).rejects.toThrow("503");
});
