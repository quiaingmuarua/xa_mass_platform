import { afterEach, describe, expect, it, vi } from "vitest";
import { createSmsAvailability } from "../src/sms/availability";

export const smsCatalog = {
  runId: "run-one",
  version: "0.1.0-preview",
  applications: [
    { id: "A", name: "应用 A", templates: [{ id: "A-code", priority: 200 }] }
  ]
};

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("SMS availability", () => {
  it("shares the catalog request, retains the result and does not poll", async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn(async () => new Response(JSON.stringify(smsCatalog)));
    vi.stubGlobal("fetch", fetcher);
    const availability = createSmsAvailability(false);
    const first = availability.load();
    expect(availability.load()).toBe(first);
    await first;
    expect(availability.state.value).toEqual({
      status: "enabled",
      catalog: smsCatalog
    });
    await availability.load();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fetcher).toHaveBeenCalledTimes(1);
    availability.dispose();
  });

  it.each([404, 503, 401])(
    "distinguishes a %s response from successful enablement",
    async (status) => {
      vi.stubGlobal(
        "fetch",
        vi.fn(async () => new Response("null", { status }))
      );
      const availability = createSmsAvailability(false);
      await availability.load();
      expect(availability.state.value.status).toBe(
        status === 404 ? "disabled" : "unavailable"
      );
      availability.dispose();
    }
  );

  it.each([{}, { runId: "r" }, { ...smsCatalog, applications: [] }])(
    "rejects an invalid catalog",
    async (body) => {
      vi.stubGlobal(
        "fetch",
        vi.fn(async () => new Response(JSON.stringify(body)))
      );
      const availability = createSmsAvailability(false);
      await availability.load();
      expect(availability.state.value.status).toBe("unavailable");
      availability.dispose();
    }
  );

  it("times out after five seconds, then admits an explicit retry", async () => {
    vi.useFakeTimers();
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementationOnce(
        (_input, init) =>
          new Promise((_resolve, reject) => {
            init?.signal?.addEventListener("abort", () =>
              reject(new DOMException("Aborted", "AbortError"))
            );
          })
      )
      .mockResolvedValueOnce(new Response(JSON.stringify(smsCatalog)));
    vi.stubGlobal("fetch", fetcher);
    const availability = createSmsAvailability(false);
    const request = availability.load();
    await vi.advanceTimersByTimeAsync(4_999);
    expect(availability.state.value.status).toBe("loading");
    await vi.advanceTimersByTimeAsync(1);
    await request;
    expect(availability.state.value).toMatchObject({
      status: "unavailable",
      message: expect.stringContaining("超时")
    });
    await availability.load(true);
    expect(availability.state.value.status).toBe("enabled");
    expect(fetcher).toHaveBeenCalledTimes(2);
    availability.dispose();
  });

  it("does not fetch in Demo mode, including explicit retry", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const availability = createSmsAvailability(true);
    await availability.load();
    await availability.load(true);
    availability.acceptCatalog(smsCatalog);
    expect(availability.state.value.status).toBe("demo");
    expect(fetcher).not.toHaveBeenCalled();
    availability.dispose();
  });

  it("aborts the request and does not publish a late catalog after disposal", async () => {
    let complete!: (response: Response) => void;
    const fetcher = vi.fn<typeof fetch>(
      () =>
        new Promise((resolve) => {
          complete = resolve;
        })
    );
    vi.stubGlobal("fetch", fetcher);
    const availability = createSmsAvailability(false);
    const request = availability.load();
    availability.dispose();
    expect(fetcher.mock.calls[0]?.[1]?.signal?.aborted).toBe(true);
    complete(new Response(JSON.stringify(smsCatalog)));
    await request;
    expect(availability.state.value.status).not.toBe("enabled");
    await availability.load(true);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});
