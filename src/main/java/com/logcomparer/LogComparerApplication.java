package com.logcomparer;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@SpringBootApplication
public class LogComparerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LogComparerApplication.class, args);
    }
}

// ───── Models ─────

class LogEntry {
    private String timestamp, level, component, message, normalizedMessage;
    private int lineNumber;

    public LogEntry(int lineNumber) {
        this.lineNumber = lineNumber;
        this.level = "UNKNOWN";
        this.component = this.message = this.timestamp = "";
    }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String v) { timestamp = v; }
    public String getLevel() { return level; }
    public void setLevel(String v) { level = v; }
    public String getComponent() { return component; }
    public void setComponent(String v) { component = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public String getNormalizedMessage() { return normalizedMessage; }
    public void setNormalizedMessage(String v) { normalizedMessage = v; }
    public int getLineNumber() { return lineNumber; }
    public boolean isError() { return "ERROR".equalsIgnoreCase(level); }
    public boolean isWarn() { return "WARN".equalsIgnoreCase(level) || "WARNING".equalsIgnoreCase(level); }
}

class LogReport {
    private String fileName;
    private int totalEntries, errorCount, warnCount;
    private List<LogEntry> entries;

    public LogReport(String fileName, List<LogEntry> entries) {
        this.fileName = fileName;
        this.entries = entries;
        this.totalEntries = entries.size();
        this.errorCount = (int) entries.stream().filter(LogEntry::isError).count();
        this.warnCount = (int) entries.stream().filter(LogEntry::isWarn).count();
    }

    public String getFileName() { return fileName; }
    public int getTotalEntries() { return totalEntries; }
    public int getErrorCount() { return errorCount; }
    public int getWarnCount() { return warnCount; }
    public List<LogEntry> getEntries() { return entries; }
}

class AnalysisEntry {
    private String category;
    private int countInCorrect;
    private int countInSuspect;
    private String verdict;

    public AnalysisEntry(String category, int countInCorrect, int countInSuspect) {
        this.category = category;
        this.countInCorrect = countInCorrect;
        this.countInSuspect = countInSuspect;
        int diff = Math.abs(countInCorrect - countInSuspect);
        if (countInCorrect > countInSuspect)
            this.verdict = "Occurs " + diff + " more time(s) in Correct — may be missing/skipped in Suspect";
        else
            this.verdict = "Occurs " + diff + " more time(s) in Suspect — unexpected extra operation";
    }

    public String getCategory() { return category; }
    public int getCountInCorrect() { return countInCorrect; }
    public int getCountInSuspect() { return countInSuspect; }
    public String getVerdict() { return verdict; }
}

class ComparisonResult {
    private LogReport logAReport, logBReport;
    private List<LogEntry> extraInA = new ArrayList<>();
    private List<LogEntry> extraInB = new ArrayList<>();
    private List<AnalysisEntry> analysis = new ArrayList<>();

    public ComparisonResult(LogReport a, LogReport b) { logAReport = a; logBReport = b; }
    public LogReport getLogAReport() { return logAReport; }
    public LogReport getLogBReport() { return logBReport; }
    public List<LogEntry> getExtraInA() { return extraInA; }
    public List<LogEntry> getExtraInB() { return extraInB; }
    public List<AnalysisEntry> getAnalysis() { return analysis; }
    public int getTotalDifferences() { return extraInA.size() + extraInB.size(); }
    public int getCommonCount() { return logAReport.getEntries().size() - extraInA.size(); }
}

// ───── Services ─────

@Service
class CsvParserService {
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_PATTERN = Pattern.compile("0x[0-9a-fA-F]+");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d{2,}\\b");
    private static final Pattern BRACKET_PATTERN = Pattern.compile("\\[[^\\]]+\\]");

    public List<LogEntry> parse(MultipartFile file) throws IOException {
        return parseContent(new String(file.getBytes(), StandardCharsets.UTF_8), file.getOriginalFilename());
    }

    public List<LogEntry> parseContent(String content, String fileName) throws IOException {
        List<LogEntry> entries = new ArrayList<>();
        try (Reader reader = new StringReader(content);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                 .setTrim(true).setIgnoreEmptyLines(true).setAllowMissingColumnNames(true).build().parse(reader)) {

            Map<String, Integer> header = parser.getHeaderMap();
            if (header == null || header.isEmpty())
                throw new IOException("No valid header in " + fileName);

            int timeIdx = find(header, "_time", "timestamp", "time", "date", "datetime");
            int levelIdx = find(header, "level", "log_level", "severity", "loglevel");
            int compIdx = find(header, "component", "class", "logger", "source");
            int msgIdx = find(header, "field16", "message", "_raw", "log", "text", "event", "description", "msg", "detail", "reason");

            int lineNum = 0;
            for (CSVRecord r : parser) {
                lineNum++;
                LogEntry e = new LogEntry(lineNum);
                if (timeIdx >= 0) e.setTimestamp(r.get(timeIdx));
                if (levelIdx >= 0) e.setLevel(normalizeLevel(r.get(levelIdx)));
                if (compIdx >= 0) e.setComponent(extractBracket(r.get(compIdx)));
                if (msgIdx >= 0) {
                    e.setMessage(r.get(msgIdx).trim());
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < r.size(); i++)
                        if (i != timeIdx && i != levelIdx && i != compIdx) sb.append(r.get(i)).append(" ");
                    e.setMessage(sb.toString().trim());
                }
                e.setNormalizedMessage(normalize(e.getMessage()));
                entries.add(e);
            }
        }
        return entries;
    }

    private int find(Map<String, Integer> map, String... candidates) {
        Map<String, Integer> lower = new LinkedHashMap<>();
        map.forEach((k, v) -> lower.put(k.toLowerCase().trim(), v));
        for (String c : candidates) {
            Integer v = lower.get(c.toLowerCase());
            if (v != null) return v;
        }
        return -1;
    }

    private String normalizeLevel(String raw) {
        if (raw == null) return "UNKNOWN";
        String u = raw.toUpperCase().trim();
        if (u.contains("ERROR") || u.contains("ERR")) return "ERROR";
        if (u.contains("WARN")) return "WARN";
        if (u.contains("INFO")) return "INFO";
        if (u.contains("DEBUG") || u.contains("TRACE")) return "DEBUG";
        if (u.contains("FATAL")) return "FATAL";
        return u;
    }

    private String extractBracket(String raw) {
        if (raw == null) return "";
        var m = BRACKET_PATTERN.matcher(raw);
        if (m.find()) { String g = m.group(); return g.substring(1, g.length() - 1).trim(); }
        String[] p = raw.split("\\s+");
        return p.length > 0 ? p[0] : raw.trim();
    }

    String normalize(String msg) {
        if (msg == null) return "";
        String s = msg;
        s = UUID_PATTERN.matcher(s).replaceAll("{uuid}");
        s = HEX_PATTERN.matcher(s).replaceAll("{hex}");
        s = NUMBER_PATTERN.matcher(s).replaceAll("{n}");
        return s.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}

