import type { InjectionKey } from "vue";
import { MockCreditSource } from "@/credits/mock-source";
import { MockAppCheckTaskSource } from "@/app-checks/mock-task-source";
import { MockMessageTaskSource } from "@/message-campaigns/mock-task-source";
import type { MockUserDirectorySource } from "@/user-directory-source";

// Business data lives for the application; login-specific views live in the console.
export function createConsoleMock(directory: MockUserDirectorySource) {
  const credits = new MockCreditSource(directory);
  return {
    directory,
    credits,
    appChecks: new MockAppCheckTaskSource(directory, credits),
    messages: new MockMessageTaskSource(directory)
  };
}
export const consoleMockKey: InjectionKey<ReturnType<typeof createConsoleMock>> =
  Symbol("console-mock");
