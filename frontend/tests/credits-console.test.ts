import { afterEach, expect, it } from "vitest";
import { createApp, nextTick, type App } from "vue";
import { ElAlert, ElButton, ElTable, ElTableColumn } from "element-plus";
import CreditAccountPanel from "@/credits/CreditAccountPanel.vue";
import { creditSourceKey } from "@/credits/source";
import { consoleIdentity } from "@/console-identity";
import { consoleMockKey, createConsoleMock } from "@/console-mock";
import { MockUserDirectorySource } from "@/user-directory-source";
import { ADMIN } from "./identity-fixture";

let app: App | undefined;
afterEach(() => {
  app?.unmount();
  consoleIdentity.logout();
  document.body.replaceChildren();
});
async function flush() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mount(userId = "1000") {
  const mock = createConsoleMock(new MockUserDirectorySource());
  consoleIdentity.login(userId);
  const host = document.createElement("div");
  document.body.append(host);
  app = createApp(CreditAccountPanel, { userId: "1001" });
  app.provide(creditSourceKey, mock.credits).provide(consoleMockKey, mock);
  for (const component of [ElAlert, ElButton, ElTable, ElTableColumn])
    app.use(component);
  app.mount(host);
  await flush();
  return { mock, host };
}
function fill(label: string, value: string) {
  const input = document.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!;
  input.value = value;
  input.dispatchEvent(new Event("input", { bubbles: true }));
}
function click(text: string) {
  const button = [...document.querySelectorAll("button")].find(
    (el) => el.textContent?.trim() === text
  )!;
  button.click();
}
it("keeps unknown intent on screen, disables new submissions and clears the form after reconciliation", async () => {
  const { mock, host } = await mount();
  expect(host.querySelector('[data-testid="credit-balance"]')?.textContent).toBe("0");
  fill("积分数额", "100");
  fill("调账原因", "manual UI proof");
  mock.credits.dropNextResponse(ADMIN);
  const submit = () =>
    host
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  submit();
  submit();
  await flush();
  expect(host.textContent).toContain("调账结果未确认");
  expect(host.querySelector("fieldset")?.disabled).toBe(true);
  expect((await mock.credits.account(ADMIN, "1001")).latestSequence).toBe(1);
  click("核对结果");
  await flush();
  expect(host.querySelector('[data-testid="credit-balance"]')?.textContent).toBe("100");
  expect(host.querySelector<HTMLInputElement>('[aria-label="积分数额"]')?.value).toBe(
    ""
  );
  expect(host.textContent).toContain("manual UI proof");
  expect(host.textContent).not.toContain("核对原调账请求");
});
it("operator uses the same account panel with no adjustment form", async () => {
  const { mock, host } = await mount("1001");
  await mock.credits.adjust(ADMIN, "1001", {
    requestId: crypto.randomUUID(),
    delta: 70,
    reason: "operator proof"
  });
  click("刷新账户");
  await flush();
  expect(host.querySelector('[data-testid="credit-balance"]')?.textContent).toBe("70");
  expect(host.querySelector("form")).toBeNull();
  expect(host.textContent).toContain("operator proof");
});
