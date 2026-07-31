import type { WorkerGroupView, WorkerPreviewResponse, WorkerView } from "./types";

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
    ],
    itemAllocationFields: []
  },
  {
    workerGroupId: "scenario-string-utils-workers",
    attributes: {
      runtime: "java",
      capability: "string-utils"
    },
    eventCodes: ["string.base64.encode", "string.md5", "string.sha1"],
    itemAllocationFields: []
  }
];

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
  )
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
    attributes: {
      runtime: "java",
      capability,
      runtimeVersion: "21",
      slot: index + 1
    },
    platformAttributes: {
      assembly: "scenario-workers",
      region: "local-demo"
    },
    dynamicAttributeNames: []
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
