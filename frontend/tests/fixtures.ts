import type {
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
    eventCodes: [`${workerGroupId}.event`],
    itemAllocationFields: []
  };
}

export function worker(workerGroupId: string, workerId: string): WorkerView {
  return {
    workerId,
    workerGroupId,
    endpointManagerId: "endpoint-test",
    attributes: {
      slot: 1
    },
    platformAttributes: {
      runtime: "test"
    },
    dynamicAttributeNames: []
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
