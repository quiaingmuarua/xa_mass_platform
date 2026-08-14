import { describe, expect, it } from "vitest";

import {
  configuredRuntimeResourcesResponseSchema,
  workerPreviewResponseSchema
} from "@/runtime-viewer/schemas";
import { configuredEntry, preview, worker } from "./fixtures";

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

  it("accepts ordered configured entries with missing descriptors", () => {
    const value = {
      entries: [
        configuredEntry("group-a"),
        configuredEntry("group-b", {
          missingGroup: true,
          missingTask: true
        })
      ]
    };

    expect(configuredRuntimeResourcesResponseSchema.parse(value)).toEqual(value);
  });

  it("rejects descriptor identity drift and duplicate coordinates", () => {
    const drifted = configuredEntry("group-a");
    drifted.task = {
      ...drifted.task!,
      workerGroupId: "group-b"
    };
    expect(
      configuredRuntimeResourcesResponseSchema.safeParse({ entries: [drifted] }).success
    ).toBe(false);

    const duplicate = configuredEntry("group-a");
    const result = configuredRuntimeResourcesResponseSchema.safeParse({
      entries: [duplicate, duplicate]
    });

    expect(result.success).toBe(false);
  });
});
