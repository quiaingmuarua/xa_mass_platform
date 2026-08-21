import { z } from "zod";

import type {
  ConfiguredRuntimeResourcesResponse,
  JsonValue,
  WorkerPreviewResponse
} from "./types";

export const jsonValueSchema: z.ZodType<JsonValue> = z.lazy(() =>
  z.union([
    z.null(),
    z.boolean(),
    z.number(),
    z.string(),
    z.array(jsonValueSchema),
    z.record(z.string(), jsonValueSchema)
  ])
);

const attributesSchema = z.record(z.string(), jsonValueSchema);

export const workerGroupViewSchema = z
  .object({
    workerGroupId: z.string().min(1),
    attributes: attributesSchema,
    eventCodes: z.array(z.string().min(1))
  })
  .strict();

const taskViewSchema = z
  .object({
    taskId: z.string().min(1),
    workerGroupId: z.string().min(1),
    workerAllocationMechanism: z.enum(["PRECOMPUTED_TASK_RULE", "DIRECT_ITEM_RULE"]),
    idleDisposition: z.enum(["CLOSE_WHEN_IDLE", "PARK_WHEN_IDLE"]),
    allocationRule: attributesSchema.nullable(),
    config: z
      .object({
        priority: z.string().regex(/^\d+$/),
        maximumCandidateWorkers: z.string().regex(/^\d+$/),
        maxRetryTimes: z.string().regex(/^\d+$/)
      })
      .strict()
  })
  .strict();

const configuredRuntimeResourceEntrySchema = z
  .object({
    workerGroupId: z.string().min(1),
    taskId: z.string().min(1),
    workerGroup: workerGroupViewSchema.nullable(),
    task: taskViewSchema.nullable()
  })
  .strict()
  .superRefine((entry, context) => {
    if (
      entry.workerGroup !== null &&
      entry.workerGroup.workerGroupId !== entry.workerGroupId
    ) {
      context.addIssue({
        code: "custom",
        message: "Configured WorkerGroup identity does not match its coordinate"
      });
    }
    if (
      entry.task !== null &&
      (entry.task.taskId !== entry.taskId ||
        entry.task.workerGroupId !== entry.workerGroupId)
    ) {
      context.addIssue({
        code: "custom",
        message: "Configured Task identity does not match its coordinate"
      });
    }
  });

export const configuredRuntimeResourcesResponseSchema: z.ZodType<ConfiguredRuntimeResourcesResponse> =
  z
    .object({
      entries: z.array(configuredRuntimeResourceEntrySchema)
    })
    .strict()
    .superRefine((response, context) => {
      const groupIds = response.entries.map((entry) => entry.workerGroupId);
      const taskIds = response.entries.map((entry) => entry.taskId);
      if (
        new Set(groupIds).size !== groupIds.length ||
        new Set(taskIds).size !== taskIds.length
      ) {
        context.addIssue({
          code: "custom",
          message: "Configured resources contain duplicate coordinates"
        });
      }
    });

export const workerViewSchema = z
  .object({
    workerId: z.string().min(1),
    workerGroupId: z.string().min(1),
    endpointManagerId: z.string().min(1),
    workerProperties: attributesSchema,
    platformProperties: attributesSchema
  })
  .strict();

export const workerPreviewResponseSchema: z.ZodType<WorkerPreviewResponse> = z
  .object({
    workerGroupId: z.string().min(1),
    sampleLimit: z.number().int().min(1).max(100),
    sampledCount: z.number().int().nonnegative(),
    returnedCount: z.number().int().nonnegative(),
    unreadableCount: z.number().int().nonnegative(),
    generatedAt: z.string().datetime({ offset: true }),
    workers: z.array(workerViewSchema)
  })
  .strict()
  .superRefine((response, context) => {
    if (response.sampledCount > response.sampleLimit) {
      context.addIssue({
        code: "custom",
        message: "sampledCount exceeds sampleLimit"
      });
    }
    if (response.returnedCount + response.unreadableCount !== response.sampledCount) {
      context.addIssue({
        code: "custom",
        message: "preview counts are inconsistent"
      });
    }
    if (response.workers.length !== response.returnedCount) {
      context.addIssue({
        code: "custom",
        message: "workers length differs from returnedCount"
      });
    }
    if (
      response.workers.some((worker) => worker.workerGroupId !== response.workerGroupId)
    ) {
      context.addIssue({
        code: "custom",
        message: "Worker belongs to another WorkerGroup"
      });
    }
    if (
      new Set(response.workers.map((worker) => worker.workerId)).size !==
      response.workers.length
    ) {
      context.addIssue({
        code: "custom",
        message: "preview contains duplicate Worker identities"
      });
    }
  });

export const apiErrorResponseSchema = z
  .object({
    code: z.number().int(),
    message: z.string(),
    requestId: z.string().nullable()
  })
  .strict();
