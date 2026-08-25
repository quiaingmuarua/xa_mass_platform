import type {
  TaskPreviewResponse,
  TaskRuntimePreviewEntry,
  TaskScoreBand,
  TaskView,
  WorkerGroupPreviewResponse,
  WorkerGroupView,
  WorkerPreviewResponse,
  WorkerView
} from "@/runtime-viewer/types";

export function workerGroup(workerGroupId: string): WorkerGroupView {
  return {
    workerGroupId,
    attributes: {
      runtime: "test"
    },
    eventCodes: [`${workerGroupId}.event`]
  };
}

export function task(taskId: string, workerGroupId: string): TaskView {
  return {
    taskId,
    workerGroupId,
    workerAllocationMechanism: "DIRECT_ITEM_RULE",
    idleDisposition: "PARK_WHEN_IDLE",
    allocationRule: null,
    config: {
      priority: "0",
      maximumCandidateWorkers: "1",
      maxRetryTimes: "3"
    }
  };
}

export function taskPreviewEntry(
  workerGroupId: string,
  options: {
    missingGroup?: boolean;
    missingTask?: boolean;
    taskId?: string;
    scoreBand?: TaskScoreBand;
  } = {}
): TaskRuntimePreviewEntry {
  const taskId = options.taskId ?? `scenario-rpc-${workerGroupId}`;
  return {
    taskId,
    scoreBand: options.scoreBand ?? "running_visible",
    task: options.missingTask ? null : task(taskId, workerGroupId),
    workerGroup:
      options.missingTask || options.missingGroup ? null : workerGroup(workerGroupId)
  };
}

export function taskPreview(
  entries: TaskRuntimePreviewEntry[],
  generatedAt = "2026-07-31T12:00:00.000Z"
): TaskPreviewResponse {
  return {
    sampleLimit: 100,
    generatedAt,
    entries
  };
}

export function worker(workerGroupId: string, workerId: string): WorkerView {
  return {
    workerId,
    workerGroupId,
    endpointManagerId: "endpoint-test",
    workerProperties: {
      slot: 1
    },
    platformProperties: {
      runtime: "test"
    }
  };
}

export function groupPreview(
  workerGroupIds: string[],
  unreadableCount = 0,
  generatedAt = "2026-07-31T12:00:00.000Z"
): WorkerGroupPreviewResponse {
  return {
    sampleLimit: 100,
    sampledCount: workerGroupIds.length + unreadableCount,
    returnedCount: workerGroupIds.length,
    unreadableCount,
    generatedAt,
    workerGroups: workerGroupIds.map(workerGroup)
  };
}

export function preview(
  workerGroupId: string,
  workers: WorkerView[],
  unreadableCount = 0,
  generatedAt = "2026-07-31T12:00:00.000Z"
): WorkerPreviewResponse {
  return {
    workerGroupId,
    sampleLimit: 100,
    sampledCount: workers.length + unreadableCount,
    returnedCount: workers.length,
    unreadableCount,
    generatedAt,
    workers
  };
}
