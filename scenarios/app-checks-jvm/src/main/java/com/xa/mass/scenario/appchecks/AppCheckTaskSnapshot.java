package com.xa.mass.scenario.appchecks;

import com.xa.mass.kernel.assignment.WorkerQuery;
import java.util.Map;
import com.xa.mass.workerdelivery.json.Jsons;

/** Reconstructed solely from the immutable Task, never from current application configuration. */
record AppCheckTaskSnapshot(String eventCode, WorkerQuery selector, AppCheckContract contract,
                            AppCheckContract.Context context) {
    static AppCheckTaskSnapshot read(String workerGroupId, Map<String, String> metadata, AppCheckContracts contracts) {
        if (!"3".equals(metadata.get("inputVersion"))) throw new IllegalArgumentException("Unsupported App Checks inputVersion");
        var contract = contracts.require(metadata.get("contractId"));
        var parameters = contract.normalizeParameters(Jsons.parseObject(metadata.get("contractParameters")));
        return new AppCheckTaskSnapshot(AppCheckSpecification.text(metadata.get("eventCode"), 256),
                AppCheckSettings.selector(Jsons.parseObject(metadata.get("workerSelector"))), contract,
                new AppCheckContract.Context(metadata.get("appId"), metadata.get("country"), workerGroupId,
                        metadata.get("salt"), parameters));
    }
}
