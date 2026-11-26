import type { InjectionKey } from "vue";
import { MockTaskHistorySource } from "@/task-history/source";
import { MockCreditSource } from "@/credits/mock-source";
import { MockAppCheckTaskSource } from "@/app-checks/mock-task-source";
import { MockMessageTaskSource } from "@/message-campaigns/mock-task-source";
import type { MockUserDirectorySource } from "@/user-directory-source";
import { MockWorkerConnectionControlSource } from "@/runtime-viewer/connection-control";
import { MockWorkerGroupManagementSource } from "@/runtime-viewer/worker-group-management";

// Business data lives for the application; login-specific views live in the console.
export function createConsoleMock(directory: MockUserDirectorySource) {
  const credits = new MockCreditSource(directory);
  const history = new MockTaskHistorySource(directory);
  history.seedExamples();
  return {
    directory,
    credits,
    history,
    workerConnectionControl: new MockWorkerConnectionControlSource(directory),
    workerGroupManagement: new MockWorkerGroupManagementSource(),
    appChecks: new MockAppCheckTaskSource(directory, credits, Date.now, history),
    messages: new MockMessageTaskSource(directory, undefined, Date.now, history)
  };
}
export const consoleMockKey: InjectionKey<ReturnType<typeof createConsoleMock>> =
  Symbol("console-mock");
