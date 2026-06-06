package com.logcomparer.model;

public class DiffEntry {
    public enum Type {
        MISSING_OPERATION,
        EXTRA_OPERATION,
        COUNT_MISMATCH,
        ERROR_MISMATCH,
        RETRY_MISMATCH,
        SEQUENCE_DIFFERENCE,
        TIMING_DIFFERENCE
    }

    private Type type;
    private String summary;
    private String logADetail;
    private String logBDetail;
    private Severity severity;

    public enum Severity { HIGH, MEDIUM, LOW }

    public DiffEntry(Type type, String summary, String logADetail, String logBDetail, Severity severity) {
        this.type = type;
        this.summary = summary;
        this.logADetail = logADetail;
        this.logBDetail = logBDetail;
        this.severity = severity;
    }

    public Type getType() { return type; }
    public String getSummary() { return summary; }
    public String getLogADetail() { return logADetail; }
    public String getLogBDetail() { return logBDetail; }
    public Severity getSeverity() { return severity; }
}
