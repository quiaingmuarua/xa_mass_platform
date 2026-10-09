import { inspectRecipientInput, type RecipientLimits } from "@/files/phone-numbers";
self.onmessage = async (
  event: MessageEvent<{
    source: string | File;
    country: string;
    limits: RecipientLimits;
  }>
) => {
  try {
    self.postMessage({
      value: await inspectRecipientInput(
        event.data.source,
        event.data.country,
        event.data.limits
      )
    });
  } catch (error) {
    self.postMessage({
      error: error instanceof Error ? error.message : "号码读取失败"
    });
  }
};
