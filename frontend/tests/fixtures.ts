import type {
  ConfiguredRuntimeResourceEntry,
  TaskView,
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

export function configuredEntry(
  workerGroupId: string,
  options: { missingGroup?: boolean; missingTask?: boolean } = {}
): ConfiguredRuntimeResourceEntry {
  const taskId = `scenario-rpc-${workerGroupId}`;
  return {
    workerGroupId,
    taskId,
    workerGroup: options.missingGroup ? null : workerGroup(workerGroupId),
    task: options.missingTask ? null : task(taskId, workerGroupId)
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
