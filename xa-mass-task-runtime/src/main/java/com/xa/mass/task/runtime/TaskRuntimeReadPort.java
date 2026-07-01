package com.xa.mass.task.runtime;

import java.util.Optional;

public interface TaskRuntimeReadPort {

    FinalResultWindow readFinalResults(FinalResultReadRequest request);

    Optional<FinalResultRow> getFinalResultByMessageId(String taskId, String messageId);
}
