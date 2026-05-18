package com.xa.mass.testing.soak;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record SoakInvariantReport(boolean ok, List<SoakInvariantIssue> issues) {

    Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ok", ok);
        values.put("issueCount", issues.size());
        values.put("issues", issues);
        return values;
    }

    String failureMessage() {
        return issues.toString();
    }
}
