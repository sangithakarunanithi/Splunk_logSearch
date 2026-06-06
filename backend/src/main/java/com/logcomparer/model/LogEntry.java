package com.logcomparer.model;

public class LogEntry {
    private String timestamp;
    private String level;
    private String component;
    private String message;
    private String normalizedMessage;
    private int lineNumber;

    public LogEntry(int lineNumber) {
        this.lineNumber = lineNumber;
        this.level = "UNKNOWN";
        this.component = "";
        this.message = "";
        this.timestamp = "";
    }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getComponent() { return component; }
    public void setComponent(String component) { this.component = component; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getNormalizedMessage() { return normalizedMessage; }
    public void setNormalizedMessage(String normalizedMessage) { this.normalizedMessage = normalizedMessage; }

    public int getLineNumber() { return lineNumber; }

    public boolean isError() { return "ERROR".equalsIgnoreCase(level); }
    public boolean isWarn() { return "WARN".equalsIgnoreCase(level) || "WARNING".equalsIgnoreCase(level); }
    public boolean isRetry() {
        return message != null &&
               (message.toLowerCase().contains("retry") ||
                message.toLowerCase().contains("attempt") ||
                message.toLowerCase().contains("reconnect"));
    }
}
