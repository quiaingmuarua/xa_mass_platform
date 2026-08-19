import type {
  WorkerNetworkState,
  WorkerSchedulingState,
  WorkerStatusAxis
} from "./types";

export type WorkerStatusTagTone = "primary" | "success" | "info" | "warning" | "danger";

export interface WorkerStatusPresentation {
  label: string;
  tone: WorkerStatusTagTone;
  description: string;
}

export interface WorkerStatusAxisPresentation extends WorkerStatusPresentation {
  auxiliary?: "Refreshing" | "Stale" | "Unavailable";
}

const NETWORK_PRESENTATIONS: Record<WorkerNetworkState, WorkerStatusPresentation> = {
  connected: {
    label: "Connected",
    tone: "success",
    description: "Adapter 当前存在已验证且活动的 Route。"
  },
  disconnected: {
    label: "Disconnected",
    tone: "info",
    description: "Adapter 当前保留断开的 Route 证据。"
  },
  unknown: {
    label: "Unknown",
    tone: "info",
    description: "Adapter 没有可用于判断的当前 Route 证据。"
  }
};

const SCHEDULING_PRESENTATIONS: Record<
  WorkerSchedulingState,
  WorkerStatusPresentation
> = {
  "due-hot": {
    label: "HOT Due",
    tone: "success",
    description: "HOT Score 已到期；当前Kernel epoch和其他策略仍可能过滤该Worker。"
  },
  "held-hot": {
    label: "HOT Held",
    tone: "primary",
    description: "HOT Score 位于当前或未来时间槽；这不证明Worker正在执行。"
  },
  paused: {
    label: "Paused",
    tone: "info",
    description: "Worker 处于人工暂停坐标，连接 Evidence 不修改该状态。"
  },
  recovery: {
    label: "Recovery",
    tone: "warning",
    description: "Worker 位于负数轴恢复重试区间。"
  },
  cold: {
    label: "Cold",
    tone: "danger",
    description: "Worker 位于冷停恢复区间。"
  },
  missing: {
    label: "Score Missing",
    tone: "info",
    description: "当前没有 Worker Score；这不表示 Worker Descriptor 不存在。"
  }
};

export function presentNetworkState(
  state: WorkerNetworkState
): WorkerStatusPresentation {
  return NETWORK_PRESENTATIONS[state];
}

export function presentSchedulingState(
  state: WorkerSchedulingState
): WorkerStatusPresentation {
  return SCHEDULING_PRESENTATIONS[state];
}

export function presentStatusAxis(
  axis: WorkerStatusAxis<unknown>,
  ownerState?: WorkerStatusPresentation
): WorkerStatusAxisPresentation {
  if (ownerState !== undefined) {
    return {
      ...ownerState,
      tone: axis.stale ? "warning" : ownerState.tone,
      auxiliary: axis.stale
        ? "Stale"
        : axis.status === "loading"
          ? "Refreshing"
          : axis.status === "error"
            ? "Unavailable"
            : undefined
    };
  }
  if (axis.observation !== undefined) {
    return {
      label: "Observed",
      tone: axis.stale ? "warning" : "info",
      description: "已有最近一次成功观测。",
      auxiliary: axis.stale
        ? "Stale"
        : axis.status === "loading"
          ? "Refreshing"
          : axis.status === "error"
            ? "Unavailable"
            : undefined
    };
  }
  if (axis.status === "loading") {
    return {
      label: "Loading",
      tone: "info",
      description: "正在读取这一条观测轴。"
    };
  }
  if (axis.status === "error") {
    return {
      label: "Unavailable",
      tone: "info",
      description: "观测请求失败；不能转换成任何 Worker 状态。",
      auxiliary: "Unavailable"
    };
  }
  return {
    label: "Not observed",
    tone: "info",
    description: "当前浏览器会话尚未读取这一条观测轴。"
  };
}
