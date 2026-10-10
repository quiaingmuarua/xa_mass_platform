package com.xa.mass.workermatching;

import java.util.Objects;
import java.util.Set;

/** Immutable startup declaration; values describe coordinates, never executable strategy code. */
public record PartitionedLeasePoolDefinition(String poolName, String functionName,
        String subjectProperty, String countryProperty, Set<String> partitions) {
    public PartitionedLeasePoolDefinition {
        for (String value : new String[]{poolName, functionName, subjectProperty, countryProperty})
            RuleInputs.text(value);
        partitions = Set.copyOf(Objects.requireNonNull(partitions));
        if (partitions.isEmpty()) throw new IllegalArgumentException("lease partitions must not be empty");
        partitions.forEach(RuleInputs::text);
    }

    public String bucket(String partition, String country) {
        if (!partitions.contains(partition) || !RuleInputs.validCountry(country))
            throw new IllegalArgumentException("invalid lease partition or country");
        return partition.length() + ":" + partition + country;
    }
}
