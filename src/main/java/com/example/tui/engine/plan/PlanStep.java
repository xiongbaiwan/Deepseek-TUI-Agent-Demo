package com.example.tui.engine.plan;

/**
 * 计划中的单个步骤。
 */
public class PlanStep {

    private int id;
    private String description;
    private Status status = Status.PENDING;
    private String result;
    private String error;

    public enum Status {
        PENDING, IN_PROGRESS, COMPLETE, FAILED, SKIPPED, CANCELLED;

        public String icon() {
            switch (this) {
                case PENDING:     return "[ ]";
                case IN_PROGRESS: return "[~]";
                case COMPLETE:    return "[x]";
                case FAILED:      return "[!]";
                case SKIPPED:     return "[-]";
                case CANCELLED:   return "[/]";
                default:          return "[?]";
            }
        }

        public String label() {
            return name().toLowerCase();
        }
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    @Override
    public String toString() {
        return String.format("Step %d %s %s", id, status.icon(), description);
    }
}
