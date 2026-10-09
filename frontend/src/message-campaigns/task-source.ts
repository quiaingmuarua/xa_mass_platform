import type { InjectionKey } from "vue";

// Page data, not a proposed HTTP contract. Scheduling state is independent of Results.
export type MessageTaskState =
  | "pre_review"
  | "running-initial"
  | "running_visible"
  | "terminal";
export type MessageCountry = "CN" | "US" | "GB";
export interface MessageApplication {
  id: string;
  label: string;
  workerGroupId: string;
}
export type MessageDemoAction =
  | "advance"
  | "complete"
  | "delivered"
  | "read"
  | "reply"
  | "fail-read";
export interface MessageTask {
  taskId: string;
  name?: string;
  appId?: string;
  inputVersion?: string;
  createdAtMillis: number;
  workerGroupId: string | null;
  managed: boolean;
  state: MessageTaskState | null;
  recipientCountry?: MessageCountry;
  senderCountry?: MessageCountry | null;
  sendTotal?: number;
  // Whole-task observation supplied separately from the bounded Result preview.
  deliveredCount?: number;
  sentCount?: number;
  readCount?: number;
  repliedCount?: number;
  failedCount?: number;
  senderPhone?: string;
  body?: string;
}
export interface MessageTaskResult {
  messageId: string;
  recipientId?: string;
  resultStatus: "succeeded" | "failed";
  status?: "SENT" | "DELIVERED" | "READ" | "REPLIED" | "EXECUTION_FAILED";
  contentError?: string;
  phone?: string;
  workerId?: string;
  reply?: string;
  observedAtMillis?: number;
}
export interface MessageTaskDetail {
  task: MessageTask;
  results: MessageTaskResult[];
  resultsTruncated: boolean;
}
export interface CreateMessageTask {
  appId: string;
  requestId: string;
  name: string;
  recipientCountry: MessageCountry;
  senderCountry: MessageCountry | null;
  body: string;
}
export interface MessageTaskSource {
  readonly mode: "api" | "mock";
  readonly applications: readonly MessageApplication[];
  listTasks(): Promise<{ tasks: MessageTask[]; truncated: boolean }>;
  loadTask(taskId: string): Promise<MessageTaskDetail>;
  createTask(input: CreateMessageTask): Promise<{ taskId: string }>;
  importRecipients(taskId: string, text: string): Promise<MessageImportReceipt>;
  approveTask(taskId: string, expectedCount: number): Promise<void>;
  closeTask(taskId: string): Promise<void>;
  demonstrate?(taskId: string, action: MessageDemoAction): Promise<void>;
}
export interface MessageImportReceipt {
  taskId: string;
  inputCount: number;
  emptyCount: number;
  duplicateCount: number;
  uniqueCount: number;
  confirmedAddedCount: number;
  existingCount: number;
}
export const messageTaskSourceKey: InjectionKey<MessageTaskSource> =
  Symbol("message-task-source");

export class MessageTaskCreationUnconfirmed extends Error {
  constructor(
    message: string,
    public readonly taskId?: string
  ) {
    super(message);
  }
}

export const taskStateLabels: Record<MessageTaskState, string> = {
  pre_review: "待审核",
  "running-initial": "发送中",
  running_visible: "发送中",
  terminal: "调度已结束"
};
export function taskStateLabel(state: MessageTaskState | null): string {
  return state === null ? "状态不可用" : taskStateLabels[state];
}
export function senderRange(country: MessageCountry | null | undefined): string {
  return country === null ? "不限国家" : (country ?? "不可用");
}
