import { afterEach, expect, it, vi } from "vitest";
import { createRecipientReader } from "../src/message-campaigns/recipient-reader";
import {
  inspectRecipientInput,
  MESSAGE_IMPORT_LIMITS
} from "../src/files/phone-numbers";
afterEach(() => vi.unstubAllGlobals());
it("terminates replaced workers and ignores their late responses without disabling cancellation of the new read", async () => {
  class FakeWorker {
    static instances: FakeWorker[] = [];
    onmessage?: (event: MessageEvent) => void;
    onerror?: () => void;
    terminate = vi.fn();
    postMessage = vi.fn();
    constructor() {
      FakeWorker.instances.push(this);
    }
  }
  vi.stubGlobal("Worker", FakeWorker);
  const reader = createRecipientReader();
  const first = reader
    .read("86123", "CN", MESSAGE_IMPORT_LIMITS)
    .catch((error) => error);
  const second = reader
    .read("86124", "CN", MESSAGE_IMPORT_LIMITS)
    .catch((error) => error);
  const old = FakeWorker.instances[0];
  expect((await first).name).toBe("AbortError");
  expect(old.terminate).toHaveBeenCalledOnce();
  old.onmessage?.({
    data: { value: await inspectRecipientInput("86123", "CN", MESSAGE_IMPORT_LIMITS) }
  } as MessageEvent);
  reader.cancel();
  expect((await second).name).toBe("AbortError");
  expect(FakeWorker.instances[1].terminate).toHaveBeenCalledOnce();
  const third = reader.read("86125", "CN", MESSAGE_IMPORT_LIMITS);
  const value = await inspectRecipientInput("86125", "CN", MESSAGE_IMPORT_LIMITS);
  FakeWorker.instances[2].onmessage?.({ data: { value } } as MessageEvent);
  expect(await third).toEqual(value);
  expect(FakeWorker.instances[2].terminate).toHaveBeenCalledOnce();
});
