package com.logcomparer.model;

import java.util.*;

public class ComparisonResult {
    private LogReport logAReport;
    private LogReport logBReport;
    private List<DiffEntry> differences;

    public ComparisonResult(LogReport logAReport, LogReport logBReport) {
        this.logAReport = logAReport;
        this.logBReport = logBReport;
        this.differences = new ArrayList<>();
    }

    public LogReport getLogAReport() { return logAReport; }
    public LogReport getLogBReport() { return logBReport; }
    public List<DiffEntry> getDifferences() { return differences; }
    public void addDifference(DiffEntry diff) { differences.add(diff); }

    public long getHighSeverityCount() {
        return differences.stream().filter(d -> d.getSeverity() == DiffEntry.Severity.HIGH).count();
    }

    public long getMediumSeverityCount() {
        return differences.stream().filter(d -> d.getSeverity() == DiffEntry.Severity.MEDIUM).count();
    }

    public long getLowSeverityCount() {
        return differences.stream().filter(d -> d.getSeverity() == DiffEntry.Severity.LOW).count();
    }
}
