package com.xa.mass.integration.workerdynamicmatching;

import static com.xa.mass.integration.workerdynamicmatching.ProofApi.require;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

final class ProofAssertions {
    private ProofAssertions() { }
    static void snapshot(Map<String, String> actual, Collection<Map<String, String>> committed) {
        require(committed.contains(actual), "uncommitted-or-hybrid-properties");
    }
    static void executor(String coordinate, Set<String> allowed, boolean admitted) {
        require(admitted, "execution-before-eligibility-change");
        require(allowed.contains(coordinate), "unexpected-executing-replica");
    }
    static void identity(String expected, Object observed) {
        require(expected.equals(observed), "worker-identity-drift");
    }
}
