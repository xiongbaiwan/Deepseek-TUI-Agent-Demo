package com.example.tui.engine.plan;

/**
 * 将计划渲染为终端可读的 ANSI 格式文本。
 */
public class PlanFormatter {

    private static final String CYAN = "[36m";
    private static final String GREEN = "[32m";
    private static final String YELLOW = "[33m";
    private static final String RED = "[31m";
    private static final String BOLD = "[1m";
    private static final String DIM = "[2m";
    private static final String RESET = "[0m";

    /**
     * 渲染计划步骤列表（不含执行结果）。
     */
    public static String renderSteps(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(CYAN).append(BOLD).append("  Plan: ").append(plan.getTitle()).append(RESET).append("\n");
        if (plan.getOriginalTask() != null && !plan.getOriginalTask().isEmpty()) {
            sb.append(DIM).append("  任务: ").append(plan.getOriginalTask()).append(RESET).append("\n");
        }
        sb.append("\n");
        for (PlanStep step : plan.getSteps()) {
            sb.append("  ").append(step.getStatus().icon())
              .append(" [").append(step.getId()).append("] ")
              .append(step.getDescription()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 渲染执行完成后的计划摘要（含每步结果）。
     */
    public static String renderSummary(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(CYAN).append(BOLD).append("  执行摘要: ").append(plan.getTitle()).append(RESET).append("\n");
        sb.append(DIM).append(String.format("  完成: %d/%d\n", plan.completedCount(), plan.totalCount())).append(RESET);
        sb.append("\n");
        for (PlanStep step : plan.getSteps()) {
            String color = statusColor(step.getStatus());
            sb.append(color).append("  ").append(step.getStatus().icon())
              .append(" [").append(step.getId()).append("] ")
              .append(step.getDescription()).append(RESET).append("\n");
            if (step.getResult() != null && !step.getResult().isEmpty()) {
                String preview = step.getResult().length() > 120
                        ? step.getResult().substring(0, 120) + "..."
                        : step.getResult();
                sb.append(DIM).append("      ").append(preview).append(RESET).append("\n");
            }
            if (step.getError() != null) {
                sb.append(DIM).append("      错误: ").append(step.getError()).append(RESET).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 根据状态返回对应的 ANSI 颜色。
     */
    private static String statusColor(PlanStep.Status status) {
        switch (status) {
            case COMPLETE:    return GREEN;
            case IN_PROGRESS: return YELLOW;
            case FAILED:      return RED;
            case CANCELLED:   return RED;
            default:          return "";
        }
    }
}
