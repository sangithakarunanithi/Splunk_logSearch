package com.logcomparer.service;

import com.logcomparer.model.LogEntry;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class CsvParserService {

    private static final Pattern UUID_PATTERN =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_PATTERN =
        Pattern.compile("0x[0-9a-fA-F]+");
    private static final Pattern NUMBER_PATTERN =
        Pattern.compile("\\b\\d{2,}\\b");
    private static final Pattern BRACKET_TAG_PATTERN =
        Pattern.compile("\\[[^\\]]+\\]");

    public List<LogEntry> parse(MultipartFile file) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return parseContent(content, file.getOriginalFilename());
    }

    public List<LogEntry> parseContent(String content, String fileName) throws IOException {
        List<LogEntry> entries = new ArrayList<>();

        try (Reader reader = new StringReader(content);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                 .setHeader()
                 .setSkipHeaderRecord(true)
                 .setTrim(true)
                 .setIgnoreEmptyLines(true)
                 .setAllowMissingColumnNames(true)
                 .build()
                 .parse(reader)) {

            Map<String, Integer> headerMap = parser.getHeaderMap();
            if (headerMap == null || headerMap.isEmpty()) {
                throw new IOException("CSV file has no valid header row: " + fileName);
            }

            int timeIdx = findColumn(headerMap, "_time", "timestamp", "time", "date", "datetime", "_time_");
            int levelIdx = findColumn(headerMap, "level", "log_level", "severity", "loglevel", "log-level");
            int componentIdx = findColumn(headerMap, "component", "class", "logger", "source", "loggername");
            int msgIdx = findColumn(headerMap, "message", "_raw", "log", "text", "event", "description",
                                     "msg", "detail", "reason", "summary");

            if (msgIdx == -1) {
                msgIdx = findColumn(headerMap, headerMap.keySet().toArray(new String[0]));
            }

            int lineNum = 0;
            for (CSVRecord record : parser) {
                lineNum++;
                LogEntry entry = new LogEntry(lineNum);

                if (timeIdx >= 0) entry.setTimestamp(record.get(timeIdx));
                if (levelIdx >= 0) entry.setLevel(normalizeLevel(record.get(levelIdx)));
                if (componentIdx >= 0) entry.setComponent(extractBracketContent(record.get(componentIdx)));

                if (msgIdx >= 0) {
                    entry.setMessage(record.get(msgIdx).trim());
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < record.size(); i++) {
                        if (i != timeIdx && i != levelIdx && i != componentIdx) {
                            sb.append(record.get(i)).append(" ");
                        }
                    }
                    entry.setMessage(sb.toString().trim());
                }

                entry.setNormalizedMessage(normalizeMessage(entry.getMessage()));
                entries.add(entry);
            }
        }
        return entries;
    }

    private int findColumn(Map<String, Integer> headerMap, String... candidates) {
        Map<String, Integer> lowerMap = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : headerMap.entrySet()) {
            lowerMap.put(e.getKey().toLowerCase().trim(), e.getValue());
        }
        for (String candidate : candidates) {
            String key = candidate.toLowerCase();
            if (lowerMap.containsKey(key)) {
                return lowerMap.get(key);
            }
        }
        return -1;
    }

    private String normalizeLevel(String raw) {
        if (raw == null) return "UNKNOWN";
        String up = raw.toUpperCase().trim();
        if (up.contains("ERROR") || up.contains("ERR")) return "ERROR";
        if (up.contains("WARN")) return "WARN";
        if (up.contains("INFO")) return "INFO";
        if (up.contains("DEBUG") || up.contains("TRACE")) return "DEBUG";
        if (up.contains("FATAL")) return "FATAL";
        return up;
    }

    private String extractBracketContent(String raw) {
        if (raw == null) return "";
        var m = BRACKET_TAG_PATTERN.matcher(raw);
        if (m.find()) {
            String content = m.group();
            return content.substring(1, content.length() - 1).trim();
        }
        String[] parts = raw.split("\\s+");
        return parts.length > 0 ? parts[0] : raw.trim();
    }

    String normalizeMessage(String message) {
        if (message == null) return "";
        String s = message;
        s = UUID_PATTERN.matcher(s).replaceAll("{uuid}");
        s = HEX_PATTERN.matcher(s).replaceAll("{hex}");
        s = NUMBER_PATTERN.matcher(s).replaceAll("{n}");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }
}
