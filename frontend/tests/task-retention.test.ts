import { expect, it } from "vitest";
import { MockUserDirectorySource } from "@/user-directory-source";
import { MockAppCheckTaskSource } from "@/app-checks/mock-task-source";
import { MockMessageTaskSource } from "@/message-campaigns/mock-task-source";
import { taskRetentionSchema, retentionLabel } from "@/task-management/retention";
import { ADMIN, OPERATOR_A as A } from "./identity-fixture";

it("freezes Messages receipts and changes execution identity only after purge", async () => {
  let now = 2000000000000;
  const source = new MockMessageTaskSource(
    new MockUserDirectorySource(),
    [],
    () => now
  );
  const input = {
    requestId: "retention",
    appId: "demo",
    name: "retained",
    recipientCountry: "CN" as const,
    senderCountry: null,
    body: "{}"
  };
  const first = await source.createTask(A, input);
  await source.importRecipients(A, first.taskId, "+8613800000001");
  await source.approveTask(A, first.taskId, 1);
  await source.demonstrate(ADMIN, first.taskId, "complete");
  const closed = await source.loadTask(A, first.taskId);
  expect(closed.task.retention?.terminatedAtMillis).toBe(now);
  now += 1000;
  await source.demonstrate(ADMIN, first.taskId, "delivered");
  await source.closeTask(A, first.taskId);
  expect(
    (await source.loadTask(A, first.taskId)).task.retention?.terminatedAtMillis
  ).toBe(now - 1000);
  await expect(source.runRetention(A, first.taskId)).rejects.toMatchObject({
    status: 403
  });
  expect(await source.runRetention(ADMIN, first.taskId)).toBe("unchanged");
  now += 3600000;
  expect(await source.runRetention(ADMIN, first.taskId)).toBe("archived");
  expect(await source.createTask(A, input)).toEqual(first);
  await expect(source.demonstrate(ADMIN, first.taskId, "read")).rejects.toThrow("冻结");
  const archived = await source.loadTask(A, first.taskId);
  expect(archived.results[0].status).toBe("DELIVERED");
  expect(retentionLabel(taskRetentionSchema.parse(archived.task.retention))).toBe(
    "结果已归档"
  );
  now += 7 * 86400000;
  expect(await source.runRetention(ADMIN, first.taskId)).toBe("purged");
  await expect(source.loadTask(A, first.taskId)).rejects.toThrow();
  expect((await source.createTask(A, input)).taskId).not.toBe(first.taskId);
});

it("keeps App Checks statistics and CSV after archive and removes the creation association at expiry", async () => {
  let now = 2000000000000;
  const source = new MockAppCheckTaskSource(new MockUserDirectorySource(), () => now);
  const input = { requestId: "retention", appId: "app-a", country: "CN" as const };
  const first = await source.createTask(A, input);
  await source.importNumbers(A, first.taskId, {
    size: 13,
    text: async () => "+8613800000001"
  } as Blob);
  await source.approveTask(A, first.taskId, 1);
  await source.completeTask(ADMIN, first.taskId);
  const before = await source.loadTask(A, first.taskId);
  now += 3600000;
  expect(await source.runRetention(ADMIN, first.taskId)).toBe("archived");
  const after = await source.loadTask(A, first.taskId);
  expect(after.results).toEqual(before.results);
  expect(after.task.totalCount).toBe(before.task.totalCount);
  expect(after.task.succeededCount).toBe(before.task.succeededCount);
  expect((await source.exportTask(A, first.taskId, "all")).count).toBe(1);
  expect(await source.createTask(A, input)).toEqual(first);
  now += 7 * 86400000;
  expect(await source.runRetention(ADMIN, first.taskId)).toBe("purged");
  expect(
    (await source.listTasks(A)).tasks.some((task) => task.taskId === first.taskId)
  ).toBe(false);
  expect((await source.createTask(A, input)).taskId).not.toBe(first.taskId);
});
