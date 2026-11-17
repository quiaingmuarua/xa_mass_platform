import type { InjectionKey } from "vue";
import type {
  TaskHistorySource,
  HistoryEntry,
  HistoryItem,
  HistoryPreview
} from "./source";
export const historySourceKey: InjectionKey<TaskHistorySource> =
  Symbol("task-history-source");
type Session = {
  rows: HistoryEntry[];
  listReadAt: number;
  listError: string;
  listBusy: boolean;
  nextCursor: string | null;
  details: Record<
    string,
    {
      entry?: HistoryEntry;
      preview?: HistoryPreview;
      items?: HistoryItem[];
      nextItem: string | null;
      readAt: number;
      error: string;
      previewError: string;
      busy: boolean;
    }
  >;
  scroll: number;
};
export const historySessionsKey: InjectionKey<Map<string, Session>> = Symbol(
  "task-history-sessions"
);
