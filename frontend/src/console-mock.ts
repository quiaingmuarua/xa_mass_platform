import type { InjectionKey } from "vue";
import { MockAppCheckTaskSource } from "@/app-checks/mock-task-source";
import { MockMessageTaskSource } from "@/message-campaigns/mock-task-source";

// Business data lives for the application; login-specific views live in the console.
export function createConsoleMock() {
  return {
    appChecks: new MockAppCheckTaskSource(),
    messages: new MockMessageTaskSource()
  };
}
export const consoleMockKey: InjectionKey<ReturnType<typeof createConsoleMock>> =
  Symbol("console-mock");
