import axios, { type AxiosInstance } from "axios";
import { z } from "zod";

import type { WorkerView } from "@/runtime-viewer/types";

import type {
  WorkerNetworkObservation,
  WorkerSchedulingObservation,
  WorkerStatusDataSource
} from "./types";

const networkStateSchema = z.enum(["connected", "disconnected", "unknown"]);

const networkResponseSchema = z
  .object({
    endpointManagerId: z.string().min(1),
    readAt: z.string().datetime({ offset: true }),
    statesByWorkerId: z.record(z.string().min(1), networkStateSchema)
  })
  .strict();

const schedulingStateSchema = z.enum([
  "hot-score-overdue",
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

  constructor(baseUrl: string, client?: AxiosInstance) {
    this.client =
      client ??
      axios.create({
        baseURL: baseUrl,
        timeout: 5_000,
        headers: { "Content-Type": "application/json" }
      });
  }

  async observeNetwork(
    workers: WorkerView[],
    signal?: AbortSignal
  ): Promise<WorkerNetworkObservation[]> {
    requireNetworkWorkers(workers);
    const workerIdsByEndpoint = new Map<string, string[]>();
    workers.forEach((worker) => {
      const workerIds = workerIdsByEndpoint.get(worker.endpointManagerId) ?? [];
      workerIds.push(worker.workerId);
      workerIdsByEndpoint.set(worker.endpointManagerId, workerIds);
    });
    try {
      const batches = await Promise.all(
        Array.from(workerIdsByEndpoint, async ([endpointManagerId, workerIds]) => {
          const response = await this.client.post(
            `/v1/runtime-view/endpoint-managers/${encodeURIComponent(
              endpointManagerId
            )}/workers:network-observe`,
            { workerIds },
            {
              signal,
              headers: { "X-Request-Id": createRequestId("worker-network") }
            }
          );
          const parsed = networkResponseSchema.safeParse(response.data);
          if (!parsed.success || parsed.data.endpointManagerId !== endpointManagerId) {
            throw new Error("Worker Network response is invalid");
          }
          requireReturnedIds(workerIds, parsed.data.statesByWorkerId, "Worker Network");
          return parsed.data;
        })
      );
      const observations = new Map<string, WorkerNetworkObservation>();
      batches.forEach((batch) => {
        Object.entries(batch.statesByWorkerId).forEach(([workerId, state]) => {
          observations.set(workerId, {
            workerId,
            endpointManagerId: batch.endpointManagerId,
            state,
            readAt: batch.readAt
          });
        });
      });
      return workers.map((worker) => observations.get(worker.workerId)!);
    } catch (error) {
      if (signal?.aborted || axios.isCancel(error)) {
        throw abortError();
      }
      throw error;
    }
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
      requireReturnedIds(workerIds, parsed.data.statesByWorkerId, "Worker Scheduling");
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

function requireNetworkWorkers(workers: WorkerView[]): void {
  if (
    workers.length < 1 ||
    workers.length > 100 ||
    new Set(workers.map((worker) => worker.workerId)).size !== workers.length ||
    workers.some((worker) => worker.endpointManagerId.length === 0)
  ) {
    throw new Error("Worker Network observation requires 1..100 unique ids");
  }
}

function requireReturnedIds(
  workerIds: string[],
  statesByWorkerId: Record<string, string>,
  owner: string
): void {
  const returnedIds = Object.keys(statesByWorkerId);
  if (
    returnedIds.length !== workerIds.length ||
    workerIds.some((workerId) => !(workerId in statesByWorkerId))
  ) {
    throw new Error(`${owner} response identities do not match`);
  }
}

function createRequestId(prefix = "worker-scheduling"): string {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function abortError(): Error {
  const error = new Error("Worker status observation was cancelled");
  error.name = "AbortError";
  return error;
}