@Service
class LogComparisonService {

    public ComparisonResult compare(List<LogEntry> a, List<LogEntry> b, String na, String nb) {
        LogReport ra = new LogReport(na, a), rb = new LogReport(nb, b);
        ComparisonResult r = new ComparisonResult(ra, rb);

        Set<String> normA = new HashSet<>();
        Set<String> normB = new HashSet<>();

        for (LogEntry e : a) {
            String n = e.getNormalizedMessage();
            if (n != null && !n.isEmpty()) normA.add(n);
        }
        for (LogEntry e : b) {
            String n = e.getNormalizedMessage();
            if (n != null && !n.isEmpty()) normB.add(n);
        }

        Set<String> uniqueA = new LinkedHashSet<>(normA);
        uniqueA.removeAll(normB);

        Set<String> uniqueB = new LinkedHashSet<>(normB);
        uniqueB.removeAll(normA);

        for (LogEntry e : a) {
            String n = e.getNormalizedMessage();
            if (n != null && uniqueA.contains(n)) {
                r.getExtraInA().add(e);
                uniqueA.remove(n);
            }
        }

        for (LogEntry e : b) {
            String n = e.getNormalizedMessage();
            if (n != null && uniqueB.contains(n)) {
                r.getExtraInB().add(e);
                uniqueB.remove(n);
            }
        }

        // ───── Analysis: group ALL entries (incl duplicates) by keyword pattern ─────
        Map<String, int[]> catCounts = new LinkedHashMap<>();

        for (LogEntry e : a) {
            String cat = extractKeyPattern(e.getMessage());
            catCounts.computeIfAbsent(cat, k -> new int[2])[0]++;
        }
        for (LogEntry e : b) {
            String cat = extractKeyPattern(e.getMessage());
            catCounts.computeIfAbsent(cat, k -> new int[2])[1]++;
        }

        for (Map.Entry<String, int[]> e : catCounts.entrySet()) {
            int cA = e.getValue()[0], cB = e.getValue()[1];
            if (cA != cB) {
                r.getAnalysis().add(new AnalysisEntry(e.getKey(), cA, cB));
            }
        }

        return r;
    }

    private String extractKeyPattern(String msg) {
        if (msg == null || msg.isEmpty()) return "(empty)";
        String s = msg.toLowerCase().trim();
        // Remove leading metadata like date, log level, thread info
        s = s.replaceAll("^\\d{4}-\\d{2}-\\d{2}\\s+\\S+\\s+", "");
        s = s.replaceAll("^\\[\\S+\\]\\s*", "");
        s = s.replaceAll("^(info|warn|error|debug|trace)\\s*[:\\-]?\\s*", "");
        // Extract the first meaningful action phrase (first 3-5 meaningful words)
        s = s.replaceAll("[\\[\\](){}]", " ").trim();
        String[] words = s.split("\\s+");
        int take = Math.min(6, words.length);
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < take; i++) {
            if (i > 0) key.append(" ");
            key.append(words[i]);
        }
        return key.toString();
    }
}

// ───── Controller ─────

@Controller
class LogController {
    private final CsvParserService parser;
    private final LogComparisonService comparer;

    public LogController(CsvParserService parser, LogComparisonService comparer) {
        this.parser = parser; this.comparer = comparer;
    }

    @GetMapping("/")
    public String index() { return "index"; }

    @PostMapping("/compare")
    public String compare(@RequestParam("fileA") MultipartFile fileA,
                          @RequestParam("fileB") MultipartFile fileB, Model model) {
        try {
            if (fileA.isEmpty() || fileB.isEmpty()) {
                model.addAttribute("error", "Upload both CSV files");
                return "index";
            }
            var logA = parser.parse(fileA);
            var logB = parser.parse(fileB);
            ComparisonResult result = comparer.compare(logA, logB,
                fileA.getOriginalFilename(), fileB.getOriginalFilename());
            model.addAttribute("result", result);
            return "result";
        } catch (Exception e) {
            model.addAttribute("error", "Error: " + e.getMessage());
            return "index";
        }
    }
}
