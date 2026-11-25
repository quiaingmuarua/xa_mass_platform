import { MOCK_PROJECTS, MOCK_AGENT_GROUPS } from "@/runtime-viewer/mock-projects";
import { MOCK_TASK_PREVIEW, MOCK_WORKER_GROUPS } from "@/runtime-viewer/mock-data";
import { mockWorkerGroupBindings } from "@/runtime-viewer/worker-group-bindings";
import type { RefillTarget } from "@/runtime-viewer/types";
import type { MockUserDirectorySource } from "@/user-directory-source";
import { rpcTaskId } from "@/task-workbench/rpc-task";
import { compareTaskIds, taskUserScore } from "@/task-user-id";
import { type TaskCaller, requireTaskCaller, selectTaskUser } from "@/task-caller";
import {
  ProjectRequestError,
  type Project,
  type ProjectGroup,
  type ProjectSource,
  type ProjectTask,
  type TaskStats
} from "./projects";

type TagCounts = Partial<Record<1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9, number>>;

function makeStats(counts: TagCounts): TaskStats {
  const countsByTag: Record<string, number> = {};
  let total = 0;
  for (let tag = 1; tag <= 9; tag++) {
    const value = counts[tag as keyof TagCounts] ?? 0;
    countsByTag[String(tag)] = value;
    total += value;
  }
  return { total, countsByTag };
}

function makeRetention(
  phase: "OPEN" | "FROZEN" | "RELEASED",
  terminatedAtMillis: number | null,
  frozenAtMillis: number | null = null,
  historyId: string | null = null
) {
  return {
    phase,
    terminatedAtMillis,
    frozenAtMillis,
    expiresAtMillis:
      terminatedAtMillis == null ? null : terminatedAtMillis + 7 * 86_400_000,
    historyId,
    historyExpiresAtMillis:
      terminatedAtMillis == null ? null : terminatedAtMillis + 30 * 86_400_000
  };
}

function buildTask(spec: {
  taskId: string;
  projectId?: string;
  workerGroupId: string;
  managed?: boolean;
  name: string | null;
  createdAtMillis: number;
  scoreBand: ProjectTask["scoreBand"];
  stats: TaskStats;
  retention?: ProjectTask["retention"];
  refill?: RefillTarget[];
  priority?: string;
  maxRetryTimes?: string;
  extraMetadata?: Record<string, string>;
}): ProjectTask {
  const projectId = spec.projectId ?? "default";
  const managed = spec.managed ?? false;
  return {
    taskId: spec.taskId,
    createdAtMillis: spec.createdAtMillis,
    scoreBand: spec.scoreBand,
    retention: spec.retention ?? (managed ? null : makeRetention("OPEN", null)),
    stats: spec.stats,
    task: {
      taskId: spec.taskId,
      projectId,
      workerGroupId: spec.workerGroupId,
      idleDisposition: managed ? "PARK_WHEN_IDLE" : "CLOSE_WHEN_IDLE",
      name: spec.name,
      metadata: {
        userId: "demo",
        "server.inputOwner": "raw",
        "server.projectId": projectId,
        "server.createdAtMillis": String(spec.createdAtMillis),
        ...spec.extraMetadata
      },
      config: {
        priority: spec.priority ?? (managed ? "0" : "50"),
        maxRetryTimes: spec.maxRetryTimes ?? "3"
      },
      refill: spec.refill ?? [{ poolName: "any", target: {}, count: 100 }]
    }
  };
}

