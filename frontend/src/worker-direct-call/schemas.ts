import { z } from "zod";

const targetReasonSchema = z.enum([
  "timeout",
  "shutdown",
  "not-found",
  "not-bound",
  "endpoint-mismatch",
  "command-slot-occupied",
  "submission-unknown"
]);

export const workerDirectTargetResultSchema = z.discriminatedUnion("status", [
  z
    .object({
      status: z.literal("observed"),
      outcomeCode: z.string().min(1),
      opaqueResultPayload: z.string().optional()
    })
    .strict(),
  z
    .object({
      status: z.literal("unobserved"),
      reason: targetReasonSchema
    })
    .strict(),
  z
    .object({
      status: z.literal("rejected"),
      reason: targetReasonSchema
    })
    .strict()
]);

export const workerDirectCallResponseSchema = z
  .object({
    directCallId: z.string().min(1),
    status: z.enum(["observed", "partial"]),
    results: z.record(z.string().min(1), workerDirectTargetResultSchema)
  })
  .strict();

export const workerDirectCallApiErrorSchema = z
  .object({
    code: z.number().int(),
    message: z.string(),
    requestId: z.string().nullable()
  })
  .strict();
