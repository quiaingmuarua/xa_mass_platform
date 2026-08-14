import { z } from "zod";

import type { TaskBatchInputUploadResponse, TaskBatchRunResponse } from "./types";

const identifier = z.string().min(1);
const nonnegativeInteger = z.number().int().nonnegative();

export const taskBatchInputUploadResponseSchema: z.ZodType<TaskBatchInputUploadResponse> =
  z
    .object({
      fileName: identifier,
      byteCount: nonnegativeInteger,
      lineCount: nonnegativeInteger
    })
    .strict();

export const taskBatchRunResponseSchema: z.ZodType<TaskBatchRunResponse> = z
  .object({
    runId: identifier,
    workerGroupId: identifier,
    eventCode: identifier,
    payloadKey: identifier,
    status: z.enum(["succeeded", "partial"]),
    inputFile: identifier,
    inputCount: nonnegativeInteger,
    resultCount: nonnegativeInteger,
    remainingCount: nonnegativeInteger,
    loadRounds: nonnegativeInteger,
    durationMillis: nonnegativeInteger,
    outputFile: identifier
  })
  .strict()
  .superRefine((response, context) => {
    if (response.resultCount + response.remainingCount !== response.inputCount) {
      context.addIssue({
        code: "custom",
        message: "Task Batch result counts are inconsistent"
      });
    }
    if (response.status === "succeeded" && response.remainingCount !== 0) {
      context.addIssue({
        code: "custom",
        message: "Succeeded Task Batch still has remaining results"
      });
    }
  });

export const taskBatchApiErrorResponseSchema = z
  .object({
    code: z.number().int(),
    message: z.string(),
    requestId: z.string().nullable()
  })
  .strict();
