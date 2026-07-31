import { describe, expect, it } from "vitest";

import {
  workerGroupBatchGetResponseSchema,
  workerPreviewResponseSchema
} from "@/runtime-viewer/schemas";
import { preview, worker, workerGroup } from "./fixtures";

describe("Runtime View response schemas", () => {
  it("accepts the bounded public preview DTO", () => {
    const value = preview("group-a", [
      worker("group-a", "worker-b"),
      worker("group-a", "worker-a")
    ]);

    expect(workerPreviewResponseSchema.parse(value)).toEqual(value);
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
        redisKey: "wr:test:workers:group-a"
      })
    }
  ])("rejects $name", ({ mutate }) => {
    expect(workerPreviewResponseSchema.safeParse(mutate()).success).toBe(false);
  });

  it("rejects duplicate identities across found and missing groups", () => {
    const result = workerGroupBatchGetResponseSchema.safeParse({
      workerGroups: [workerGroup("group-a")],
      missingWorkerGroupIds: ["group-a"]
    });

    expect(result.success).toBe(false);
  });
});
