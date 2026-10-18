import { z } from "zod";
import { TaskApiError } from "@/task-caller";

export const MAX_CREDITS = Number.MAX_SAFE_INTEGER;
const requestIdentity = z.string().regex(/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
const nonnegative = z.number().int().min(0).max(MAX_CREDITS);
export const accountSchema = z.object({
  userId: z.string(),
  balance: nonnegative,
  latestSequence: nonnegative,
  updatedAtMillis: nonnegative.nullable(),
  userPresent: z.boolean()
});
export const transactionSchema = z.object({
  sequence: nonnegative.positive(),
  requestId: requestIdentity,
  userId: z.string(),
  actorUserId: z.string(),
  delta: z
    .number()
    .int()
    .min(-MAX_CREDITS)
    .max(MAX_CREDITS)
    .refine((v) => v !== 0),
  balanceBefore: nonnegative,
  balanceAfter: nonnegative,
  reason: z.string(),
  createdAtMillis: nonnegative,
  business: z
    .object({
      source: z.string(),
      taskId: z.string(),
      checkId: z.string(),
      unitPrice: nonnegative.positive(),
      quantity: nonnegative.positive()
    })
    .nullish()
});
export const pageSchema = z.object({
  entries: z.array(transactionSchema),
  nextCursor: z.string().nullable()
});
export const outcomeSchema = z.discriminatedUnion("status", [
  z.object({
    requestId: requestIdentity,
    status: z.literal("applied"),
    transaction: transactionSchema,
    rejectionCode: z.null(),
    balanceAtDecision: nonnegative
  }),
  z.object({
    requestId: requestIdentity,
    status: z.literal("rejected"),
    transaction: z.null(),
    rejectionCode: z.enum(["insufficient_balance", "balance_limit", "sequence_limit"]),
    balanceAtDecision: nonnegative
  }),
  z.object({
    requestId: requestIdentity,
    status: z.literal("not_observed"),
    transaction: z.null(),
    rejectionCode: z.null(),
    balanceAtDecision: z.null()
  })
]);
export type CreditAccount = z.infer<typeof accountSchema>;
export type CreditTransaction = z.infer<typeof transactionSchema>;
export type CreditPage = z.infer<typeof pageSchema>;
export type CreditOutcome = z.infer<typeof outcomeSchema>;
export interface CreditAdjustment {
  readonly requestId: string;
  readonly delta: number;
  readonly reason: string;
}
export function normalizeAdjustment(input: CreditAdjustment): CreditAdjustment {
  if (
    !requestIdentity.safeParse(input.requestId).success ||
    !Number.isSafeInteger(input.delta) ||
    input.delta === 0 ||
    !input.reason.trim() ||
    input.reason.trim().length > 500
  )
    throw new TaskApiError(400, "请输入非零安全整数和 1～500 字的原因");
  return Object.freeze({ ...input, reason: input.reason.trim() });
}
export class CreditApiError extends TaskApiError {
  constructor(
    status: number,
    message: string,
    readonly errorCode?: number
  ) {
    super(status, message);
  }
}
export class CreditSubmissionUnknown extends Error {
  constructor() {
    super("调账结果未确认，请核对原请求；不要新建一笔相同调账。");
  }
}
export function rejectionMessage(code: string) {
  return code === "insufficient_balance"
    ? "余额不足，本次调账已拒绝。"
    : "余额或流水序号超出安全整数范围，本次调账已拒绝。";
}
