import { z } from "zod";

import type { TaskCreateApiResponse, TaskItemsAppendApiResponse } from "./types";

export const taskCreateResponseSchema: z.ZodType<TaskCreateApiResponse> = z
  .object({
    taskId: z.string().min(1),
    status: z.literal("created")
  })
  .strict();

export const taskItemsAppendResponseSchema: z.ZodType<TaskItemsAppendApiResponse> = z
  .object({
    results: z.record(
      z.string().min(1),
      z
        .object({
          status: z.string().min(1),
          reason: z.string().nullable()
        })
        .strict()
    )
  })
  .strict();

export const taskApprovalResponseSchema = z
  .object({
    status: z.enum(["approved", "already_approved"]),
    reason: z.string().nullable()
  })
  .strict();

export const taskExportNotReadySchema = z
  .object({ status: z.literal("not_ready") })
  .strict();

export const taskApiErrorResponseSchema = z
  .object({
    code: z.number().int(),
    message: z.string(),
    requestId: z.string().nullable()
  })
  .strict();
