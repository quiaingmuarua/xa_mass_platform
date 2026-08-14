import type {
  ConfiguredRuntimeResourcesResponse,
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
      "phonenumber.country",
      "phonenumber.e164",
      "phonenumber.original-carrier"
    ]
  },
  {
    workerGroupId: "scenario-string-utils-workers",
    attributes: {
      runtime: "java",
      capability: "string-utils"
    },
    eventCodes: ["string.base64.encode", "string.md5", "string.sha1"]
  },
  {
    workerGroupId: "android-demo-workers",
    attributes: {
      capability: "android-demo-state"
    },
    eventCodes: ["android.demo.state.read"]
  }
];

const mockTasks: TaskView[] = MOCK_WORKER_GROUPS.map((group) => ({
  taskId: `scenario-rpc-${group.workerGroupId}`,
  workerGroupId: group.workerGroupId,
  taskType: "ITEM_DRIVEN",
  allocationRule: null,
  config: {
    priority: "0",
    maximumCandidateWorkers: "1",
    maxRetryTimes: "3"
  },
  emptyCloseAtMillis: 9_999_999_999_900
}));

export const MOCK_CONFIGURED_RESOURCES: ConfiguredRuntimeResourcesResponse = {
  entries: MOCK_WORKER_GROUPS.map((workerGroup, index) => ({
    workerGroupId: workerGroup.workerGroupId,
    taskId: mockTasks[index]!.taskId,
    workerGroup,
    task: mockTasks[index]!
  }))
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
