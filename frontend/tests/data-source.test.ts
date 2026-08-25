import { AxiosError, type AxiosInstance } from "axios";
import { describe, expect, it, vi } from "vitest";

import { createRuntimeViewerDataSource } from "@/runtime-viewer/data-source";
import { HttpRuntimeViewerDataSource } from "@/runtime-viewer/http-data-source";
import { MockRuntimeViewerDataSource } from "@/runtime-viewer/mock-data-source";
import { configuredEntry, groupPreview, preview, worker } from "./fixtures";

describe("RuntimeViewerDataSource selection", () => {
  it("uses HTTP in API mode and never installs a Mock fallback", () => {
    const source = createRuntimeViewerDataSource({
      mode: "api",
      apiBaseUrl: "/api"
    });

    expect(source).toBeInstanceOf(HttpRuntimeViewerDataSource);
    expect(source).not.toBeInstanceOf(MockRuntimeViewerDataSource);
  });

  it("uses fixed Mock data only in explicit Mock mode", () => {
    const source = createRuntimeViewerDataSource({
      mode: "mock",
      apiBaseUrl: "/api"
    });

    expect(source).toBeInstanceOf(MockRuntimeViewerDataSource);
  });
});

describe("MockRuntimeViewerDataSource", () => {
  it("mirrors the Runtime View business-not-found response class", async () => {
    const source = new MockRuntimeViewerDataSource();

    await expect(
      source.previewWorkers("missing-group", 100, null)
    ).rejects.toMatchObject({
      kind: "http",
      status: 400,
      code: 15001
    });
  });
});

describe("HttpRuntimeViewerDataSource", () => {
  it("loads the configured resource directory with one GET", async () => {
    const get = vi.fn().mockResolvedValue({
      data: {
        entries: [configuredEntry("group-a")]
      }
    });
    const source = new HttpRuntimeViewerDataSource("/api", {
      get
    } as unknown as AxiosInstance);

    await expect(source.loadConfiguredResources()).resolves.toMatchObject({
      entries: [{ workerGroupId: "group-a" }]
    });
    expect(get).toHaveBeenCalledTimes(1);
    expect(get.mock.calls[0]?.[0]).toBe("/v1/runtime-view/configured-resources");
    expect(get.mock.calls[0]?.[1].headers["X-Request-Id"]).toEqual(expect.any(String));
  });

  it("sends one request with a request ID and validates a preview", async () => {
    const post = vi.fn().mockResolvedValue({
      data: preview("group/a", [worker("group/a", "worker-a")])
    });
    const source = new HttpRuntimeViewerDataSource("/api", {
      post
    } as unknown as AxiosInstance);

    await expect(source.previewWorkers("group/a", 100, null)).resolves.toMatchObject({
      workerGroupId: "group/a",
      returnedCount: 1
    });
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0]?.[0]).toBe(
      "/v1/runtime-view/worker-groups/group%2Fa/workers:preview"
    );
    expect(post.mock.calls[0]?.[1]).toEqual({
      sampleLimit: 100,
      filter: null
    });
    expect(post.mock.calls[0]?.[2].headers["X-Request-Id"]).toEqual(expect.any(String));
  });

  it("loads a bounded WorkerGroup preview without using configured resources", async () => {
    const post = vi.fn().mockResolvedValue({
      data: groupPreview(["group-b", "group-a"])
    });
    const source = new HttpRuntimeViewerDataSource("/api", {
      post
    } as unknown as AxiosInstance);

    await expect(source.previewWorkerGroups(100)).resolves.toMatchObject({
      returnedCount: 2,
      workerGroups: [{ workerGroupId: "group-b" }, { workerGroupId: "group-a" }]
    });
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0]?.[0]).toBe("/v1/runtime-view/worker-groups:preview");
    expect(post.mock.calls[0]?.[1]).toEqual({ sampleLimit: 100 });
    expect(post.mock.calls[0]?.[2].headers["X-Request-Id"]).toEqual(expect.any(String));
  });

  it("rejects malformed API data instead of displaying it", async () => {
    const post = vi.fn().mockResolvedValue({
      data: {
        ...preview("group-a", []),
        total: 42
      }
    });
    const source = new HttpRuntimeViewerDataSource("/api", {
      post
    } as unknown as AxiosInstance);

    await expect(source.previewWorkers("group-a", 100, null)).rejects.toMatchObject({
      kind: "schema",
      message: "Runtime View 返回了无法识别的数据。"
    });
    expect(post).toHaveBeenCalledTimes(1);
  });

  it("maps a 503 to a safe message, preserves request ID, and does not retry", async () => {
    const error = new AxiosError("redis key xa_mass:profile_secret:worker:groups");
    Object.assign(error, {
      response: {
        status: 503,
        data: {
          code: 15002,
          message: "redis key xa_mass:profile_secret:worker:groups",
          requestId: "request-from-server"
        }
      }
    });
    const post = vi.fn().mockRejectedValue(error);
    const source = new HttpRuntimeViewerDataSource("/api", {
      post
    } as unknown as AxiosInstance);

    await expect(source.previewWorkers("group-a", 100, null)).rejects.toMatchObject({
      kind: "http",
      status: 503,
      code: 15002,
      requestId: "request-from-server",
      message: "Runtime View 暂时无法从 Owner 读取数据。"
    });
    expect(post).toHaveBeenCalledTimes(1);
  });

  it("rejects configured descriptor identity drift", async () => {
    const entry = configuredEntry("group-a");
    entry.task = {
      ...entry.task!,
      taskId: "another-task"
    };
    const get = vi.fn().mockResolvedValue({
      data: {
        entries: [entry]
      }
    });
    const source = new HttpRuntimeViewerDataSource("/api", {
      get
    } as unknown as AxiosInstance);

    await expect(source.loadConfiguredResources()).rejects.toMatchObject({
      kind: "schema"
    });
  });
});
