package com.xa.mass.gateway.model.payload;

import com.xa.mass.gateway.model.massMessage.TaskStep;

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
