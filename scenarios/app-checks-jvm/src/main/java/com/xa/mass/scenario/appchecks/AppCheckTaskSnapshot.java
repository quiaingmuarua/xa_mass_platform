package com.xa.mass.scenario.appchecks;

import com.xa.mass.kernel.assignment.WorkerQuery;
import java.util.Map;
import com.xa.mass.workerdelivery.json.Jsons;

/** Reconstructed solely from the immutable Task, never from current application configuration. */
record AppCheckTaskSnapshot(String checkLabel, String checkTypeLabel, String eventCode, WorkerQuery selector, AppCheckContract contract,
                            AppCheckContract.Context context) {
    static AppCheckTaskSnapshot read(String workerGroupId, Map<String, String> metadata, AppCheckContracts contracts) {
        if (!"4".equals(metadata.get("inputVersion"))) throw new IllegalArgumentException("Unsupported Batch Checks inputVersion");
        var contract = contracts.require(metadata.get("checkType"));
        if (!contract.seedType().id().equals(metadata.get("seedType"))) throw new IllegalArgumentException("Saved seed type mismatch");
        contract.seedType().requireCountry(metadata.get("country"));
        var parameters = contract.normalizeParameters(Jsons.parseObject(metadata.get("contractParameters")));
        return new AppCheckTaskSnapshot(AppCheckSpecification.text(metadata.get("checkLabel"), 128),
                AppCheckSpecification.text(metadata.get("checkTypeLabel"), 128), AppCheckSpecification.text(metadata.get("eventCode"), 256),
                AppCheckSettings.selector(Jsons.parseObject(metadata.get("workerSelector"))), contract,
                new AppCheckContract.Context(AppCheckSpecification.text(metadata.get("checkId"), 128), metadata.get("country"), workerGroupId,
                        metadata.get("salt"), parameters));
    }
}
