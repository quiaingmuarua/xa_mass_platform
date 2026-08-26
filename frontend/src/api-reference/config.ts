import type { ReferenceProps } from "@scalar/api-reference";

export const apiReferenceConfiguration: NonNullable<ReferenceProps["configuration"]> = {
  url: "/reference/openapi.json",
  agent: {
    disabled: true
  },
  documentDownloadType: "json",
  hideTestRequestButton: true,
  showDeveloperTools: "never",
  telemetry: false,
  withDefaultFonts: false
};
