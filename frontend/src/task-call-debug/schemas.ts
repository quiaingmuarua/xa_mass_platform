import { z } from "zod";

import type { TaskCallDebugOutcome } from "./types";

export const taskCallDebugOutcomeSchema: z.ZodType<TaskCallDebugOutcome> =
  z.discriminatedUnion("status", [
    z
      .object({
        status: z.literal("succeeded"),
        opaqueResultPayload: z.string()
      })
      .strict(),
    z.object({ status: z.literal("failed") }).strict(),
    z.object({ status: z.literal("not_observed") }).strict()
  ]);

export const taskCallDebugResultsSchema = z.record(
  z.string().min(1),
  taskCallDebugOutcomeSchema
);

export const taskCallDebugApiErrorSchema = z
  .object({
    code: z.number().int(),
    message: z.string(),
    requestId: z.string().nullable()
  })
  .strict();
