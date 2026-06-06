package com.logcomparer.service;

import com.logcomparer.model.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class LogComparisonService {

    private static final Pattern OPERATION_KEYWORDS =
        Pattern.compile("\\b(start|begin|end|complete|finish|update|create|delete|policy|trigger" +
            "|execute|process|validate|check|sync|push|pull|deploy|rollback|revert|apply|save)\\b",
            Pattern.CASE_INSENSITIVE);

    public ComparisonResult compare(List<LogEntry> logA, List<LogEntry> logB, String nameA, String nameB) {
        LogReport reportA = new LogReport(nameA, logA);
        LogReport reportB = new LogReport(nameB, logB);

        buildCategoryCounts(reportA);
        buildCategoryCounts(reportB);

        ComparisonResult result = new ComparisonResult(reportA, reportB);

        compareSummaryStats(result);
        compareCategories(result);
        findMissingOperations(result);
        findExtraOperations(result);
        compareErrorPatterns(result);
        compareRetryPatterns(result);
        compareSequence(result);

        return result;
    }

    private void buildCategoryCounts(LogReport report) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LogEntry entry : report.getEntries()) {
            String cat = categorize(entry);
            counts.put(cat, counts.getOrDefault(cat, 0) + 1);
        }
        report.setCategoryCounts(counts);
    }

    String categorize(LogEntry entry) {
        String msg = entry.getMessage().toLowerCase();
        String norm = entry.getNormalizedMessage().toLowerCase();

        if (entry.isError()) {
            if (msg.contains("timeout")) return "Error: Timeout";
            if (msg.contains("connection")) return "Error: Connection";
            if (msg.contains("null") || msg.contains("npe")) return "Error: NullPointer";
            if (msg.contains("permission") || msg.contains("denied") || msg.contains("forbidden"))
                return "Error: Permission";
            if (msg.contains("not found") || msg.contains("missing")) return "Error: NotFound";
            if (msg.contains("invalid") || msg.contains("bad")) return "Error: InvalidInput";
            if (msg.contains("already")) return "Error: Duplicate";
            return "Error: Other";
        }

        if (entry.isRetry()) return "Retry";

        if (norm.contains("start") || norm.contains("begin")) return "Operation: Start";
        if (norm.contains("complete") || norm.contains("finish") || norm.contains("end") || norm.contains("success"))
            return "Operation: Complete";
        if (norm.contains("fail") || norm.contains("abort") || norm.contains("cancel"))
            return "Operation: Failed";
        if (norm.contains("update") || norm.contains("modify") || norm.contains("change"))
            return "Operation: Update";
        if (norm.contains("create") || norm.contains("add") || norm.contains("insert"))
            return "Operation: Create";
        if (norm.contains("delete") || norm.contains("remove") || norm.contains("purge"))
            return "Operation: Delete";
        if (norm.contains("validate") || norm.contains("check") || norm.contains("verify"))
            return "Operation: Validation";
        if (norm.contains("retry") || norm.contains("attempt")) return "Retry";
        if (norm.contains("policy")) return "Operation: Policy";
        if (norm.contains("deploy") || norm.contains("push") || norm.contains("rollout"))
            return "Operation: Deploy";
        if (norm.contains("sync") || norm.contains("replicate") || norm.contains("backup"))
            return "Operation: Sync";
        if (norm.contains("fetch") || norm.contains("load") || norm.contains("get") || norm.contains("query"))
            return "Operation: Fetch";
        if (norm.contains("config") || norm.contains("setting") || norm.contains("param"))
            return "Operation: Configuration";

        return "Other";
    }

    private void compareSummaryStats(ComparisonResult result) {
        LogReport a = result.getLogAReport();
        LogReport b = result.getLogBReport();

        if (a.getErrorCount() != b.getErrorCount()) {
            String diff = a.getErrorCount() > b.getErrorCount()
                ? a.getFileName() + " has " + (a.getErrorCount() - b.getErrorCount()) + " more errors"
                : b.getFileName() + " has " + (b.getErrorCount() - a.getErrorCount()) + " more errors";
            result.addDifference(new DiffEntry(
                DiffEntry.Type.ERROR_MISMATCH,
                "Error count differs",
                a.getFileName() + ": " + a.getErrorCount() + " errors",
                b.getFileName() + ": " + b.getErrorCount() + " errors",
                DiffEntry.Severity.HIGH));
        }

        if (a.getRetryCount() != b.getRetryCount()) {
            result.addDifference(new DiffEntry(
                DiffEntry.Type.RETRY_MISMATCH,
                "Retry/attempt count differs",
                a.getFileName() + ": " + a.getRetryCount() + " retries",
                b.getFileName() + ": " + b.getRetryCount() + " retries",
                DiffEntry.Severity.HIGH));
        }

        if (Math.abs(a.getTotalEntries() - b.getTotalEntries()) > 5) {
            result.addDifference(new DiffEntry(
                DiffEntry.Type.COUNT_MISMATCH,
                "Total log volume differs significantly",
                a.getFileName() + ": " + a.getTotalEntries() + " entries",
                b.getFileName() + ": " + b.getTotalEntries() + " entries",
                DiffEntry.Severity.MEDIUM));
        }
    }

    private void compareCategories(ComparisonResult result) {
        LogReport a = result.getLogAReport();
        LogReport b = result.getLogBReport();
        Map<String, Integer> catsA = a.getCategoryCounts();
        Map<String, Integer> catsB = b.getCategoryCounts();

        Set<String> allCats = new LinkedHashSet<>(catsA.keySet());
        allCats.addAll(catsB.keySet());

        for (String cat : allCats) {
            int countA = catsA.getOrDefault(cat, 0);
            int countB = catsB.getOrDefault(cat, 0);
            if (countA != countB) {
                result.addDifference(new DiffEntry(
                    DiffEntry.Type.COUNT_MISMATCH,
                    "Category \"" + cat + "\" count differs",
                    a.getFileName() + ": " + countA + "x",
                    b.getFileName() + ": " + countB + "x",
                    cat.startsWith("Error") ? DiffEntry.Severity.HIGH : DiffEntry.Severity.MEDIUM));
            }
        }
    }

    private void findMissingOperations(ComparisonResult result) {
        findMissingInTarget(result, result.getLogAReport(), result.getLogBReport(), true);
    }

    private void findExtraOperations(ComparisonResult result) {
        findMissingInTarget(result, result.getLogBReport(), result.getLogAReport(), false);
    }

    private void findMissingInTarget(ComparisonResult result, LogReport source, LogReport target, boolean missingInB) {
        Map<String, Long> sourceCounts = countNormalized(source.getEntries());
        Map<String, Long> targetCounts = countNormalized(target.getEntries());

        List<Map.Entry<String, Long>> onlyInSource = sourceCounts.entrySet().stream()
            .filter(e -> !targetCounts.containsKey(e.getKey()))
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(20)
            .toList();

        for (var entry : onlyInSource) {
            String sample = findSampleMessage(source.getEntries(), entry.getKey());
            String label = missingInB ? "Present in " + source.getFileName() + " but missing in " + target.getFileName()
                                      : "Present in " + target.getFileName() + " but missing in " + source.getFileName();
            result.addDifference(new DiffEntry(
                missingInB ? DiffEntry.Type.MISSING_OPERATION : DiffEntry.Type.EXTRA_OPERATION,
                label,
                source.getFileName() + ": " + entry.getValue() + "x " + sample,
                target.getFileName() + ": 0x",
                DiffEntry.Severity.MEDIUM));
        }
    }

    private void compareErrorPatterns(ComparisonResult result) {
        List<LogEntry> errorsA = result.getLogAReport().getEntries().stream()
            .filter(LogEntry::isError).toList();
        List<LogEntry> errorsB = result.getLogBReport().getEntries().stream()
            .filter(LogEntry::isError).toList();

        Map<String, Long> errorCatsA = errorsA.stream()
            .collect(Collectors.groupingBy(e -> categorize(e), Collectors.counting()));
        Map<String, Long> errorCatsB = errorsB.stream()
            .collect(Collectors.groupingBy(e -> categorize(e), Collectors.counting()));

        Set<String> allErrorCats = new LinkedHashSet<>(errorCatsA.keySet());
        allErrorCats.addAll(errorCatsB.keySet());

        for (String cat : allErrorCats) {
            long countA = errorCatsA.getOrDefault(cat, 0L);
            long countB = errorCatsB.getOrDefault(cat, 0L);
            if (countA != countB) {
                result.addDifference(new DiffEntry(
                    DiffEntry.Type.ERROR_MISMATCH,
                    "Error pattern \"" + cat + "\" count differs",
                    result.getLogAReport().getFileName() + ": " + countA + "x",
                    result.getLogBReport().getFileName() + ": " + countB + "x",
                    DiffEntry.Severity.HIGH));
            }
        }
    }

    private void compareRetryPatterns(ComparisonResult result) {
        long retriesA = result.getLogAReport().getEntries().stream().filter(LogEntry::isRetry).count();
        long retriesB = result.getLogBReport().getEntries().stream().filter(LogEntry::isRetry).count();

        if (retriesA != retriesB) {
            String more = retriesA > retriesB
                ? result.getLogAReport().getFileName() + " has more retries"
                : result.getLogBReport().getFileName() + " has more retries";
            result.addDifference(new DiffEntry(
                DiffEntry.Type.RETRY_MISMATCH,
                "Retry count differs: " + more,
                result.getLogAReport().getFileName() + ": " + retriesA + " retries",
                result.getLogBReport().getFileName() + ": " + retriesB + " retries",
                DiffEntry.Severity.HIGH));
        }
    }

    private void compareSequence(ComparisonResult result) {
        List<String> seqA = result.getLogAReport().getEntries().stream()
            .map(e -> categorize(e))
            .toList();
        List<String> seqB = result.getLogBReport().getEntries().stream()
            .map(e -> categorize(e))
            .toList();

        int posA = 0, posB = 0;
        int diffCount = 0;
        while (posA < seqA.size() && posB < seqB.size() && diffCount < 10) {
            if (!seqA.get(posA).equals(seqB.get(posB))) {
                result.addDifference(new DiffEntry(
                    DiffEntry.Type.SEQUENCE_DIFFERENCE,
                    "Sequence mismatch at position " + (posA + 1) + " vs " + (posB + 1),
                    result.getLogAReport().getFileName() + ": \"" + seqA.get(posA) + "\"",
                    result.getLogBReport().getFileName() + ": \"" + seqB.get(posB) + "\"",
                    DiffEntry.Severity.LOW));
                diffCount++;
                posA++;
                posB++;
            } else {
                posA++;
                posB++;
            }
        }
    }

    private Map<String, Long> countNormalized(List<LogEntry> entries) {
        return entries.stream()
            .map(LogEntry::getNormalizedMessage)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private String findSampleMessage(List<LogEntry> entries, String normalized) {
        return entries.stream()
            .filter(e -> normalized.equals(e.getNormalizedMessage()))
            .map(LogEntry::getMessage)
            .filter(m -> m != null && !m.isEmpty())
            .findFirst()
            .orElse("");
    }
}
