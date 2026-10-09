package com.xa.mass.scenario.appchecks;

import com.xa.mass.workermatching.FixedWindowPoolDefinition.WindowLimit;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Pool/Group limits shared by the observation projection and the Matching declaration. */
@ConfigurationProperties(prefix = "xa.mass.worker-pools.assignment-window", ignoreUnknownFields = false)
public record AppCheckPoolProperties(Map<String, WindowLimit> groups) {
    public AppCheckPoolProperties {
        if (groups == null || !groups.keySet().equals(Set.copyOf(AppCheckWorkerSupply.APPS.values())))
            throw new IllegalArgumentException("window limits must cover exactly the App Checks Worker Groups");
        groups = Map.copyOf(groups);
    }
}
