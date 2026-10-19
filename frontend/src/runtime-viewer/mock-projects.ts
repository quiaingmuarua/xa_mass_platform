/** Shared declared scopes; resource enablement is independently defined by Group. */
export const MOCK_PROJECTS = [
  {
    projectId: "default",
    workerGroupSource: "registered" as const,
    workerGroupIds: []
  },
  {
    projectId: "pool-validation",
    workerGroupSource: "declared" as const,
    workerGroupIds: ["android-demo-workers", "scenario-string-utils-workers"]
  },
  {
    projectId: "agentforge",
    workerGroupSource: "declared" as const,
    workerGroupIds: ["detect-env", "instagram", "rcs", "telegram"].map(
      (name) => `agentforge-${name}-workers`
    )
  },
  {
    projectId: "app-checks",
    workerGroupSource: "declared" as const,
    workerGroupIds: ["app-a-sim", "app-b-sim"]
  },
  {
    projectId: "messages",
    workerGroupSource: "declared" as const,
    workerGroupIds: ["demo-sim", "app-a-sim", "app-b-sim"]
  }
];

export const MOCK_AGENT_GROUPS = ["detect-env", "instagram", "rcs", "telegram"].map(
  (name) => {
    const workerGroupId = `agentforge-${name}-workers`;
    return {
      workerGroupId,
      workerGroup:
        name === "detect-env"
          ? {
              workerGroupId,
              attributes: { source: "mock" },
              eventCodes: ["extension.worker.detect-env.scenario-catalog"]
            }
          : null
    };
  }
);
