import { randomUUID } from "node:crypto";
import { MockAppCheckTaskSource } from "@/app-checks/mock-task-source";
import type { AppCheckTaskSource } from "@/app-checks/task-source";
import type { CheckCreditConfirmation } from "@/app-checks/credits";
import { MockCreditSource } from "@/credits/mock-source";
import type { MockUserDirectorySource } from "@/user-directory-source";
import type { TaskCaller } from "@/task-caller";

/** Existing business proofs explicitly fund their directory fixtures; production Mock still starts at zero. */
export class FundedAppCheckSource extends MockAppCheckTaskSource {
  readonly fixtureCredits: MockCreditSource;
  readonly funded: Promise<void>;
  constructor(directory: MockUserDirectorySource, now: () => number = Date.now) {
    const credits = new MockCreditSource(directory, now);
    super(directory, credits, now);
    this.fixtureCredits = credits;
    this.funded = directory.load().then(async ({ users, roles }) => {
      const admin = users.find((u) =>
        roles.find((r) => r.id === u.role)?.permissions.includes("credits.manage")
      );
      if (!admin)
        throw new Error("Credit proof requires an explicit directory administrator");
      for (const user of users)
        await credits.adjust(admin, user.userId, {
          requestId: randomUUID(),
          delta: 1_000_000,
          reason: "App Checks test fixture"
        });
    });
  }
  override async loadCredits(caller: TaskCaller, taskId: string, requestId?: string) {
    await this.funded;
    return super.loadCredits(caller, taskId, requestId);
  }
  override async approveTask(
    caller: TaskCaller,
    taskId: string,
    count: number,
    confirmation: CheckCreditConfirmation
  ) {
    await this.funded;
    return super.approveTask(caller, taskId, count, confirmation);
  }
}
export async function approveCheck(
  source: AppCheckTaskSource,
  caller: TaskCaller,
  taskId: string,
  expectedCount: number
) {
  const quote = await source.loadCredits(caller, taskId);
  return source.approveTask!(caller, taskId, expectedCount, {
    requestId: randomUUID(),
    expectedSeedCount: quote.seedCount,
    expectedAmount: quote.amount
  });
}
