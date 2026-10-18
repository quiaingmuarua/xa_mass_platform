import { webcrypto } from "node:crypto";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { MockUserDirectorySource } from "@/user-directory-source";
import { MockCreditSource } from "@/credits/mock-source";
import { MockAppCheckTaskSource } from "@/app-checks/mock-task-source";
import { CheckStartUnconfirmed } from "@/app-checks/credits";
import { ADMIN, OPERATOR_A as A, OPERATOR_B as B } from "./identity-fixture";

beforeEach(() => vi.stubGlobal("crypto", webcrypto));
afterEach(() => vi.unstubAllGlobals());
function world() {
  const directory = new MockUserDirectorySource();
  const credits = new MockCreditSource(directory);
  const source = new MockAppCheckTaskSource(directory, credits);
  const fund = (delta: number) =>
    credits.adjust(ADMIN, A.userId, {
      requestId: crypto.randomUUID(),
      delta,
      reason: "Acceptance fixture"
    });
  return { directory, credits, source, fund };
}
async function create(source: MockAppCheckTaskSource, count = 23) {
  const { taskId } = await source.createTask(A, {
    requestId: crypto.randomUUID(),
    checkId: "md5-prefix"
  });
  const file = new Blob([
    Array.from({ length: count }, (_, i) => `seed-${i}`).join("\n")
  ]);
  await source.importSeeds(A, taskId, file);
  await source.importSeeds(A, taskId, file);
  const quote = await source.loadCredits(A, taskId);
  return {
    taskId,
    quote,
    confirmation: {
      requestId: crypto.randomUUID(),
      expectedSeedCount: quote.seedCount,
      expectedAmount: quote.amount
    }
  };
}
it("charges accepted seeds including the short group once, keeps owner attribution, and never refunds closure", async () => {
  const { source, credits, fund } = world();
  await fund(100);
  const { taskId, quote, confirmation } = await create(source);
  expect(quote).toMatchObject({
    itemCount: 3,
    seedCount: 23,
    amount: 23,
    balance: 100
  });
  await source.approveTask(ADMIN, taskId, 3, confirmation);
  await source.approveTask(ADMIN, taskId, 3, confirmation);
  expect(await credits.account(A, A.userId)).toMatchObject({
    balance: 77,
    latestSequence: 2
  });
  expect(await credits.account(ADMIN, ADMIN.userId)).toMatchObject({ balance: 0 });
  const rows = await credits.transactions(A, A.userId);
  expect(rows.entries[0]).toMatchObject({
    actorUserId: ADMIN.userId,
    delta: -23,
    business: { taskId, quantity: 23, unitPrice: 1 }
  });
  await source.closeTask(A, taskId);
  expect((await credits.account(A, A.userId)).balance).toBe(77);
  await expect(source.loadCredits(B, taskId)).rejects.toMatchObject({ status: 403 });
});
it("retains an insufficient receipt and requires an explicit new request after funding", async () => {
  const { source, credits, fund } = world();
  const { taskId, confirmation } = await create(source);
  await expect(source.approveTask(A, taskId, 3, confirmation)).rejects.toMatchObject({
    status: 409
  });
  expect((await source.loadTask(A, taskId)).task.state).toBe("pre_review");
  await fund(100);
  await expect(source.approveTask(A, taskId, 3, confirmation)).rejects.toMatchObject({
    status: 409
  });
  await source.approveTask(A, taskId, 3, {
    ...confirmation,
    requestId: crypto.randomUUID()
  });
  expect((await credits.account(A, A.userId)).balance).toBe(77);
  expect(
    (await source.loadCredits(A, taskId, confirmation.requestId)).requestStatus
  ).toBe("rejected");
});
it("reconciles a post-debit lost response using the shared account before explicitly continuing", async () => {
  const { source, credits, fund } = world();
  await fund(100);
  const { taskId, confirmation } = await create(source);
  source.simulateStartResponseLoss(ADMIN);
  await expect(
    source.approveTask(ADMIN, taskId, 3, confirmation)
  ).rejects.toBeInstanceOf(CheckStartUnconfirmed);
  expect((await source.loadTask(A, taskId)).task.state).toBe("pre_review");
  expect(await source.loadCredits(ADMIN, taskId, confirmation.requestId)).toMatchObject(
    { status: "charged", balance: 77, requestStatus: "applied" }
  );
  await expect(source.importSeeds(A, taskId, new Blob(["new"]))).rejects.toThrow(
    "不能继续导入"
  );
  await source.approveTask(ADMIN, taskId, 3, confirmation);
  expect((await credits.account(A, A.userId)).latestSequence).toBe(2);
});
it("fixes prices on creation and serializes competing Tasks against the same account", async () => {
  const { source, credits, fund } = world();
  await fund(100);
  const original = await create(source, 40);
  const view = await source.loadConfiguration(ADMIN);
  view.saved.configuration.checks["md5-prefix"].unitPrice = 2;
  await source.saveConfiguration(
    ADMIN,
    view.saved.fingerprint,
    view.saved.configuration
  );
  await source.simulateConfigurationRestart(ADMIN);
  const fresh = await create(source, 40);
  expect((await source.loadCredits(A, original.taskId)).unitPrice).toBe(1);
  expect(fresh.quote.amount).toBe(80);
  const outcomes = await Promise.allSettled([
    source.approveTask(A, original.taskId, 4, original.confirmation),
    source.approveTask(A, fresh.taskId, 4, fresh.confirmation)
  ]);
  expect(outcomes.filter((o) => o.status === "fulfilled")).toHaveLength(1);
  expect([20, 60]).toContain((await credits.account(A, A.userId)).balance);
});
