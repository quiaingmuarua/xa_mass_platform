import { z } from "zod";
import {
  callerHeaders,
  requireCaller,
  TaskApiError,
  type TaskCaller
} from "@/task-caller";
import type { MockUserDirectorySource } from "@/user-directory-source";
import { MOCK_PREVIEWS } from "./mock-data";

const milliseconds = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
export const connectionControlSchema = z
  .strictObject({
    workerGroupId: z.string().min(1),
    workerId: z.string().min(1),
    transportType: z.string().nullable(),
    supported: z.boolean(),
    unsupportedReason: z.string().nullable(),
    status: z.enum(["allowed", "cooldown"]),
    revision: z.string().min(1).nullable(),
    requestId: z.string().min(1).nullable(),
    cooldownUntilMillis: milliseconds.nullable(),
    reason: z.string().max(256).nullable(),
    updatedAt: milliseconds.nullable(),
    readAt: milliseconds
  })
  .superRefine((value, context) => {
    if (
      (value.status === "cooldown") !==
      (value.cooldownUntilMillis !== null && value.cooldownUntilMillis > value.readAt)
    )
      context.addIssue({ code: "custom", message: "接入状态与 Server 读取时间不一致" });
    if (
      value.supported &&
      (value.transportType !== "WEBSOCKET" || value.unsupportedReason !== null)
    )
      context.addIssue({ code: "custom", message: "接入控制支持范围不一致" });
  });
export type WorkerConnectionControl = z.infer<typeof connectionControlSchema>;

const changeSchema = z.strictObject({
  requestId: z.string().trim().min(1).max(128),
  expectedRevision: z.string().min(1).max(128).nullable(),
  cooldownUntilMillis: milliseconds.positive().nullable(),
  reason: z.string().max(256)
});
export type WorkerConnectionChange = z.infer<typeof changeSchema>;

export class ConnectionControlUnknown extends Error {
  constructor() {
    super("保存或断连结果未确认。保留原请求，请读取核对后显式重试；不会自动重提。");
  }
}

export interface WorkerConnectionControlSource {
  read(
    caller: TaskCaller,
    group: string,
    worker: string,
    signal?: AbortSignal
  ): Promise<WorkerConnectionControl>;
  save(
    caller: TaskCaller,
    group: string,
    worker: string,
    input: WorkerConnectionChange
  ): Promise<WorkerConnectionControl>;
}

export class ApiWorkerConnectionControlSource implements WorkerConnectionControlSource {
  constructor(private readonly baseUrl = "/api") {}

  private async request(
    caller: TaskCaller,
    group: string,
    worker: string,
    input?: WorkerConnectionChange,
    signal?: AbortSignal
  ) {
    const headers = callerHeaders(requireCaller(caller));
    let response: Response;
    try {
      response = await fetch(
        `${this.baseUrl}/v1/worker-groups/${encodeURIComponent(group)}/workers/${encodeURIComponent(worker)}/connection-control`,
        {
          method: input ? "PUT" : "GET",
          headers: {
            ...headers,
            ...(input ? { "Content-Type": "application/json" } : {})
          },
          ...(input ? { body: JSON.stringify(input) } : {}),
          signal: signal
            ? AbortSignal.any([signal, AbortSignal.timeout(10000)])
            : AbortSignal.timeout(10000)
        }
      );
    } catch (cause) {
      if (input) throw new ConnectionControlUnknown();
      throw cause;
    }
    if (!response.ok) {
      if (input && ![400, 401, 403, 404, 409].includes(response.status))
        throw new ConnectionControlUnknown();
      throw new TaskApiError(
        response.status,
        response.status === 409
          ? "接入控制已变化，或同一请求身份的内容冲突，请重新读取"
          : response.status === 403
            ? "接入控制仅限管理员"
            : response.status === 400
              ? "Worker、Transport 或接入控制参数不符合要求"
              : `无法读取或修改 Worker 接入控制 (${response.status})`
      );
    }
    try {
      const result = connectionControlSchema.parse(await response.json());
      if (result.workerGroupId !== group || result.workerId !== worker)
        throw new Error("Worker 接入控制身份不一致");
      if (
        input &&
        (result.requestId !== input.requestId ||
          result.cooldownUntilMillis !== input.cooldownUntilMillis ||
          result.reason !== input.reason)
      )
        throw new ConnectionControlUnknown();
      return result;
    } catch (cause) {
      if (input) throw new ConnectionControlUnknown();
      throw cause;
    }
  }

