import type { InjectionKey } from "vue";
import type {
  TaskHistorySource,
  HistoryEntry,
  HistoryItem,
  HistoryPreview
} from "./source";
export const historySourceKey: InjectionKey<TaskHistorySource> =
  Symbol("task-history-source");
export type HistorySession = {
  rows: HistoryEntry[];
  listReadAt: number;
  listError: string;
  listBusy: boolean;
  listVersion: number;
  nextCursor: string | null;
  details: Record<
    string,
    {
      entry?: HistoryEntry;
      preview?: HistoryPreview;
      items?: HistoryItem[];
      nextItem: string | null;
      readAt: number;
      previewReadAt: number;
      error: string;
      previewError: string;
      busy: boolean;
      version: number;
      tab: string;
    }
  >;
  scroll: number;
};
export const historySessionsKey: InjectionKey<Map<string, HistorySession>> = Symbol(
  "task-history-sessions"
);
