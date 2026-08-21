import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  buildSeedItems,
  MAX_TASK_SEED_LINES,
  parseSeedLines,
  presentationLabel,
  presentationStatus,
  TASK_ADMISSION_DELAY_MILLIS,
  TASK_DISPATCH_DELAY_MILLIS
} from "@/task-management/model";
import type { TaskManagementScheduler } from "@/task-management/types";
import { createTaskManagementStore } from "@/stores/task-management";

describe("finite Task seed model", () => {
  it("keeps internal empty lines and ignores one trailing line terminator", () => {
    const lines = parseSeedLines(bytes("one\n\nthree\n"));

    expect(lines).toEqual(["one", "", "three"]);
    expect(buildSeedItems(lines, "value")).toEqual([
      { lineNumber: 1, rawLine: "one", payload: { value: "one" } },
      { lineNumber: 2, rawLine: "", payload: { value: "" } },
      { lineNumber: 3, rawLine: "three", payload: { value: "three" } }
    ]);
  });

  it("rejects invalid UTF-8 and more than 1000 lines", () => {
    expect(() => parseSeedLines(new Uint8Array([0xc3, 0x28]).buffer)).toThrow(
      "valid UTF-8"
    );
    expect(() =>
      parseSeedLines(
        bytes(Array.from({ length: MAX_TASK_SEED_LINES + 1 }, () => "x").join("\n"))
      )
    ).toThrow("1000 lines");
  });
});

describe("finite Task management store", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("creates an awaiting-seeds Task and rejects duplicate session IDs", () => {
    const store = createTaskManagementStore(catalog());

    const task = store.createTask(createRequest());

    expect(task).toMatchObject({
      lifecycleState: "PRE_REVIEW",
      seedState: "MISSING",
      allocationRule: {}
    });
    expect(presentationLabel(presentationStatus(task!))).toBe("Awaiting Seeds");
    expect(store.createTask(createRequest())).toBeUndefined();
    expect(store.error).toContain("already exists");
  });

  it("allows later review and advances only after explicit approval", async () => {
    const scheduler = new ManualScheduler();
    const store = createTaskManagementStore(catalog(), scheduler);
    const task = store.createTask(createRequest())!;

    await store.attachSeed({
      taskId: task.taskId,
      eventCode: "extension.worker.string.md5",
      payloadKey: "value",
      file: textFile("seed.txt", "one\n\nthree\n")
    });

    expect(presentationStatus(task)).toBe("awaiting-approval");
    expect(task.seed?.items.map((item) => item.payload)).toEqual([
      { value: "one" },
      { value: "" },
      { value: "three" }
    ]);
    expect(task.lifecycleState).toBe("PRE_REVIEW");

    const completion = store.approveTask(task.taskId);
    expect(task.lifecycleState).toBe("ADMISSION_VISIBLE");
    expect(scheduler.pendingDelay()).toBe(TASK_ADMISSION_DELAY_MILLIS);

    await scheduler.advance();
    expect(task.lifecycleState).toBe("RUNNING_VISIBLE");
    expect(scheduler.pendingDelay()).toBe(TASK_DISPATCH_DELAY_MILLIS);

    await scheduler.advance();
    await expect(completion).resolves.toBe(true);
    expect(task.lifecycleState).toBe("TERMINAL");
    expect(task.outputFile).toBe("finite-task-001.mock.jsonl");
    expect(task.results.map((result) => result.messageId)).toEqual([
      "finite-task-001-0001",
      "finite-task-001-0002",
      "finite-task-001-0003"
    ]);
    expect(task.results[1]).toMatchObject({
      input: { value: "" },
      result: { valid: true, mock: true, lineNumber: 2 }
    });
  });

  it("publishes ordered JSONL and a fresh Store starts empty", async () => {
    const scheduler = new ManualScheduler();
    const store = createTaskManagementStore(catalog(), scheduler);
    const task = store.createTask(createRequest())!;
    await store.attachSeed({
      taskId: task.taskId,
      eventCode: "extension.worker.string.md5",
      payloadKey: "value",
      file: textFile("seed.txt", "a\nb")
    });
    const completion = store.approveTask(task.taskId);
    await scheduler.advance();
    await scheduler.advance();
    await completion;

    const download = store.downloadTask(task.taskId)!;
    const jsonl = await readBlob(download.blob);
    expect(download.fileName).toBe("finite-task-001.mock.jsonl");
    expect(
      jsonl
        .trim()
        .split("\n")
        .map((line) => JSON.parse(line))
    ).toEqual(task.results);

    setActivePinia(createPinia());
    expect(createTaskManagementStore(catalog()).tasks).toEqual([]);
  });
});

function catalog() {
  return {
    entries: [
      {
        workerGroupId: "scenario-string-utils-workers",
        workerGroup: {
          eventCodes: ["extension.worker.string.md5"]
        }
      },
      {
        workerGroupId: "missing-group",
        workerGroup: null
      }
    ]
  };
}

function createRequest() {
  return {
    taskId: "finite-task-001",
    workerGroupId: "scenario-string-utils-workers",
    config: {
      priority: 50,
      maximumCandidateWorkers: 10,
      maxRetryTimes: 3
    }
  };
}

function bytes(value: string): ArrayBuffer {
  return new TextEncoder().encode(value).buffer;
}

function textFile(name: string, contents: string): File {
  const file = new File([contents], name, { type: "text/plain" });
  Object.defineProperty(file, "arrayBuffer", {
    configurable: true,
    value: vi.fn(async () => bytes(contents))
  });
  return file;
}

function readBlob(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}

class ManualScheduler implements TaskManagementScheduler {
  private currentMillis = Date.parse("2026-08-21T00:00:00Z");
  private readonly pending: Array<{ delayMillis: number; resolve: () => void }> = [];

  now(): number {
    return this.currentMillis;
  }

  wait(delayMillis: number): Promise<void> {
    return new Promise((resolve) => {
      this.pending.push({ delayMillis, resolve });
    });
  }

  pendingDelay(): number | undefined {
    return this.pending[0]?.delayMillis;
  }

  async advance(): Promise<void> {
    const next = this.pending.shift();
    if (next === undefined) throw new Error("No scheduled transition");
    this.currentMillis += next.delayMillis;
    next.resolve();
    await Promise.resolve();
    await Promise.resolve();
  }
}
