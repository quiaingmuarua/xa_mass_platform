import { describe, expect, it } from "vitest";

import {
  taskPreviewResponseSchema,
  workerGroupPreviewResponseSchema,
  workerPreviewResponseSchema
} from "@/runtime-viewer/schemas";
import {
  groupPreview,
  preview,
  taskPreview,
  taskPreviewEntry,
  worker
} from "./fixtures";

describe("Runtime View response schemas", () => {
  it("accepts the bounded public preview DTO", () => {
    const value = preview("group-a", [
      worker("group-a", "worker-b"),
      worker("group-a", "worker-a")
    ]);

    expect(workerPreviewResponseSchema.parse(value)).toEqual(value);
  });

  it("accepts a bounded WorkerGroup preview and rejects duplicate identities", () => {
    const value = groupPreview(["group-a", "group-b"], 1);
    expect(workerGroupPreviewResponseSchema.parse(value)).toEqual(value);

    const duplicate = groupPreview(["group-a", "group-a"]);
    expect(workerGroupPreviewResponseSchema.safeParse(duplicate).success).toBe(false);
  });

  it.each([
    {
      name: "inconsistent counts",
      mutate: () => ({
        ...preview("group-a", [worker("group-a", "worker-a")]),
        sampledCount: 2
      })
    },
    {
      name: "cross-group Worker",
      mutate: () => preview("group-a", [worker("group-b", "worker-a")])
    },
    {
      name: "duplicate Worker identity",
      mutate: () =>
        preview("group-a", [
          worker("group-a", "worker-a"),
          worker("group-a", "worker-a")
        ])
    },
    {
      name: "internal field",
      mutate: () => ({
        ...preview("group-a", []),
        redisKey: "xa_mass:profile_secret:worker:metadata:group-a"
      })
    }
  ])("rejects $name", ({ mutate }) => {
    expect(workerPreviewResponseSchema.safeParse(mutate()).success).toBe(false);
  });

  it("accepts ordered Task Score entries with missing descriptors", () => {
    const value = taskPreview([
      taskPreviewEntry("group-a", { scoreBand: "pre_review" }),
      taskPreviewEntry("group-b", {
        missingTask: true,
        scoreBand: "terminal"
      })
    ]);

    expect(taskPreviewResponseSchema.parse(value)).toEqual(value);
  });

  it("rejects descriptor identity drift and duplicate coordinates", () => {
    const drifted = taskPreviewEntry("group-a");
    drifted.task = {
      ...drifted.task!,
      workerGroupId: "group-b"
    };
    expect(taskPreviewResponseSchema.safeParse(taskPreview([drifted])).success).toBe(
      false
    );

    const duplicate = taskPreviewEntry("group-a");
    const result = taskPreviewResponseSchema.safeParse(
      taskPreview([duplicate, duplicate])
    );

    expect(result.success).toBe(false);

    const orphanedGroup = taskPreviewEntry("group-b", { missingGroup: true });
    orphanedGroup.workerGroup = workerGroupPreviewResponseSchema.parse(
      groupPreview(["group-b"])
    ).workerGroups[0]!;
    orphanedGroup.task = null;
    expect(
      taskPreviewResponseSchema.safeParse(taskPreview([orphanedGroup])).success
    ).toBe(false);

    expect(
      taskPreviewResponseSchema.safeParse({
        ...taskPreview([taskPreviewEntry("group-c")]),
        rawScore: 42
      }).success
    ).toBe(false);
  });
});
