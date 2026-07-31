import { describe, expect, it } from "vitest";

import { filterCurrentSample } from "@/runtime-viewer/filter";
import { worker } from "./fixtures";

describe("filterCurrentSample", () => {
  it("sorts the unstable sample by Worker ID without mutating it", () => {
    const workers = [worker("group-a", "worker-z"), worker("group-a", "worker-a")];

    expect(filterCurrentSample(workers, "").map((value) => value.workerId)).toEqual([
      "worker-a",
      "worker-z"
    ]);
    expect(workers.map((value) => value.workerId)).toEqual(["worker-z", "worker-a"]);
  });

  it("filters only the current in-memory sample across safe text fields", () => {
    const first = worker("group-a", "worker-a");
    first.attributes = { region: "Shanghai" };
    const second = worker("group-a", "worker-b");

    expect(filterCurrentSample([first, second], "shanghai")).toEqual([first]);
    expect(filterCurrentSample([first, second], "not-present")).toEqual([]);
  });
});
