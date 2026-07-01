package com.xa.mass.task.runtime;

public interface TaskRuntimeSchedulerPort {

    void updateTaskEligibility(UpdateSchedulerEligibilityCommand command);

    SchedulerDiscoveryOutcome discoverEligibleTasks(SchedulerDiscoveryCommand command);

    void markTaskDirty(String taskId);
}
