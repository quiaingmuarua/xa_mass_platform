import { z } from "zod";
import { transactionSchema } from "@/credits/model";

const count = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
export const checkCreditsSchema = z.object({
  taskId: z.string(),
  userId: z.string(),
  unitPrice: count.positive(),
  itemCount: count,
  seedCount: count,
  amount: count,
  balance: count,
  status: z.enum([
    "ready",
    "rejected",
    "debit_unconfirmed",
    "charged",
    "started",
    "closed"
  ]),
  requestId: z.string().nullable(),
  requestStatus: z.enum(["not_observed", "applied", "rejected"]),
  rejectionCode: z.string().nullable(),
  transaction: transactionSchema.nullable()
});
export type CheckCredits = z.infer<typeof checkCreditsSchema>;
export interface CheckCreditConfirmation {
  readonly requestId: string;
  readonly expectedSeedCount: number;
  readonly expectedAmount: number;
}
export class CheckStartUnconfirmed extends Error {
  constructor() {
    super("扣分或启动结果未确认。请核对原请求或继续原操作，不要新建一笔启动请求。");
  }
}