export function createMockProjectSource(
  caller: TaskCaller,
  directory: MockUserDirectorySource
): ProjectSource {
  const groups: ProjectGroup[] = MOCK_WORKER_GROUPS.map((workerGroup) => ({
    workerGroupId: workerGroup.workerGroupId,
    workerGroup,
    matching: mockWorkerGroupBindings(workerGroup.workerGroupId).matching
  }));
  const agentGroups: ProjectGroup[] = MOCK_AGENT_GROUPS.map((group) => ({
    ...group,
    matching: mockWorkerGroupBindings(group.workerGroupId).matching
  }));
  const projects: Project[] = structuredClone(MOCK_PROJECTS);
  const baseTime = Date.parse(MOCK_TASK_PREVIEW.generatedAt);

  const finitePreviewOverrides: Record<
    string,
    Partial<Parameters<typeof buildTask>[0]>
  > = {
    "mock-finite-awaiting-review": {
      name: "号码归属地待审核批次",
      createdAtMillis: baseTime - 18 * 60_000,
      stats: makeStats({ 1: 120 })
    },
    "mock-finite-initial": {
      name: "MD5 批量校验任务",
      createdAtMillis: baseTime - 9 * 60_000,
      scoreBand: "running_visible",
      stats: makeStats({ 1: 160, 5: 12, 6: 328 }),
      extraMetadata: { batchTag: "nightly-demo" }
    },
    "mock-finite-closed": {
      name: "字符串摘要已完成批次",
      createdAtMillis: baseTime - 45 * 60_000,
      retention: makeRetention("OPEN", baseTime - 32 * 60_000),
      stats: makeStats({ 6: 250 })
    }
  };

  const tasks: ProjectTask[] = MOCK_TASK_PREVIEW.entries.map((row) => {
    const override = finitePreviewOverrides[row.taskId];
    if (override && row.task) {
      return buildTask({
        taskId: row.taskId,
        workerGroupId: row.task.workerGroupId,
        name: override.name ?? row.task.name,
        createdAtMillis: override.createdAtMillis ?? baseTime,
        scoreBand: override.scoreBand ?? row.scoreBand,
        stats: override.stats ?? makeStats({}),
        retention: override.retention,
        refill: row.task.refill,
        extraMetadata: override.extraMetadata
      });
    }
    return {
      retention: null,
      taskId: row.taskId,
      task: row.task,
      scoreBand: row.scoreBand,
      stats: makeStats({ 1: 3 }),
      createdAtMillis: row.task?.metadata["server.createdAtMillis"]
        ? Number(row.task.metadata["server.createdAtMillis"])
        : null
    };
  });

  tasks.push(
    buildTask({
      taskId: "mock-finite-running-phone",
      workerGroupId: "scenario-phone-number-workers",
      name: "号码格式标准化批次",
      createdAtMillis: baseTime - 4 * 60_000,
      scoreBand: "running-initial",
      stats: makeStats({ 1: 185, 6: 15 })
    }),
    buildTask({
      taskId: "mock-finite-partial-failed",
      workerGroupId: "scenario-string-utils-workers",
      name: "摘要回执抽检（含部分失败）",
      createdAtMillis: baseTime - 110 * 60_000,
      scoreBand: "terminal",
      retention: makeRetention(
        "FROZEN",
        baseTime - 94 * 60_000,
        baseTime - 34 * 60_000,
        "mock-history-partial-failed"
      ),
      stats: makeStats({ 5: 18, 6: 462, 7: 20 }),
      priority: "60",
      extraMetadata: { env: "staging" }
    }),
    buildTask({
      taskId: "mock-finite-stopped-early",
      workerGroupId: "scenario-phone-number-workers",
      name: "提前终止的号码批任务",
      createdAtMillis: baseTime - 180 * 60_000,
      scoreBand: "terminal",
      retention: makeRetention(
        "RELEASED",
        baseTime - 165 * 60_000,
        baseTime - 105 * 60_000,
        "mock-history-stopped-early"
      ),
      stats: makeStats({ 1: 85, 5: 5, 6: 110 }),
      maxRetryTimes: "2",
      refill: [{ poolName: "any", target: {}, count: 50 }]
    })
  );

  const rpcPresets: Record<
    string,
    { name: string; stats: TaskStats; createdOffsetMs: number }
  > = {
    "scenario-phone-number-workers": {
      name: "号码解析 RPC 通道",
      stats: makeStats({ 1: 6, 5: 3, 6: 418 }),
      createdOffsetMs: 3 * 3_600_000
    },
    "scenario-string-utils-workers": {
      name: "摘要计算 RPC 通道",
      stats: makeStats({ 6: 1_280 }),
      createdOffsetMs: 24 * 3_600_000
    },
    "android-demo-workers": {
      name: "Android 设备状态通道",
      stats: makeStats({ 1: 2, 6: 35 }),
      createdOffsetMs: 12 * 3_600_000
    }
  };
  for (const group of groups) {
    const taskId = rpcTaskId("default", "demo", group.workerGroupId);
    const preset = rpcPresets[group.workerGroupId];
    if (!tasks.some((row) => row.taskId === taskId))
      tasks.push(
        buildTask({
          taskId,
          workerGroupId: group.workerGroupId,
          managed: true,
          name: preset?.name ?? null,
          createdAtMillis: baseTime - (preset?.createdOffsetMs ?? 0),
          scoreBand: "running_visible",
          stats: preset?.stats ?? makeStats({ 1: 2, 6: 14 })
        })
      );
  }

  tasks.push(
    buildTask({
      taskId: "mock-rpc-pending-enable",
      workerGroupId: "scenario-string-utils-workers",
      managed: true,
      name: "待启用摘要通道",
      createdAtMillis: baseTime - 25 * 60_000,
      scoreBand: "pre_review",
      stats: makeStats({}),
      refill: [{ poolName: "any", target: {}, count: 50 }]
    }),
    buildTask({
      taskId: "mock-rpc-deprecated-v0",
      workerGroupId: "scenario-phone-number-workers",
      managed: true,
      name: "旧版号码通道（已停用）",
      createdAtMillis: baseTime - 72 * 3_600_000,
      scoreBand: "terminal",
      stats: makeStats({ 5: 4, 6: 96 }),
      refill: []
    })
  );

  for (const managed of [true, false]) {
    const groupId = agentGroups[0]!.workerGroupId;
    tasks.push(
      buildTask({
        taskId: managed
          ? rpcTaskId("agentforge", "demo", groupId)
          : "mock-agentforge-finite",
        projectId: "agentforge",
        workerGroupId: groupId,
        managed,
        name: managed ? "AgentForge RPC 示例" : "AgentForge 有限任务示例",
        createdAtMillis: baseTime,
        scoreBand: managed ? "running_visible" : "pre_review",
        stats: managed ? makeStats({ 6: 5 }) : makeStats({ 1: 40 }),
        refill: []
      })
    );
  }

  const requireProject = (project: string) => {
    if (!projects.some((p) => p.projectId === project))
      throw new Error("Project 不可用");
  };

  return {
    async list() {
      directory.resolveCaller(caller);
      return structuredClone(projects);
    },
    async tasks(project, _signal, userId) {
      const actor = directory.resolveCaller(caller, project);
      const owner = selectTaskUser(actor, userId);
      requireProject(project);
      const owned = tasks.filter(
        (row) =>
          row.task?.projectId === project &&
          (!owner || row.task.metadata.userId === owner)
      );
      const batch = [...owned]
        .sort(
          (a, b) =>
            taskUserScore(a.task?.metadata.userId) -
              taskUserScore(b.task?.metadata.userId) ||
            compareTaskIds(a.taskId, b.taskId)
        )
        .slice(0, 100);
      batch.sort(
        (a, b) =>
          (b.createdAtMillis ?? -1) - (a.createdAtMillis ?? -1) ||
          compareTaskIds(a.taskId, b.taskId)
      );
      return structuredClone({
        projectId: project,
        truncated: owned.length > 100,
        tasks: batch
      });
    },
    async task(project, taskId) {
      const actor = directory.resolveCaller(caller, project);
      requireProject(project);
      const task = tasks.find(
        (t) => t.taskId === taskId && t.task?.projectId === project
      );
      if (!task) throw new ProjectRequestError(400, 12002);
      requireTaskCaller(actor, { userId: task.task?.metadata.userId });
      return structuredClone(task);
    },
    async stats(project, taskIds) {
      requireProject(project);
      const actor = directory.resolveCaller(caller, project);
      for (const id of taskIds) {
        const task = tasks.find(
          (row) => row.taskId === id && row.task?.projectId === project
        );
        if (!task) throw new ProjectRequestError(400, 12002);
        requireTaskCaller(actor, { userId: task.task?.metadata.userId });
      }
      return Object.fromEntries(
        taskIds.map((id) => {
          const found = tasks.find(
            (row) => row.taskId === id && row.task?.projectId === project
          );
          return [id, structuredClone(found?.stats ?? makeStats({}))];
        })
      );
    },
    async results(project, taskId) {
      const task = await this.task(project, taskId);
      if (task.scoreBand === "pre_review") return { results: [], truncated: false };
      return {
        truncated: false,
        results: [
          {
            messageId: "mock-raw-1",
            item: {
              eventCode: "extension.worker.string.md5",
              payload: { value: "示例" },
              workerSelector: { executorName: "workerId", input: "mock-worker" }
            },
            result: {
              status: "succeeded",
              opaqueResultPayload: '{"workerValue":"原始输出","nested":{"value":42}}'
            }
          },
          { messageId: "mock-raw-2", item: null, result: { status: "failed" } }
        ]
      };
    },
    async groups(project) {
      directory.resolveCaller(caller);
      requireProject(project);
      const known = [...groups, ...agentGroups];
      const declaration = projects.find((entry) => entry.projectId === project)!;
      const ids =
        declaration.workerGroupSource === "registered"
          ? known
              .filter((entry) => entry.workerGroup)
              .map((entry) => entry.workerGroupId)
              .sort()
          : declaration.workerGroupIds;
      return structuredClone(
        ids.map(
          (workerGroupId) =>
            known.find((entry) => entry.workerGroupId === workerGroupId) ?? {
              workerGroupId,
              workerGroup: null,
              matching: { pools: [], functions: [] }
            }
        )
      );
    }
  };
}
