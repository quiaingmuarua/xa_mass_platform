package com.xa.mass.task.runtime.starter;

import java.util.List;

public record TaskRuntimeLoopHostStatus(boolean running, List<String> loopNames, String lastFailure) {

    public TaskRuntimeLoopHostStatus {
        loopNames = loopNames == null ? List.of() : List.copyOf(loopNames);
        lastFailure = lastFailure == null ? "" : lastFailure;
    }
}
