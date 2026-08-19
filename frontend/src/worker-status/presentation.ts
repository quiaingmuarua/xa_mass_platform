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
  eligible: {
    label: "Eligible",
    tone: "success",
    description: "Score 位于当前 Kernel epoch 的普通可调度 HOT 区间。"
  },
  "pre-epoch-hot": {
    label: "Awaiting Probe",
    tone: "warning",
    description: "启动前遗留 HOT，暂不进入普通 Candidate，等待 Serviceability 验证。"
  },
  leased: {
    label: "Leased",
    tone: "primary",
    description: "Score 位于 Lease 区间；这不是 Worker 正在执行的证明。"
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
