import type {
  TaskPreviewResponse,
  TaskView,
  WorkerGroupView,
  WorkerPreviewResponse,
  WorkerView
} from "./types";

export const MOCK_GENERATED_AT = "2026-07-31T12:00:00.000Z";

export const MOCK_WORKER_GROUPS: WorkerGroupView[] = [
  {
    workerGroupId: "scenario-phone-number-workers",
    attributes: {
      runtime: "java",
      capability: "libphonenumber"
    },
    eventCodes: [
      "extension.worker.phonenumber.country",
      "extension.worker.phonenumber.e164",
      "extension.worker.phonenumber.original-carrier"
    ]
  },
  {
    workerGroupId: "scenario-string-utils-workers",
    attributes: {
      runtime: "java",
      capability: "string-utils"
    },
    eventCodes: [
      "extension.worker.string.base64.encode",
      "extension.worker.string.md5",
      "extension.worker.string.sha1"
    ]
  },
  {
    workerGroupId: "android-demo-workers",
    attributes: {
      capability: "android-demo-state"
    },
    eventCodes: ["extension.worker.android.state.read"]
  }
];

const mockTasks: TaskView[] = [
  mockTask(
    "scenario-rpc-scenario-phone-number-workers",
    "scenario-phone-number-workers"
  ),
  mockTask(
    "scenario-rpc-scenario-string-utils-workers",
    "scenario-string-utils-workers"
  ),
  mockTask("scenario-rpc-android-demo-workers", "android-demo-workers")
];

function mockTask(taskId: string, workerGroupId: string): TaskView {
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

export const MOCK_TASK_PREVIEW: TaskPreviewResponse = {
  sampleLimit: 100,
  generatedAt: MOCK_GENERATED_AT,
  entries: [
    {
      taskId: "mock-finite-awaiting-review",
      scoreBand: "pre_review",
      task: {
        ...mockTasks[0]!,
        taskId: "mock-finite-awaiting-review",
        workerAllocationMechanism: "PRECOMPUTED_TASK_RULE",
        idleDisposition: "CLOSE_WHEN_IDLE",
        allocationRule: {}
      },
      workerGroup: MOCK_WORKER_GROUPS[0]!
    },
    {
      taskId: "mock-finite-initial",
      scoreBand: "running-initial",
      task: {
        ...mockTasks[1]!,
        taskId: "mock-finite-initial",
        workerAllocationMechanism: "PRECOMPUTED_TASK_RULE",
        idleDisposition: "CLOSE_WHEN_IDLE",
        allocationRule: {}
      },
      workerGroup: MOCK_WORKER_GROUPS[1]!
    },
    {
      taskId: mockTasks[2]!.taskId,
      scoreBand: "running_visible",
      task: mockTasks[2]!,
      workerGroup: MOCK_WORKER_GROUPS[2]!
    },
    {
      taskId: "mock-finite-closed",
      scoreBand: "terminal",
      task: {
        ...mockTasks[1]!,
        taskId: "mock-finite-closed",
        workerAllocationMechanism: "PRECOMPUTED_TASK_RULE",
        idleDisposition: "CLOSE_WHEN_IDLE",
        allocationRule: {}
      },
      workerGroup: MOCK_WORKER_GROUPS[1]!
    }
  ]
};

const phoneWorkers = createWorkers(
  "scenario-phone-number-workers",
  "scenario-phone-number-worker-",
  10,
  "libphonenumber"
);
const stringWorkers = createWorkers(
  "scenario-string-utils-workers",
  "scenario-string-utils-worker-",
  5,
  "string-utils"
);

export const MOCK_PREVIEWS: Record<string, WorkerPreviewResponse> = {
  "scenario-phone-number-workers": preview(
    "scenario-phone-number-workers",
    phoneWorkers,
    0
  ),
  "scenario-string-utils-workers": preview(
    "scenario-string-utils-workers",
    stringWorkers,
    1
  ),
  "android-demo-workers": preview("android-demo-workers", [], 0)
};

function createWorkers(
  workerGroupId: string,
  workerIdPrefix: string,
  count: number,
  capability: string
): WorkerView[] {
  return Array.from({ length: count }, (_, index) => ({
    workerId: `${workerIdPrefix}${index + 1}`,
    workerGroupId,
    endpointManagerId: "scenario-websocket",
    workerProperties: {
      runtime: "java",
      capability,
      runtimeVersion: "21",
      slot: index + 1
    },
    platformProperties: {
      assembly: "scenario-workers",
      region: "local-demo"
    }
  }));
}

function preview(
  workerGroupId: string,
  workers: WorkerView[],
  unreadableCount: number
): WorkerPreviewResponse {
  return {
    workerGroupId,
    sampleLimit: 100,
    sampledCount: workers.length + unreadableCount,
    returnedCount: workers.length,
    unreadableCount,
    generatedAt: MOCK_GENERATED_AT,
    workers
  };
}
