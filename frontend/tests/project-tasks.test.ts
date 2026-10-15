import { MockUserDirectorySource } from "@/user-directory-source";
import { ADMIN } from "./identity-fixture";
import taskWireFixture from "./fixtures/server-task-view.json";
import { taskViewSchema } from "@/runtime-viewer/schemas";
import { afterEach, expect, it, vi } from "vitest";
import { computed } from "vue";
import { createProjectSource, type ProjectSource } from "@/task-management/projects";
import { createProjectWorkspace } from "@/task-management/workspace";
import { task } from "./fixtures";
afterEach(() => vi.unstubAllGlobals());
it("raw previews retain old snapshots on failure and discard cross-task late responses", async () => {
  let release!: (value: unknown) => void;
  const current = {
    results: [
      {
        messageId: "b",
        item: null,
        result: { status: "succeeded", opaqueResultPayload: "原文\n" }
      }
    ],
    truncated: true
  };
  const source = {
    results: vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            release = resolve;
          })
      )
      .mockResolvedValueOnce(current)
  } as unknown as ProjectSource;
  const workspace = createProjectWorkspace(source);
  const old = workspace.loadResults("p", "a");
  await workspace.loadResults("p", "b");
  release({ results: [], truncated: false });
  await old;
  expect(workspace.results.value.value).toEqual(current);
  const time = workspace.results.value.readAt;
  vi.mocked(source.results).mockRejectedValueOnce(new Error("offline"));
  await workspace.loadResults("p", "b");
  expect(workspace.results.value.stale).toBe(true);
  expect(workspace.results.value.readAt).toBe(time);
  expect(workspace.results.value.value).toEqual(current);
  workspace.dispose();
});
it("first asynchronous loads notify computed list and Group consumers", async () => {
  const source = {
    tasks: vi.fn().mockResolvedValue({
      projectId: "p",
      truncated: false,
      tasks: [{ taskId: "one", createdAtMillis: 1, scoreBand: null, task: null }]
    }),
    groups: vi.fn().mockResolvedValue([
      {
        workerGroupId: "g",
        workerGroup: null,
        matching: { pools: [], functions: [{ name: "workerId", poolName: null }] }
      }
    ])
  } as unknown as ProjectSource;
  const workspace = createProjectWorkspace(source);
  const count = computed(() => workspace.windows.p?.value?.tasks.length ?? 0);
  const groups = computed(() => workspace.groups.p?.value?.length ?? 0);
  const pending = Promise.all([workspace.loadTasks("p"), workspace.loadGroups("p")]);
  expect(count.value).toBe(0);
  expect(groups.value).toBe(0);
  await pending;
  expect(count.value).toBe(1);
  expect(groups.value).toBe(1);
});
it("reads startup Projects and complete backend-shaped TaskViews with null projections", async () => {
  const descriptor = {
    ...task("t", "g"),
    projectId: "p",
    name: "named",
    metadata: { scenario: "test" }
  };
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify([
          { projectId: "p", workerGroupSource: "declared", workerGroupIds: ["g"] }
        ])
      )
    )
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          projectId: "p",
          truncated: true,
          tasks: [
            {
              taskId: "t",
              createdAtMillis: 12,
              task: descriptor,
              scoreBand: "pre_review"
            },
            { taskId: "missing", createdAtMillis: 10, task: null, scoreBand: null }
          ]
        })
      )
    );
  vi.stubGlobal("fetch", fetcher);
  const source = createProjectSource("/api", "api", ADMIN);
  expect(await source.list()).toHaveLength(1);
  fetcher.mockResolvedValueOnce(
    new Response(JSON.stringify({ t: { total: 2, countsByTag: { "1": 2 } } }))
  );
  expect((await source.tasks("p")).tasks[0]?.task).toEqual(descriptor);
  expect(fetcher.mock.calls.map((call) => call[0])).toEqual([
    "/api/v1/projects",
    "/api/v1/projects/p/tasks?limit=100",
    "/api/v1/projects/p/tasks:stats"
  ]);
});
it("Mock has a consistent directory and no service requests", async () => {
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  const source = createProjectSource(
    "/api",
    "mock",
    ADMIN,
    new MockUserDirectorySource()
  );
  const projects = await source.list();
  expect(projects.map((project) => project.projectId)).toEqual(
    expect.arrayContaining(["messages", "app-checks"])
  );
  for (const project of projects) {
    const result = await source.tasks(project.projectId);
    expect(result.tasks.every((row) => row.task?.projectId === project.projectId)).toBe(
      true
    );
    const groups = await source.groups(project.projectId);
    if (project.workerGroupSource === "declared")
      expect(groups.map((group) => group.workerGroupId)).toEqual(
        project.workerGroupIds
      );
    else {
      expect(project.workerGroupIds).toEqual([]);
      expect(groups.map((group) => group.workerGroupId)).toContain(
        "agentforge-detect-env-workers"
      );
      expect(groups.every((group) => group.workerGroup !== null)).toBe(true);
    }
    for (const row of result.tasks)
      expect(await source.task(project.projectId, row.taskId)).toEqual(row);
  }
  expect(fetcher).not.toHaveBeenCalled();
});
it("late detail responses cannot overwrite another task; refresh failure retains snapshot and time", async () => {
  let resolveOld!: (value: unknown) => void;
  const current = { taskId: "new", task: null, createdAtMillis: 2, scoreBand: null };
  const source = {
    task: vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveOld = resolve;
          })
      )
      .mockResolvedValueOnce(current)
      .mockRejectedValueOnce(new Error("unavailable"))
  } as unknown as ProjectSource;
  const workspace = createProjectWorkspace(source);
  const first = workspace.loadDetail("p", "old");
  await workspace.loadDetail("p", "new");
  const readAt = workspace.detail.value.readAt;
  resolveOld({ ...current, taskId: "old" });
  await first;
  expect(workspace.detail.value.value?.taskId).toBe("new");
  await workspace.loadDetail("p", "new");
  expect(workspace.detail.value).toMatchObject({
    value: current,
    readAt,
    stale: true,
    error: "unavailable"
  });
  workspace.dispose();
});
it("failed task reads do not erase the directory or Group capabilities", async () => {
  const source = {
    list: vi
      .fn()
      .mockResolvedValue([
        { projectId: "p", workerGroupSource: "declared", workerGroupIds: ["g"] }
      ]),
    tasks: vi.fn().mockRejectedValue(new Error("bad Task response")),
    groups: vi.fn().mockResolvedValue([])
  } as unknown as ProjectSource;
  const workspace = createProjectWorkspace(source);
  await workspace.loadDirectory();
  await Promise.all([workspace.loadTasks("p"), workspace.loadGroups("p")]);
  expect(workspace.directory.value?.[0]?.projectId).toBe("p");
  expect(workspace.groups.p?.stale).toBe(false);
  expect(workspace.windows.p?.error).toBe("bad Task response");
});

it("accepts the exact DTO fixture asserted by the Server serializer", () => {
  expect(taskViewSchema.parse(taskWireFixture)).toEqual(taskWireFixture);
  expect(() => taskViewSchema.parse({ ...taskWireFixture, unknown: true })).toThrow();
});

it("a pre-mutation read cannot make an invalidated snapshot fresh again", async () => {
  const current = { taskId: "rpc", task: null, createdAtMillis: 2, scoreBand: null };
  let release!: (value: unknown) => void;
  const source = {
    task: vi
      .fn()
      .mockResolvedValueOnce(current)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            release = resolve;
          })
      )
  } as unknown as ProjectSource;
  const workspace = createProjectWorkspace(source);
  await workspace.loadDetail("p", "rpc");
  const time = workspace.detail.value.readAt;
  const old = workspace.loadDetail("p", "rpc");
  workspace.invalidateDetail();
  release({ ...current, createdAtMillis: 999 });
  await old;
  expect(workspace.detail.value).toMatchObject({
    value: current,
    readAt: time,
    stale: true,
    loading: false
  });
});
