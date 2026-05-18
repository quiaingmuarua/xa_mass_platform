package com.xa.mass.testing.soak;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record SoakTraceProof(boolean enabled,
                      String path,
                      Object validation,
                      Object stats,
                      long droppedCount,
                      List<?> analyses) {

    Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("path", path);
        values.put("validation", validation);
        values.put("stats", stats);
        values.put("droppedCount", droppedCount);
        values.put("analyses", analyses);
        return values;
    }
}
