import { FundedAppCheckSource, approveCheck } from "./app-check-credit-fixture";
import { webcrypto } from "node:crypto";
import { Blob as NodeBlob } from "node:buffer";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import { MockUserDirectorySource } from "@/user-directory-source";
import { CALLER } from "./app-check-fixture";
import {
  ApiAppCheckTaskSource,
  AppCheckImportUnconfirmed
} from "../src/app-checks/task-source";

beforeEach(() => vi.stubGlobal("crypto", webcrypto));
afterEach(() => vi.unstubAllGlobals());
const file = (seeds: string[]) => new NodeBlob([seeds.join("\n")]) as unknown as Blob;

it("preserves confirmed partial-import Item counts without claiming that unconfirmed groups failed", async () => {
  const fetcher = vi.fn().mockResolvedValue(
    new Response(
      JSON.stringify({
        message: "导入未确认",
        taskId: "known",
        confirmedAddedCount: 100,
        existingCount: 2
      }),
      { status: 503 }
    )
  );
  vi.stubGlobal("fetch", fetcher);
  await expect(
    new ApiAppCheckTaskSource().importSeeds(CALLER, "known", file(["a"]))
  ).rejects.toMatchObject({
    taskId: "known",
    message: "导入未确认；本次已确认新增 100 个执行项，已存在 2 个执行项"
  });
  await expect(
    new ApiAppCheckTaskSource().importSeeds(CALLER, "known", file(["a"]))
  ).rejects.toBeInstanceOf(AppCheckImportUnconfirmed);
  expect(fetcher).toHaveBeenCalledTimes(2);
});

it("keeps ordered group replay separate from seed overlap and counts execution Items", async () => {
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  const source = new FundedAppCheckSource(new MockUserDirectorySource());
  const { taskId } = await source.createTask(CALLER, {
    requestId: "groups",
    checkId: "original-carrier",
    country: "CN"
  });
  const seeds = Array.from(
    { length: 23 },
    (_, i) => "+861380000" + String(i).padStart(4, "0")
  );
  expect(
    await source.importSeeds(CALLER, taskId, file([...seeds, seeds[0]]))
  ).toMatchObject({
    uniqueCount: 23,
    duplicateCount: 1,
    itemCount: 3,
    addedCount: 3,
    existingCount: 0
  });
  expect(await source.importSeeds(CALLER, taskId, file(seeds))).toMatchObject({
    itemCount: 3,
    addedCount: 0,
    existingCount: 3
  });
  expect(
    await source.importSeeds(CALLER, taskId, file([seeds[0], seeds[22]]))
  ).toMatchObject({ itemCount: 1, addedCount: 1 });
  await expect(approveCheck(source, CALLER, taskId, 25)).rejects.toThrow("执行项数量");
  await approveCheck(source, CALLER, taskId, 4);
  await source.completeTask(CALLER, taskId);
  const detail = await source.loadTask(CALLER, taskId);
  expect(detail.task).toMatchObject({
    totalCount: 4,
    succeededCount: 4,
    activeCount: 0,
    seedsPerItem: 10
  });
  expect(detail.results).toHaveLength(25);
  expect(new Set(detail.results.map((row) => row.messageId)).size).toBe(4);
  expect(
    new Set(detail.results.map((row) => JSON.stringify([row.messageId, row.seed]))).size
  ).toBe(25);
  expect(detail.results.filter((row) => row.seed === seeds[0])).toHaveLength(2);
  expect((await source.exportTask(CALLER, taskId, "all")).count).toBe(25);
  expect(fetcher).not.toHaveBeenCalled();
});

it("freezes grouping in existing Tasks and activates saved grouping only on explicit Mock restart", async () => {
  const source = new FundedAppCheckSource(new MockUserDirectorySource());
  const request = {
    requestId: "saved-grouping",
    checkId: "original-carrier",
    country: "CN" as const
  };
  const old = await source.createTask(CALLER, request);
  const saved = (await source.loadConfiguration(CALLER)).saved;
  saved.configuration.checks["original-carrier"].execution.seedsPerItem = 1;
  await source.saveConfiguration(CALLER, saved.fingerprint, saved.configuration);
  expect(
    (await source.catalog()).checks.find(
      (check) => check.checkId === "original-carrier"
    )?.execution.seedsPerItem
  ).toBe(10);
  await source.simulateConfigurationRestart(CALLER);
  expect(await source.createTask(CALLER, request)).toEqual(old);
  const fresh = await source.createTask(CALLER, {
    ...request,
    requestId: "fresh-grouping"
  });
  const seeds = Array.from(
    { length: 23 },
    (_, i) => "+861380000" + String(i).padStart(4, "0")
  );
  expect((await source.importSeeds(CALLER, old.taskId, file(seeds))).itemCount).toBe(3);
  expect((await source.importSeeds(CALLER, fresh.taskId, file(seeds))).itemCount).toBe(
    23
  );
});
