import { z } from "zod";

import type {
  JsonValue,
  TaskPreviewResponse,
  WorkerGroupPreviewResponse,
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
    config: z.record(z.string(), z.string())
  })
  .strict();

const taskRuntimePreviewEntrySchema = z
  .object({
    taskId: z.string().min(1),
    scoreBand: z.enum([
      "pre_review",
      "admission_visible",
      "running_visible",
      "terminal"
    ]),
    task: taskViewSchema.nullable(),
    workerGroup: workerGroupViewSchema.nullable()
  })
  .strict()
  .superRefine((entry, context) => {
    if (entry.task === null && entry.workerGroup !== null) {
      context.addIssue({
        code: "custom",
        message: "A missing Task descriptor cannot expose a WorkerGroup descriptor"
      });
    }
    if (entry.task !== null && entry.task.taskId !== entry.taskId) {
      context.addIssue({
        code: "custom",
        message: "Task descriptor identity does not match the score coordinate"
      });
    }
    if (
      entry.workerGroup !== null &&
      entry.task !== null &&
      entry.workerGroup.workerGroupId !== entry.task.workerGroupId
    ) {
      context.addIssue({
        code: "custom",
        message: "WorkerGroup descriptor identity does not match the Task"
      });
    }
  });

export const taskPreviewResponseSchema: z.ZodType<TaskPreviewResponse> = z
  .object({
    sampleLimit: z.number().int().min(1).max(100),
    generatedAt: z.string().datetime({ offset: true }),
    entries: z.array(taskRuntimePreviewEntrySchema)
  })
  .strict()
  .superRefine((response, context) => {
    const taskIds = response.entries.map((entry) => entry.taskId);
    if (response.entries.length > response.sampleLimit) {
      context.addIssue({
        code: "custom",
        message: "Task preview exceeds sampleLimit"
      });
    }
    if (new Set(taskIds).size !== taskIds.length) {
      context.addIssue({
        code: "custom",
        message: "Task preview contains duplicate coordinates"
      });
    }
  });

export const workerGroupPreviewResponseSchema: z.ZodType<WorkerGroupPreviewResponse> = z
  .object({
    sampleLimit: z.number().int().min(1).max(100),
    sampledCount: z.number().int().nonnegative(),
    returnedCount: z.number().int().nonnegative(),
    unreadableCount: z.number().int().nonnegative(),
    generatedAt: z.string().datetime({ offset: true }),
    workerGroups: z.array(workerGroupViewSchema)
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
        message: "WorkerGroup preview counts are inconsistent"
      });
    }
    if (response.workerGroups.length !== response.returnedCount) {
      context.addIssue({
        code: "custom",
        message: "workerGroups length differs from returnedCount"
      });
    }
    if (
      new Set(response.workerGroups.map((group) => group.workerGroupId)).size !==
      response.workerGroups.length
    ) {
      context.addIssue({
        code: "custom",
        message: "preview contains duplicate WorkerGroup identities"
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
