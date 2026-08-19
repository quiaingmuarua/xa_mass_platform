import axios, { type AxiosInstance } from "axios";
import { z } from "zod";

import type { WorkerView } from "@/runtime-viewer/types";

import { MockWorkerStatusDataSource } from "./mock-data-source";
import type {
  WorkerNetworkObservation,
  WorkerSchedulingObservation,
  WorkerStatusDataSource
} from "./types";

const schedulingStateSchema = z.enum([
  "due-hot",
  "held-hot",
  "paused",
  "recovery",
  "cold",
  "missing"
]);

const schedulingResponseSchema = z
  .object({
    workerGroupId: z.string().min(1),
    readAt: z.string().datetime({ offset: true }),
    statesByWorkerId: z.record(z.string().min(1), schedulingStateSchema)
  })
  .strict();

export class ApiWorkerStatusDataSource implements WorkerStatusDataSource {
  private readonly client: AxiosInstance;
  private readonly networkMock: MockWorkerStatusDataSource;

  constructor(
    baseUrl: string,
    client?: AxiosInstance,
    networkMock = new MockWorkerStatusDataSource()
  ) {
    this.client =
      client ??
      axios.create({
        baseURL: baseUrl,
        timeout: 5_000,
        headers: { "Content-Type": "application/json" }
      });
    this.networkMock = networkMock;
  }

  observeNetwork(
    workers: WorkerView[],
    signal?: AbortSignal
  ): Promise<WorkerNetworkObservation[]> {
    return this.networkMock.observeNetwork(workers, signal);
  }

  async observeScheduling(
    workerGroupId: string,
    workerIds: string[],
    signal?: AbortSignal
  ): Promise<WorkerSchedulingObservation[]> {
    if (
      workerIds.length < 1 ||
      workerIds.length > 100 ||
      new Set(workerIds).size !== workerIds.length
    ) {
      throw new Error("Worker Scheduling observation requires 1..100 unique ids");
    }
    try {
      const response = await this.client.post(
        `/v1/runtime-view/worker-groups/${encodeURIComponent(
          workerGroupId
        )}/workers:scheduling-observe`,
        { workerIds },
        {
          signal,
          headers: { "X-Request-Id": createRequestId() }
        }
      );
      const parsed = schedulingResponseSchema.safeParse(response.data);
      if (!parsed.success || parsed.data.workerGroupId !== workerGroupId) {
        throw new Error("Worker Scheduling response is invalid");
      }
      const returnedIds = Object.keys(parsed.data.statesByWorkerId);
      if (
        returnedIds.length !== workerIds.length ||
        workerIds.some((workerId) => !(workerId in parsed.data.statesByWorkerId))
      ) {
        throw new Error("Worker Scheduling response identities do not match");
      }
      return workerIds.map((workerId) => ({
        workerId,
        workerGroupId,
        state: parsed.data.statesByWorkerId[workerId]!,
        readAt: parsed.data.readAt
      }));
    } catch (error) {
      if (signal?.aborted || axios.isCancel(error)) {
        throw abortError();
      }
      throw error;
    }
  }
}

function createRequestId(): string {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return `worker-scheduling-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function abortError(): Error {
  const error = new Error("Worker status observation was cancelled");
  error.name = "AbortError";
  return error;
}
