package com.example.tui.engine.plan;

import java.util.*;

/**
 * 完整的执行计划，包含标题和一系列步骤。
 */
public class Plan {

    private String title;
    private String originalTask;
    private List<PlanStep> steps = new ArrayList<>();

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getOriginalTask() { return originalTask; }
    public void setOriginalTask(String originalTask) { this.originalTask = originalTask; }

    public List<PlanStep> getSteps() { return steps; }
    public void setSteps(List<PlanStep> steps) { this.steps = steps; }

    public void updateStepStatus(int stepId, PlanStep.Status status, String result) {
        for (PlanStep step : steps) {
            if (step.getId() == stepId) {
                step.setStatus(status);
                if (result != null) step.setResult(result);
                break;
            }
        }
    }

    public PlanStep getStep(int stepId) {
        for (PlanStep step : steps) {
            if (step.getId() == stepId) return step;
        }
        return null;
    }

    public int completedCount() {
        int count = 0;
        for (PlanStep s : steps) {
            if (s.getStatus() == PlanStep.Status.COMPLETE) count++;
        }
        return count;
    }

    public int totalCount() {
        return steps.size();
    }

    public boolean isComplete() {
        return completedCount() == steps.size();
    }
}
