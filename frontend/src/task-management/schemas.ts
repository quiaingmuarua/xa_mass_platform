import { z } from "zod";

import type { TaskCreateApiResponse, TaskItemsAppendApiResponse } from "./types";

export const taskCreateResponseSchema: z.ZodType<TaskCreateApiResponse> = z
  .object({
    taskId: z.string().min(1)
  })
  .strict();

export const taskItemsAppendResponseSchema: z.ZodType<TaskItemsAppendApiResponse> = z
  .object({
    results: z.record(
      z.string().min(1),
      z.discriminatedUnion("status", [
        z.object({ status: z.literal("succeeded") }).strict(),
        z
          .object({
            status: z.literal("failed"),
            code: z.number().int(),
            message: z.string().min(1)
          })
          .strict()
      ])
    )
  })
  .strict();

export const taskApprovalResponseSchema = z
  .object({
    status: z.enum(["approved", "already_approved"])
  })
  .strict();

export const taskApiErrorResponseSchema = z
  .object({
    code: z.number().int(),
    message: z.string(),
    requestId: z.string().nullable()
  })
  .strict();
