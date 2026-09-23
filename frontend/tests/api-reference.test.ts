import { describe, expect, it } from "vitest";

import { apiReferenceConfiguration } from "@/api-reference/config";

describe("static API reference", () => {
  it("loads only the checked snapshot and disables hosted request tools", () => {
    expect(apiReferenceConfiguration).toMatchObject({
      url: "/reference/openapi.json",
      agent: {
        disabled: true
      },
      hideTestRequestButton: true,
      showDeveloperTools: "never",
      telemetry: false,
      withDefaultFonts: false
    });
  });
});
