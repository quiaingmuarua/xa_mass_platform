import { describe, expect, it } from "vitest";
import type { TaskRow } from "@/components/RawTaskTable.vue";
import {
  formatCompactTime,
  formatDateTime,
  formatDuration,
  formatOutcomeItems,
  formatRefillList,
  isRunningBand,
  presentFiniteStatus,
  presentResultStatus,
  presentRpcStatus,
  presentTaskSource,
  splitTaskMetadata,
  summarizeTaskStats
} from "@/task-workbench/presentation";

function taskRow(overrides: Partial<TaskRow> = {}): TaskRow {
  return {
    taskId: "t-1",
    createdAtMillis: 1_700_000_000_000,
    scoreBand: "pre_review",
    retention: null,
    stats: undefined,
    task: {
      taskId: "t-1",
      projectId: "default",
      workerGroupId: "g1",
      idleDisposition: "CLOSE_WHEN_IDLE",
      refill: [],
      name: "demo",
      metadata: { "server.inputOwner": "raw", userId: "1000" },
      config: { priority: "50", maxRetryTimes: "3" }
    },
    ...overrides
  };
}

describe("task-workbench presentation", () => {
  it("summarizes TaskStats and formats concise non-zero outcome items", () => {
    expect(summarizeTaskStats(undefined)).toBeNull();
    expect(
      summarizeTaskStats({
        total: 100,
        countsByTag: { "1": 20, "2": 2, "5": 8, "6": 50, "7": 15, "9": 5 }
      })
    ).toEqual({
      total: 100,
      active: 20,
      failed: 8,
      succeeded: 70,
      observedLater: 20,
      other: 2,
      completed: 80,
      percent: 80,
      succeededPercent: 70,
      failedPercent: 8
    });

    expect(formatOutcomeItems({ total: 250, countsByTag: { "6": 250 } })).toEqual([
      { text: "250 成功", tone: "normal" }
    ]);
    expect(
      formatOutcomeItems({ total: 500, countsByTag: { "5": 18, "6": 482 } })
    ).toEqual([
      { text: "482 成功", tone: "normal" },
      { text: "18 失败", tone: "danger" }
    ]);
    expect(formatOutcomeItems({ total: 0, countsByTag: {} })).toEqual([
      { text: "无执行项", tone: "muted" }
    ]);
  });

  it("classifies finite task statuses concisely while retaining raw scoreBand and archived flag", () => {
    expect(isRunningBand("running-initial")).toBe(true);
    expect(isRunningBand("running_visible")).toBe(true);
    expect(isRunningBand("pre_review")).toBe(false);
    expect(isRunningBand("terminal")).toBe(false);

    expect(
      presentFiniteStatus(
        taskRow({
          scoreBand: "pre_review",
          stats: { total: 10, countsByTag: { "1": 10 } }
        })
      )
    ).toMatchObject({
      label: "待审核",
      rawBand: "pre_review",
      archived: false,
      category: "pre_review",
      tone: "warning"
    });

    expect(
      presentFiniteStatus(
        taskRow({
          scoreBand: "running-initial",
          stats: { total: 10, countsByTag: { "1": 10 } }
        })
      )
    ).toMatchObject({
      label: "初始化",
      rawBand: "running-initial",
      category: "running"
    });

    expect(
      presentFiniteStatus(
        taskRow({
          scoreBand: "running_visible",
          stats: { total: 10, countsByTag: { "1": 4, "6": 6 } }
        })
      )
    ).toMatchObject({
      label: "运行中",
      rawBand: "running_visible",
      category: "running"
    });

    expect(
      presentFiniteStatus(
        taskRow({
          scoreBand: "terminal",
          stats: { total: 0, countsByTag: {} }
        })
      )
    ).toMatchObject({
      label: "已终止",
      rawBand: "terminal",
      category: "attention"
    });

    expect(
      presentFiniteStatus(
        taskRow({
          scoreBand: "terminal",
          stats: { total: 20, countsByTag: { "1": 5, "6": 15 } }
        })
      )
    ).toMatchObject({
      label: "已终止",
      rawBand: "terminal",
      category: "attention",
      tone: "warning"
    });

    expect(
      presentFiniteStatus(
        taskRow({
          scoreBand: "terminal",
          stats: { total: 20, countsByTag: { "6": 18, "8": 2 } },
          retention: {
            phase: "RELEASED",
            terminatedAtMillis: 1_700_000_100_000,
            frozenAtMillis: 1_700_000_200_000,
            expiresAtMillis: null,
            historyId: "h1",
            historyExpiresAtMillis: null
          }
        })
      )
    ).toMatchObject({
      label: "已完成",
      archived: true,
      category: "succeeded",
      tone: "success"
    });

    expect(
      presentFiniteStatus(
        taskRow({
          scoreBand: "terminal",
          stats: { total: 10, countsByTag: { "5": 10 } }
        })
      )
    ).toMatchObject({
      label: "已失败",
      category: "attention",
      tone: "danger"
    });

    expect(
      presentFiniteStatus(
        taskRow({
          scoreBand: "terminal",
          stats: { total: 10, countsByTag: { "5": 3, "6": 7 } }
        })
      )
    ).toMatchObject({
      label: "部分失败",
      category: "attention",
      tone: "warning"
    });

    expect(presentFiniteStatus(taskRow({ scoreBand: null }))).toMatchObject({
      label: "状态不可用",
      rawBand: "unavailable",
      category: "unknown"
    });
  });

  it("presents RPC status, task source, result status, duration, compact time, metadata and refill", () => {
    expect(presentRpcStatus(taskRow({ scoreBand: "running_visible" }))).toMatchObject({
      label: "服务中",
      tone: "success",
      rawBand: "running_visible"
    });
    expect(presentRpcStatus(taskRow({ scoreBand: "pre_review" }))).toMatchObject({
      label: "待启用",
      tone: "warning"
    });
    expect(presentRpcStatus(taskRow({ scoreBand: "terminal" }))).toMatchObject({
      label: "已停用",
      tone: "info"
    });

    expect(presentTaskSource(taskRow())).toEqual({
      label: "Raw",
      raw: true,
      owner: "raw",
      creator: "1000"
    });
    expect(
      presentTaskSource(
        taskRow({
          task: {
            ...taskRow().task!,
            metadata: { "server.inputOwner": "messages", userId: "1001" }
          }
        })
      )
    ).toEqual({
      label: "Scenario · messages",
      raw: false,
      owner: "messages",
      creator: "1001"
    });
    expect(presentTaskSource(taskRow({ task: null }))).toMatchObject({
      label: "描述符缺失",
      raw: false
    });

    expect(presentResultStatus("succeeded")).toMatchObject({
      label: "执行成功",
      tone: "success"
    });
    expect(presentResultStatus("failed")).toMatchObject({
      label: "执行失败",
      tone: "danger"
    });
    expect(presentResultStatus("not_observed")).toMatchObject({
      label: "尚未观测",
      tone: "info"
    });

    expect(formatDateTime(null)).toBe("—");
    expect(formatCompactTime(null)).toBe("—");
    expect(formatCompactTime(1_700_000_000_000)).toMatch(
      /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/
    );
    expect(formatDuration(1000, 1500)).toBe("<1s");
    expect(formatDuration(1000, 13_000)).toBe("12s");
    expect(formatDuration(0, 198_000)).toBe("3m 18s");
    expect(formatDuration(0, 8_100_000)).toBe("2h 15m");
    expect(formatDuration(0, 100_800_000)).toBe("1d 4h");
    expect(formatDuration(2000, 1000)).toBeNull();

    expect(
      splitTaskMetadata({
        "server.inputOwner": "raw",
        userId: "1000",
        env: "prod",
        batch: "b1"
      })
    ).toEqual({
      custom: [
        ["batch", "b1"],
        ["env", "prod"]
      ],
      system: [
        ["server.inputOwner", "raw"],
        ["userId", "1000"]
      ]
    });

    expect(
      formatRefillList([
        { poolName: "country", target: { country: ["CN"] }, count: 100 }
      ])
    ).toEqual(["country × 100"]);
  });
});