  read(caller: TaskCaller, group: string, worker: string, signal?: AbortSignal) {
    return this.request(caller, group, worker, undefined, signal);
  }
  save(
    caller: TaskCaller,
    group: string,
    worker: string,
    input: WorkerConnectionChange
  ) {
    return this.request(caller, group, worker, changeSchema.parse(input));
  }
}

type MockWorker = {
  workerGroupId: string;
  workerId: string;
  transportType: string;
  managed: boolean;
};
type SavedControl = {
  input: WorkerConnectionChange;
  revision: string;
  actor: string;
  updatedAt: number;
};
const coordinate = (group: string, worker: string) => JSON.stringify([group, worker]);

/** Explicit frontend-only policy fixtures; never alters the independent network/score samples. */
export class MockWorkerConnectionControlSource
  implements WorkerConnectionControlSource
{
  private readonly saved = new Map<string, SavedControl>();
  private loseSave = false;
  private failRead = false;
  constructor(
    private readonly directory: MockUserDirectorySource,
    private readonly now: () => number = Date.now,
    private readonly workers: readonly MockWorker[] = Object.values(
      MOCK_PREVIEWS
    ).flatMap((preview) =>
      preview.workers.map((worker) => ({
        ...worker,
        transportType: "WEBSOCKET",
        managed: true
      }))
    )
  ) {}

  loseNextSaveResponse() {
    this.loseSave = true;
  }
  failNextRead() {
    this.failRead = true;
  }

  private target(caller: TaskCaller, group: string, worker: string) {
    const actor = this.directory.resolveCaller(caller, undefined, "runtime.view");
    if (actor.role !== "admin") throw new TaskApiError(403, "接入控制仅限管理员");
    const target = this.workers.find(
      (value) => value.workerGroupId === group && value.workerId === worker
    );
    if (!target) throw new TaskApiError(400, "Worker 不存在或不属于此 Group");
    return { actor, target };
  }

  async read(caller: TaskCaller, group: string, worker: string, signal?: AbortSignal) {
    const { target } = this.target(caller, group, worker);
    signal?.throwIfAborted();
    if (this.failRead) {
      this.failRead = false;
      throw new TaskApiError(503, "Mock 接入状态读取失败");
    }
    return this.view(target);
  }

  private view(target: MockWorker): WorkerConnectionControl {
    const current = this.saved.get(coordinate(target.workerGroupId, target.workerId));
    const readAt = this.now();
    const until = current?.input.cooldownUntilMillis ?? null;
    const supported = target.transportType === "WEBSOCKET" && target.managed;
    return {
      workerGroupId: target.workerGroupId,
      workerId: target.workerId,
      transportType: target.transportType,
      supported,
      unsupportedReason: supported
        ? null
        : target.transportType !== "WEBSOCKET"
          ? "websocket_required"
          : "adapter_not_running_here",
      status: until !== null && until > readAt ? "cooldown" : "allowed",
      revision: current?.revision ?? null,
      requestId: current?.input.requestId ?? null,
      cooldownUntilMillis: until,
      reason: current?.input.reason ?? null,
      updatedAt: current?.updatedAt ?? null,
      readAt
    };
  }

  async save(
    caller: TaskCaller,
    group: string,
    worker: string,
    value: WorkerConnectionChange
  ) {
    const { actor, target } = this.target(caller, group, worker);
    const input = changeSchema.parse(value);
    if (!this.view(target).supported)
      throw new TaskApiError(400, "此 Transport 或 Adapter 不支持接入冷却");
    const key = coordinate(group, worker);
    const current = this.saved.get(key);
    if (current?.input.requestId === input.requestId) {
      if (
        current.actor !== actor.userId ||
        JSON.stringify(current.input) !== JSON.stringify(input)
      )
        throw new TaskApiError(409, "原请求内容不同");
    } else {
      if ((current?.revision ?? null) !== input.expectedRevision)
        throw new TaskApiError(409, "接入控制修订冲突");
      if (input.cooldownUntilMillis !== null && input.cooldownUntilMillis <= this.now())
        throw new TaskApiError(400, "新的冷却截止时间必须在未来");
      this.saved.set(key, {
        input: Object.freeze({ ...input }),
        revision: crypto.randomUUID(),
        actor: actor.userId,
        updatedAt: this.now()
      });
    }
    if (this.loseSave) {
      this.loseSave = false;
      throw new ConnectionControlUnknown();
    }
    return this.view(target);
  }
}

export function normalizeConnectionChange(input: WorkerConnectionChange) {
  return Object.freeze(changeSchema.parse(input));
}
