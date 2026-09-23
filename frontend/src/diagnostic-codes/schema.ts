import { z } from "zod";

export const PLATFORM_DIAGNOSTIC_CODE_NOTICE =
  "Current-build diagnostic lookup only. This is not a compatibility contract.";

export const PLATFORM_DIAGNOSTIC_OWNER_ORDER = [
  "server_jvm",
  "transport:netty-adapter",
  "transport:worker-core"
] as const;

const diagnosticCodeSchema = z
  .object({
    code: z.number().int().positive(),
    name: z.string().regex(/^[A-Z][A-Z0-9_]*$/),
    meaning: z
      .string()
      .min(1)
      .refine((value) => value === value.trim(), "meaning must be trimmed")
  })
  .strict();

const diagnosticOwnerSchema = z
  .object({
    owner: z.string().min(1),
    module: z.string().min(1),
    definition: z.string().min(1),
    surface: z.string().min(1),
    codes: z.array(diagnosticCodeSchema).min(1)
  })
  .strict();

export const platformDiagnosticCodesSchema = z
  .object({
    schemaVersion: z.literal(1),
    version: z.string().min(1),
    gitCommit: z.string().regex(/^[0-9a-f]{40}$/),
    notice: z.literal(PLATFORM_DIAGNOSTIC_CODE_NOTICE),
    owners: z.array(diagnosticOwnerSchema).length(3)
  })
  .strict()
  .superRefine((dictionary, context) => {
    const actualOrder = dictionary.owners.map((owner) => owner.owner);
    PLATFORM_DIAGNOSTIC_OWNER_ORDER.forEach((expectedOwner, index) => {
      if (actualOrder[index] !== expectedOwner) {
        context.addIssue({
          code: "custom",
          message: `owner ${index} must be ${expectedOwner}`,
          path: ["owners", index, "owner"]
        });
      }
    });

    const ownerNames = new Set<string>();
    const ownerCodePairs = new Set<string>();
    const ownerSymbolPairs = new Set<string>();
    dictionary.owners.forEach((owner, ownerIndex) => {
      if (ownerNames.has(owner.owner)) {
        context.addIssue({
          code: "custom",
          message: `duplicate owner ${owner.owner}`,
          path: ["owners", ownerIndex, "owner"]
        });
      }
      ownerNames.add(owner.owner);

      let previousCode = 0;
      owner.codes.forEach((entry, codeIndex) => {
        const pair = `${owner.owner}\u0000${entry.code}`;
        if (ownerCodePairs.has(pair)) {
          context.addIssue({
            code: "custom",
            message: `duplicate owner/code pair ${owner.owner}/${entry.code}`,
            path: ["owners", ownerIndex, "codes", codeIndex, "code"]
          });
        }
        ownerCodePairs.add(pair);
        const symbolPair = `${owner.owner}\u0000${entry.name}`;
        if (ownerSymbolPairs.has(symbolPair)) {
          context.addIssue({
            code: "custom",
            message: `duplicate owner/symbol pair ${owner.owner}/${entry.name}`,
            path: ["owners", ownerIndex, "codes", codeIndex, "name"]
          });
        }
        ownerSymbolPairs.add(symbolPair);
        if (entry.code <= previousCode) {
          context.addIssue({
            code: "custom",
            message: "codes must be strictly ascending within an owner",
            path: ["owners", ownerIndex, "codes", codeIndex, "code"]
          });
        }
        previousCode = entry.code;
      });
    });
  });

export type PlatformDiagnosticCodes = z.infer<typeof platformDiagnosticCodesSchema>;
