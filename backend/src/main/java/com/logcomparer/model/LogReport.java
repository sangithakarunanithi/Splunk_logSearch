package com.logcomparer.model;

import java.util.*;
import java.util.stream.Collectors;

public class LogReport {
    private String fileName;
    private int totalEntries;
    private int errorCount;
    private int warnCount;
    private int retryCount;
    private List<LogEntry> entries;
    private Map<String, Integer> categoryCounts;

    public LogReport(String fileName, List<LogEntry> entries) {
        this.fileName = fileName;
        this.entries = entries;
        this.totalEntries = entries.size();
        this.errorCount = (int) entries.stream().filter(LogEntry::isError).count();
        this.warnCount = (int) entries.stream().filter(LogEntry::isWarn).count();
        this.retryCount = (int) entries.stream().filter(LogEntry::isRetry).count();
        this.categoryCounts = new LinkedHashMap<>();
    }

    public String getFileName() { return fileName; }
    public int getTotalEntries() { return totalEntries; }
    public int getErrorCount() { return errorCount; }
    public int getWarnCount() { return warnCount; }
    public int getRetryCount() { return retryCount; }
    public List<LogEntry> getEntries() { return entries; }
    public Map<String, Integer> getCategoryCounts() { return categoryCounts; }
    public void setCategoryCounts(Map<String, Integer> categoryCounts) { this.categoryCounts = categoryCounts; }
}
