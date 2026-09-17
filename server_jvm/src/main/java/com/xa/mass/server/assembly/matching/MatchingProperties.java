package com.xa.mass.server.assembly.matching;

import com.xa.mass.workermatching.MatchingGroup;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xa.mass.worker-matching", ignoreUnknownFields = false)
public record MatchingProperties(Map<String, MatchingGroup> groups) {
    public MatchingProperties { groups = groups == null ? Map.of() : Map.copyOf(groups); }
}
