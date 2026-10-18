import { afterEach, describe, expect, it, vi } from "vitest";
import { MockUserDirectorySource } from "@/user-directory-source";
import { MockCreditSource } from "@/credits/mock-source";
import { ApiCreditSource } from "@/credits/source";
import { createCreditWorkspace } from "@/credits/workspace";
import {
  CreditSubmissionUnknown,
  MAX_CREDITS,
  normalizeAdjustment,
  type CreditAdjustment,
  type CreditOutcome
} from "@/credits/model";
import { developmentUsers } from "@/development-users";
import { ADMIN, OPERATOR_A, OPERATOR_B } from "./identity-fixture";
import { routeAllowed } from "@/router/access";
import { consoleRoutes } from "@/router";

afterEach(() => vi.unstubAllGlobals());
const input = (delta: number, reason = "manual"): CreditAdjustment => ({
  requestId: crypto.randomUUID(),
  delta,
  reason
});
function setup() {
  const directory = new MockUserDirectorySource();
  return { directory, source: new MockCreditSource(directory, () => 1000) };
}
describe("credit account facts", () => {
  it("starts at zero, applies exact integers and preserves rejected receipts", async () => {
    const { source } = setup();
    expect((await source.account(OPERATOR_A, "1001")).balance).toBe(0);
    const rejected = input(-1);
    await expect(source.adjust(ADMIN, "1001", rejected)).rejects.toMatchObject({
      status: 409
    });
    await source.adjust(ADMIN, "1001", input(MAX_CREDITS));
    await expect(source.adjust(ADMIN, "1001", input(1))).rejects.toMatchObject({
      status: 409
    });
    await expect(source.adjust(ADMIN, "1001", rejected)).rejects.toMatchObject({
      status: 409
    });
    expect(
      await source.adjustment(OPERATOR_A, "1001", rejected.requestId)
    ).toMatchObject({ status: "rejected", balanceAtDecision: 0 });
    await source.adjust(ADMIN, "1001", input(-MAX_CREDITS));
    expect((await source.account(OPERATOR_A, "1001")).balance).toBe(0);
    for (const delta of [0, 1.5, MAX_CREDITS + 1, NaN])
      expect(() => normalizeAdjustment(input(delta))).toThrow();
    expect(() => normalizeAdjustment(input(1, "   "))).toThrow();
  });
  it("concurrent debits cannot overdraw and duplicate requests cannot double charge", async () => {
    const { source } = setup();
    await source.adjust(ADMIN, "1001", input(100));
    const outcomes = await Promise.allSettled([
      source.adjust(ADMIN, "1001", input(-60)),
      source.adjust(ADMIN, "1001", input(-60))
    ]);
    expect(outcomes.filter((o) => o.status === "fulfilled")).toHaveLength(1);
    expect((await source.account(OPERATOR_A, "1001")).balance).toBe(40);
    const request = input(1);
    await Promise.all(
      Array.from({ length: 10 }, () => source.adjust(ADMIN, "1001", request))
    );
    expect(await source.account(OPERATOR_A, "1001")).toMatchObject({
      balance: 41,
      latestSequence: 3
    });
    await expect(
      source.adjust(ADMIN, "1001", { ...request, delta: 2 })
    ).rejects.toMatchObject({ status: 409, errorCode: 18005 });
  });
  it("paginates by sequence across new inserts and equal timestamps", async () => {
    const { source } = setup();
    for (let i = 0; i < 105; i++) await source.adjust(ADMIN, "1001", input(1));
    const first = await source.transactions(OPERATOR_A, "1001");
    await source.adjust(ADMIN, "1001", input(1));
    const second = await source.transactions(OPERATOR_A, "1001", first.nextCursor!);
    const third = await source.transactions(OPERATOR_A, "1001", second.nextCursor!);
    expect(
      [...first.entries, ...second.entries, ...third.entries].map((t) => t.sequence)
    ).toEqual(Array.from({ length: 105 }, (_, i) => 105 - i));
    expect(third.nextCursor).toBeNull();
    await expect(
      source.transactions(ADMIN, "1002", first.nextCursor!)
    ).rejects.toMatchObject({ status: 400 });
  });
  it("uses current directory permissions, permits admin self and has no Project gate", async () => {
    const { source, directory } = setup();
    await source.adjust(ADMIN, "1000", input(1));
    await expect(source.account(OPERATOR_A, "1002")).rejects.toMatchObject({
      status: 403
    });
    await expect(source.adjust(OPERATOR_A, "1001", input(1))).rejects.toMatchObject({
      status: 403
    });
    await expect(
      source.adjust({ ...OPERATOR_A, role: "admin" }, "1001", input(1))
    ).rejects.toMatchObject({ status: 403 });
    await source.adjust(ADMIN, "1001", input(100));
    await directory.save(
      ADMIN,
      { ...developmentUsers[1]!, displayName: "renamed", projectIds: [] },
      "1001"
    );
    expect((await source.account(OPERATOR_A, "1001")).balance).toBe(100);
    await directory.save(
      ADMIN,
      { ...developmentUsers[1]!, role: "admin", projectIds: [] },
      "1001"
    );
    await expect(source.account(OPERATOR_A, "1001")).rejects.toMatchObject({
      status: 403
    });
    expect(
      (await source.account({ ...OPERATOR_A, role: "admin" }, "1001")).balance
    ).toBe(100);
    const root = consoleRoutes.find((route) => route.path === "/")!;
    expect(
      routeAllowed(developmentUsers[1]!, {
        meta: root.meta!,
        query: { view: "credits" }
      })
    ).toBe(true);
  });
});
describe("credit requests and confirmation", () => {
  it("retains original content after a committed response loss and permits query or exact retry", async () => {
    const { source } = setup();
    const workspace = createCreditWorkspace(source, ADMIN, "1001");
    source.dropNextResponse(ADMIN);
    await workspace.submit(100, " reason ");
    const pending = workspace.pending.value;
    expect(pending).toMatchObject({ delta: 100, reason: "reason" });
    await workspace.submit(300, "different");
    expect(workspace.pending.value).toBe(pending);
    await workspace.reconcile();
    expect(workspace.account.value?.balance).toBe(100);
    expect(workspace.entries.value).toHaveLength(1);
    source.dropNextResponse(ADMIN);
    await workspace.submit(-30, "subtract");
    const original = workspace.pending.value;
    await workspace.retry();
    expect(workspace.confirmed.value?.requestId).toBe(original?.requestId);
    expect(workspace.account.value?.balance).toBe(70);
    expect(workspace.entries.value).toHaveLength(2);
    workspace.dispose();
    const nextLogin = createCreditWorkspace(source, OPERATOR_A, "1001");
    await nextLogin.load();
    expect(nextLogin.account.value?.balance).toBe(70);
    expect(nextLogin.pending.value).toBeUndefined();
  });
  it("a missing receipt keeps the original pending intent, and 403 is never unknown", async () => {
    const { source } = setup();
    const adjust = vi
      .spyOn(source, "adjust")
      .mockRejectedValueOnce(new CreditSubmissionUnknown());
    const workspace = createCreditWorkspace(source, ADMIN, "1001");
    await workspace.submit(1, "reason");
    const original = workspace.pending.value;
    await workspace.reconcile();
    expect(workspace.pending.value).toBe(original);
    expect(workspace.notice.value).toContain("不能据此认定失败");
    expect(adjust).toHaveBeenCalledTimes(1);
    await workspace.retry();
    expect(workspace.account.value?.balance).toBe(1);
    const forbidden = createCreditWorkspace(source, OPERATOR_A, "1001");
    await forbidden.submit(1, "forbidden");
    expect(forbidden.pending.value).toBeUndefined();
    expect(forbidden.error.value).toContain("重新登录");
  });
  it("double clicks submit once and logout ignores late writes without follow-up reads", async () => {
    const { source } = setup();
    const real = source.adjust.bind(source);
    let finish!: (outcome: CreditOutcome) => void;
    let saved!: CreditOutcome;
    const adjust = vi.spyOn(source, "adjust").mockImplementation(async (...args) => {
      saved = await real(...args);
      return new Promise((resolve) => {
        finish = resolve;
      });
    });
    let current = true;
    const caller = { ...ADMIN };
    const workspace = createCreditWorkspace(source, caller, "1001", () => current);
    const write = workspace.submit(1, "one");
    await workspace.submit(1, "one");
    expect(adjust).toHaveBeenCalledTimes(1);
    caller.userId = OPERATOR_B.userId;
    await Promise.resolve();
    current = false;
    workspace.dispose();
    const reads = vi.spyOn(source, "account");
    finish(saved);
    await write;
    expect(reads).not.toHaveBeenCalled();
    expect(workspace.account.value).toBeUndefined();
    expect(adjust.mock.calls[0]?.[0]).toEqual(ADMIN);
    expect((await source.account(ADMIN, "1001")).balance).toBe(1);
  });
  it("late reads cannot repopulate a disposed view", async () => {
    const { source } = setup();
    let finish!: (value: Awaited<ReturnType<typeof source.account>>) => void;
    vi.spyOn(source, "account").mockReturnValue(
      new Promise((resolve) => {
        finish = resolve;
      })
    );
    const workspace = createCreditWorkspace(source, OPERATOR_A, "1001");
    const load = workspace.load();
    workspace.dispose();
    finish({
      userId: "1001",
      balance: 100,
      latestSequence: 1,
      updatedAtMillis: 1,
      userPresent: true
    });
    await load;
    expect(workspace.account.value).toBeUndefined();
  });
  it("HTTP keeps caller headers and distinguishes 409, 403 and unknown writes", async () => {
    const source = new ApiCreditSource();
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ code: 18004 }, { status: 409 }))
      .mockResolvedValueOnce(Response.json({}, { status: 403 }))
      .mockRejectedValueOnce(new Error("lost"))
      .mockResolvedValueOnce(new Response("bad payload", { status: 200 }))
      .mockResolvedValueOnce(new Response("", { status: 503 }));
    vi.stubGlobal("fetch", fetcher);
    await expect(source.adjust(ADMIN, "1001", input(-1))).rejects.toMatchObject({
      status: 409,
      errorCode: 18004
    });
    await expect(source.adjust(ADMIN, "1001", input(1))).rejects.toMatchObject({
      status: 403
    });
    for (let i = 0; i < 3; i++)
      await expect(source.adjust(ADMIN, "1001", input(1))).rejects.toBeInstanceOf(
        CreditSubmissionUnknown
      );
    expect(fetcher.mock.calls[0]?.[1].headers).toMatchObject({
      "X-XA-Dev-User": "MTAwMA",
      "X-XA-Dev-Role": "admin"
    });
    expect(fetcher).toHaveBeenCalledTimes(5);
  });
});
