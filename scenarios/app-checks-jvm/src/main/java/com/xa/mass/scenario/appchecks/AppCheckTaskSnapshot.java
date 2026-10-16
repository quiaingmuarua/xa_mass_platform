package com.xa.mass.scenario.appchecks;

import com.xa.mass.kernel.assignment.WorkerQuery;
import java.util.*;
import com.xa.mass.workerdelivery.json.Jsons;

/** Reconstructed solely from the immutable Task, never from current application configuration. */
record AppCheckTaskSnapshot(String checkLabel, String checkTypeLabel, String eventCode, WorkerQuery selector, int seedsPerItem, AppCheckContract contract,
                            AppCheckContract.Context context) {
    AppCheckTaskSnapshot {
        if (seedsPerItem < 1 || seedsPerItem > contract.maxSeedsPerItem())
            throw new IllegalArgumentException("Unsupported saved seedsPerItem");
    }
    static AppCheckTaskSnapshot read(String workerGroupId, Map<String, String> metadata, AppCheckContracts contracts) {
        if (!"5".equals(metadata.get("inputVersion"))) throw new IllegalArgumentException("Unsupported Batch Checks inputVersion");
        var contract = contracts.require(metadata.get("checkType"));
        if (!contract.seedType().id().equals(metadata.get("seedType"))) throw new IllegalArgumentException("Saved seed type mismatch");
        contract.seedType().requireCountry(metadata.get("country"));
        var parameters = contract.normalizeParameters(Jsons.parseObject(metadata.get("contractParameters")));
        return new AppCheckTaskSnapshot(AppCheckSpecification.text(metadata.get("checkLabel"), 128),
                AppCheckSpecification.text(metadata.get("checkTypeLabel"), 128), AppCheckSpecification.text(metadata.get("eventCode"), 256),
                AppCheckSettings.selector(Jsons.parseObject(metadata.get("workerSelector"))), Integer.parseInt(metadata.get("seedsPerItem")), contract,
                new AppCheckContract.Context(AppCheckSpecification.text(metadata.get("checkId"), 128), metadata.get("country"), workerGroupId,
                        metadata.get("salt"), parameters));
    }

    List<String> inputSeeds(Map<String, Object> payload) {
        var seeds = List.copyOf(contract.inputSeeds(payload));
        validateGroup(seeds);
        return seeds;
    }

    Map<String, Object> payload(List<String> seeds) {
        validateGroup(seeds);
        var payload = contract.payload(context, List.copyOf(seeds));
        if (!inputSeeds(payload).equals(seeds)) throw new IllegalArgumentException("Payload changed seed group");
        return payload;
    }

    String messageId(List<String> seeds) {
        validateGroup(seeds);
        if (seedsPerItem == 1) return contract.seedType().messageId(seeds.getFirst());
        var parts = new ArrayList<String>();
        parts.add("app-checks/v1/seed-group"); parts.add(contract.seedType().id()); parts.addAll(seeds);
        return "group-" + AppCheckSpecification.digest(parts.toArray(String[]::new));
    }

    private void validateGroup(List<String> seeds) {
        if (seeds.isEmpty() || seeds.size() > seedsPerItem || new HashSet<>(seeds).size() != seeds.size())
            throw new IllegalArgumentException("Invalid seed group size or duplicates");
        for (String seed : seeds) {
            if (seed == null || seed.isEmpty() || !seed.equals(contract.seedType().normalize(seed, context.country())))
                throw new IllegalArgumentException("Invalid normalized seed");
            contract.validateSeed(seed);
        }
    }
}
