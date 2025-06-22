package com.xa.mass.core.getway.model.payload;

import com.xa.mass.core.getway.model.massMessage.TaskStep;

import java.util.List;

public class TaskPayload {
    private List<TaskStep> steps;

    public List<TaskStep> getSteps() {
        return steps;
    }

    public void setSteps(List<TaskStep> steps) {
        this.steps = steps;
    }
}
