export type WorkerDirectTargetStatus = "observed" | "unobserved" | "rejected";

export type WorkerDirectTargetReason =
  | "timeout"
  | "shutdown"
  | "not-found"
  | "not-bound"
  | "endpoint-mismatch"
  | "command-slot-occupied"
  | "submission-unknown";

export interface WorkerDirectCallRequest {
  workerGroupId: string;
  workerId: string;
  endpointManagerId: string;
  eventName: string;
  payloadText: string;
  waitTimeoutMillis: number;
}

export type WorkerDirectCallTargetResult =
  | {
      status: "observed";
      outcomeCode: string;
      opaqueResultPayload?: string;
    }
  | {
      status: "unobserved" | "rejected";
      reason: WorkerDirectTargetReason;
    };

export interface WorkerDirectCallResult {
  directCallId: string;
  status: "observed" | "partial";
  target: WorkerDirectCallTargetResult;
}

export interface WorkerDirectCallClient {
  callWorker(request: WorkerDirectCallRequest): Promise<WorkerDirectCallResult>;
}
