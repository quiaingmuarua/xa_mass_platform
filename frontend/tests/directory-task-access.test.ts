import { describe, expect, it, vi } from "vitest";
import { MockUserDirectorySource } from "@/user-directory-source";
import { MockAppCheckTaskSource } from "@/app-checks/mock-task-source";
import { MockMessageTaskSource } from "@/message-campaigns/mock-task-source";
import { MockRuntimeViewerDataSource } from "@/runtime-viewer/mock-data-source";
import { createConsoleIdentity } from "@/console-identity";
import { ADMIN, OPERATOR_A as A, OPERATOR_B as B } from "./identity-fixture";

function world(project: "messages" | "app-checks") {
  const directory = new MockUserDirectorySource();
  if (project === "messages") {
    const source = new MockMessageTaskSource(directory);
    return {
      directory,
      source,
      create: (caller: typeof A) =>
        source.createTask(caller, {
          requestId: "directory-access",
          appId: "demo",
          name: "Directory access",
          recipientCountry: "CN",
          senderCountry: null,
          body: "{}"
        })
    };
  }
  const source = new MockAppCheckTaskSource(directory);
  return {
    directory,
    source,
    create: (caller: typeof A) =>
      source.createTask(caller, {
        requestId: "directory-access",
        appId: "app-a",
        country: "CN"
      })
  };
}

describe.each(["messages", "app-checks"] as const)(
  "%s directory admission",
  (project) => {
    it("updates the next operation's Project grants while retaining owners and creation correlation", async () => {
      const { directory, source, create } = world(project);
      const first = await create(A),
        second = await create(B);
      expect(first.taskId).not.toBe(second.taskId);
      expect((await source.listTasks(A)).tasks.map((task) => task.taskId)).toContain(
        first.taskId
      );
      expect(
        (await source.listTasks(B)).tasks.map((task) => task.taskId)
      ).not.toContain(first.taskId);
      expect((await source.listTasks(ADMIN)).tasks.map((task) => task.taskId)).toEqual(
        expect.arrayContaining([first.taskId, second.taskId])
      );
      const original = (await source.loadTask(A, first.taskId)).task;
      await directory.save(ADMIN, { ...A, displayName: "A", projectIds: [] }, A.userId);
      for (const operation of [
        () => create(A),
        () => source.listTasks(A),
        () => source.loadTask(A, first.taskId),
        () => source.approveTask(A, first.taskId, 1),
        () => source.closeTask(A, first.taskId)
      ])
        await expect(operation()).rejects.toMatchObject({ status: 403 });
      await directory.save(
        ADMIN,
        { ...A, displayName: "A renamed", projectIds: [project] },
        A.userId
      );
      expect(await create(A)).toEqual(first);
      expect((await source.loadTask(A, first.taskId)).task).toEqual(original);
      await directory.save(
        ADMIN,
        { ...A, displayName: "A admin", role: "admin", projectIds: [] },
        A.userId
      );
      await expect(create(A)).rejects.toMatchObject({ status: 403 });
      expect(await create({ ...A, role: "admin" })).toEqual(first);
      await directory.save(
        ADMIN,
        { ...A, displayName: "A", projectIds: [project] },
        A.userId
      );
      await expect(create({ ...A, role: "admin" })).rejects.toMatchObject({
        status: 403
      });
      expect((await source.loadTask(A, first.taskId)).task.userId).toBe(A.userId);
      await expect(create({ userId: "missing", role: "admin" })).rejects.toMatchObject({
        status: 403
      });
    });
  }
);

it("keeps an admitted import's snapshot and denies the next operation before reading its file", async () => {
  const directory = new MockUserDirectorySource();
  const source = new MockAppCheckTaskSource(directory);
  const { taskId } = await source.createTask(A, {
    requestId: "import-snapshot",
    appId: "app-a",
    country: "CN"
  });
  let release!: (text: string) => void;
  const text = vi.fn(
    () =>
      new Promise<string>((resolve) => {
        release = resolve;
      })
  );
  const pending = source.importNumbers(A, taskId, {
    size: 13,
    text
  } as unknown as Blob);
  expect(text).toHaveBeenCalledOnce();
  await directory.save(ADMIN, { ...A, displayName: "A", projectIds: [] }, A.userId);
  release("8613800000001");
  await expect(pending).resolves.toMatchObject({ addedCount: 1 });
  const forbiddenFile = {
    get size(): number {
      throw new Error("must not read file");
    }
  } as Blob;
  await expect(source.importNumbers(A, taskId, forbiddenFile)).rejects.toMatchObject({
    status: 403
  });
  await expect(source.exportTask(A, taskId, "all")).rejects.toMatchObject({
    status: 403
  });
  expect((await source.loadTask(ADMIN, taskId)).task.totalCount).toBe(1);
});

it("retains login snapshots until re-entry and rechecks runtime permissions per request", async () => {
  const directory = new MockUserDirectorySource();
  const identity = createConsoleIdentity(undefined, [], directory);
  identity.login(A.userId);
  const original = identity.requireCurrent();
  await directory.save(
    ADMIN,
    { ...A, displayName: "A admin", role: "admin", projectIds: [] },
    A.userId
  );
  await identity.load(true);
  expect(identity.requireCurrent()).toBe(original);
  identity.logout();
  await identity.load(true);
  identity.login(A.userId);
  expect(identity.requireCurrent().role).toBe("admin");
  const runtime = new MockRuntimeViewerDataSource(identity.requireCurrent(), directory);
  await expect(runtime.previewTasks(10)).resolves.toBeDefined();
  await directory.save(
    ADMIN,
    { ...A, displayName: "A", projectIds: ["messages"] },
    A.userId
  );
  await expect(runtime.previewTasks(10)).rejects.toMatchObject({ status: 403 });
});
