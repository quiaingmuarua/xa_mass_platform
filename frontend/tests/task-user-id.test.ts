import { expect, it } from "vitest";
import { normalizeTaskUserId, taskUserScore, compareTaskIds } from "@/task-user-id";

it("keeps canonical Base36 IDs and all nine digits plus six reserved bits exact", () => {
  expect(normalizeTaskUserId(" 1000 ")).toBe("1000");
  expect(taskUserScore()).toBe(0);
  expect(taskUserScore("1000")).toBe(2985984);
  const max = taskUserScore("zzzzzzzzz");
  expect(max).toBe(6499837226778560);
  expect(Number.isSafeInteger(max + 63)).toBe(true);
  expect((max / 64).toString(36)).toBe("zzzzzzzzz");
  for (const id of ["0001", "abc", "aaaaaaaaaa", "ABCD", "a-bc", "用户甲乙"])
    expect(() => normalizeTaskUserId(id)).toThrow();
  expect(["task-2", "task-10", "task-1"].sort(compareTaskIds)).toEqual([
    "task-1",
    "task-10",
    "task-2"
  ]);
});
