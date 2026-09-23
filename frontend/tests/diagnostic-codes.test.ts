import { describe, expect, it, vi } from "vitest";

import {
  loadPlatformDiagnosticCodes,
  PLATFORM_DIAGNOSTIC_CODES_URL
} from "@/diagnostic-codes/client";
import type { DiagnosticCodeLoadError } from "@/diagnostic-codes/client";
import {
  filterDiagnosticCodes,
  flattenDiagnosticCodes
} from "@/diagnostic-codes/model";
import {
  PLATFORM_DIAGNOSTIC_CODE_NOTICE,
  platformDiagnosticCodesSchema,
  type PlatformDiagnosticCodes
} from "@/diagnostic-codes/schema";

function validDictionary(): PlatformDiagnosticCodes {
  return platformDiagnosticCodesSchema.parse({
    schemaVersion: 1,
    version: "0.4.0",
    gitCommit: "0123456789abcdef0123456789abcdef01234567",
    notice: PLATFORM_DIAGNOSTIC_CODE_NOTICE,
    owners: [
      {
        owner: "server_jvm",
        module: "server_jvm",
        definition: "com.xa.mass.server.error.ServerErrorCode",
        surface: "Server Runtime API ApiErrorResponse",
        codes: [
          {
            code: 12002,
            name: "TASK_NOT_FOUND",
            meaning: "Task was not found"
          }
        ]
      },
      {
        owner: "transport:netty-adapter",
        module: "transport:netty-adapter",
        definition: "example.WorkerDeliveryAdapterErrorCode",
        surface: "Netty Worker Delivery Adapter diagnostics",
        codes: [
          {
            code: 3302,
            name: "ADAPTER_SAMPLE",
            meaning: "Adapter sample meaning"
          }
        ]
      },
      {
        owner: "transport:worker-core",
        module: "transport:worker-core",
        definition: "com.xa.mass.worker.error.WorkerErrorCode",
        surface: "Shared Platform Worker runtime diagnostics",
        codes: [
          {
            code: 3302,
            name: "EVENT_NOT_FOUND",
            meaning: "Worker event definition was not found"
          }
        ]
      }
    ]
  });
}

describe("Platform diagnostic dictionary schema", () => {
  it("accepts a strict v1 current-build projection with cross-owner code reuse", () => {
    const dictionary = validDictionary();

    expect(dictionary.owners.flatMap((owner) => owner.codes)).toHaveLength(3);
    expect(
      flattenDiagnosticCodes(dictionary).filter((row) => row.code === 3302)
    ).toHaveLength(2);
  });

  it("rejects unknown schema versions, missing fields, and extra fields", () => {
    const dictionary = validDictionary();

    expect(
      platformDiagnosticCodesSchema.safeParse({
        ...dictionary,
        schemaVersion: 2
      }).success
    ).toBe(false);
    expect(
      platformDiagnosticCodesSchema.safeParse({
        ...dictionary,
        gitCommit: undefined
      }).success
    ).toBe(false);
    expect(
      platformDiagnosticCodesSchema.safeParse({
        ...dictionary,
        generatedAt: "2026-08-26T00:00:00Z"
      }).success
    ).toBe(false);
  });

  it("rejects duplicate owner/code pairs", () => {
    const dictionary = validDictionary();
    const server = dictionary.owners[0];
    const duplicate = {
      ...dictionary,
      owners: [
        {
          ...server,
          codes: [server.codes[0], server.codes[0]]
        },
        ...dictionary.owners.slice(1)
      ]
    };

    expect(platformDiagnosticCodesSchema.safeParse(duplicate).success).toBe(false);
  });
});

describe("Platform diagnostic dictionary filtering", () => {
  const rows = flattenDiagnosticCodes(validDictionary());

  it.each([
    ["3302", 2],
    ["task was", 1],
    ["EVENT_NOT_FOUND", 1],
    ["WORKER-CORE", 1],
    ["not present", 0],
    ["", 3]
  ])("searches %s across safe fields", (query, expectedCount) => {
    expect(filterDiagnosticCodes(rows, "all", query)).toHaveLength(expectedCount);
  });

  it("filters by owner without collapsing a reused number", () => {
    expect(filterDiagnosticCodes(rows, "transport:netty-adapter", "")).toMatchObject([
      { owner: "transport:netty-adapter", code: 3302 }
    ]);
  });
});

describe("Platform diagnostic dictionary loading", () => {
  it("loads and validates the exact public JSON path", async () => {
    const fetcher = vi.fn(
      async () => new Response(JSON.stringify(validDictionary()), { status: 200 })
    ) as unknown as typeof fetch;

    await expect(loadPlatformDiagnosticCodes(undefined, fetcher)).resolves.toEqual(
      validDictionary()
    );
    expect(fetcher).toHaveBeenCalledWith(
      PLATFORM_DIAGNOSTIC_CODES_URL,
      expect.objectContaining({ headers: { Accept: "application/json" } })
    );
  });

  it("separates a missing build projection from an incompatible schema", async () => {
    const missing = vi.fn(
      async () => new Response("missing", { status: 404 })
    ) as unknown as typeof fetch;
    const incompatible = vi.fn(
      async () => new Response(JSON.stringify({ schemaVersion: 2 }), { status: 200 })
    ) as unknown as typeof fetch;

    await expect(loadPlatformDiagnosticCodes(undefined, missing)).rejects.toMatchObject(
      {
        kind: "unavailable"
      } satisfies Partial<DiagnosticCodeLoadError>
    );
    await expect(
      loadPlatformDiagnosticCodes(undefined, incompatible)
    ).rejects.toMatchObject({
      kind: "incompatible"
    } satisfies Partial<DiagnosticCodeLoadError>);
  });
});
