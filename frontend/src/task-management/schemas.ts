import { z } from "zod";

import { taskRetentionSchema } from "./retention";
import type {
  TaskCreateApiResponse,
  TaskItemsAppendApiResponse,
  TaskRetentionRunResult
} from "./types";

export const taskCreateResponseSchema: z.ZodType<TaskCreateApiResponse> = z
  .object({
    taskId: z.string().min(1)
  })
  .strict();

export const taskItemsAppendResponseSchema: z.ZodType<TaskItemsAppendApiResponse> =
  z.record(
    z.string().min(1),
    z.discriminatedUnion("status", [
      z.object({ status: z.literal("applied") }).strict(),
      z
        .object({
          status: z.literal("rejected"),
          code: z.number().int(),
          message: z.string().min(1)
        })
        .strict()
    ])
  );

export const taskActionOutcomeSchema = z.discriminatedUnion("status", [
  z.object({ status: z.literal("applied") }).strict(),
  z.object({ status: z.literal("unchanged") }).strict()
]);

export const taskRetentionRunResponseSchema: z.ZodType<TaskRetentionRunResult> = z
  .object({
    outcome: taskActionOutcomeSchema,
    retention: taskRetentionSchema.nullable()
  })
  .strict();

export const taskApiErrorResponseSchema = z
  .object({
    code: z.number().int(),
    message: z.string(),
    requestId: z.string().nullable(),
    taskId: z.string().min(1).optional()
  })
  .strict();
