import { z } from "zod";

import type {
  JsonValue,
  WorkerGroupBatchGetResponse,
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
    eventCodes: z.array(z.string().min(1)),
    itemAllocationFields: z.array(z.string().min(1))
  })
  .strict();

export const workerGroupBatchGetResponseSchema: z.ZodType<WorkerGroupBatchGetResponse> =
  z
    .object({
      workerGroups: z.array(workerGroupViewSchema),
      missingWorkerGroupIds: z.array(z.string().min(1))
    })
    .strict()
    .superRefine((response, context) => {
      const allIds = [
        ...response.workerGroups.map((group) => group.workerGroupId),
        ...response.missingWorkerGroupIds
      ];
      if (new Set(allIds).size !== allIds.length) {
        context.addIssue({
          code: "custom",
          message: "WorkerGroup response contains duplicate identities"
        });
      }
    });

export const workerViewSchema = z
  .object({
    workerId: z.string().min(1),
    workerGroupId: z.string().min(1),
    endpointManagerId: z.string().min(1),
    attributes: attributesSchema,
    platformAttributes: attributesSchema,
    dynamicAttributeNames: z.array(z.string().min(1))
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
