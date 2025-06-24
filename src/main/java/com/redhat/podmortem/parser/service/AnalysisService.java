package com.redhat.podmortem.service;

import com.redhat.podmortem.common.model.analysis.AnalysisMetadata;
import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.analysis.AnalysisSummary;
import com.redhat.podmortem.common.model.analysis.EventContext;
import com.redhat.podmortem.common.model.analysis.MatchedEvent;
import com.redhat.podmortem.common.model.kube.podmortem.PodFailureData;
import com.redhat.podmortem.common.model.pattern.ContextExtraction;
import com.redhat.podmortem.common.model.pattern.Pattern;
import com.redhat.podmortem.common.model.pattern.PatternSet;
import com.redhat.podmortem.common.model.pattern.SecondaryPattern;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    @Inject PatternService patternService;

    @Inject ScoringService scoringService;

    /**
     * Analyzes the provided pod failure data against the loaded pattern sets.
     *
     * @param data The collected data from a failed pod, including logs.
     * @return An {@link AnalysisResult} object containing all findings.
     */
    public AnalysisResult analyze(PodFailureData data) {
        long startTime = System.currentTimeMillis();
        List<MatchedEvent> foundEvents = new ArrayList<>();
        String[] logLines = data.getLogs().split("\\r?\\n");

        // pre-compile all regex patterns
        for (PatternSet patternSet : patternService.getPatternSets()) {
            if (patternSet.getPatterns() == null) {
                continue;
            }
            for (Pattern pattern : patternSet.getPatterns()) {
                // compile primary pattern
                pattern.getPrimaryPattern()
                        .setCompiledRegex(
                                java.util.regex.Pattern.compile(
                                        pattern.getPrimaryPattern().getRegex()));

                // compile secondary patterns
                if (pattern.getSecondaryPatterns() != null) {
                    for (SecondaryPattern sp : pattern.getSecondaryPatterns()) {
                        sp.setCompiledRegex(java.util.regex.Pattern.compile(sp.getRegex()));
                    }
                }
            }
        }

        // look for matches in the logs
        for (int logLine = 0; logLine < logLines.length; logLine++) {
            String line = logLines[logLine];
            for (var patternSet : patternService.getPatternSets()) {
                for (var pattern : patternSet.getPatterns()) {
                    Matcher matcher = pattern.getPrimaryPattern().getCompiledRegex().matcher(line);

                    if (matcher.find()) {
                        log.info(
                                "Line {}: Found match for pattern '{}'",
                                logLine + 1,
                                pattern.getName());
                        MatchedEvent event = new MatchedEvent();
                        event.setLineNumber(logLine + 1);
                        event.setMatchedPattern(pattern);
                        event.setContext(
                                extractContext(logLines, logLine, pattern.getContextExtraction()));

                        double score = scoringService.calculateScore(event, logLines);
                        event.setScore(score);

                        foundEvents.add(event);
                    }
                }
            }
        }

        AnalysisResult result = new AnalysisResult();
        result.setEvents(foundEvents);
        result.setAnalysisId(UUID.randomUUID().toString());
        result.setMetadata(buildMetadata(startTime, logLines, patternService.getPatternSets()));
        result.setSummary(buildSummary(foundEvents));

        return result;
    }

    /**
     * Extracts the surrounding log lines based on the pattern's extraction rules.
     *
     * @param allLines The complete array of log lines.
     * @param matchIndex The index of the line where the primary pattern matched.
     * @param rules The context extraction rules from the matched pattern.
     * @return An {@link EventContext} object populated with the relevant lines.
     */
    private EventContext extractContext(
            String[] allLines, int matchIndex, ContextExtraction rules) {
        EventContext context = new EventContext();
        context.setMatchedLine(allLines[matchIndex]);

        if (rules == null) {
            return context;
        }

        // get lines before the match
        int beforeStart = Math.max(0, matchIndex - rules.getLinesBefore());
        List<String> beforeLines =
                Arrays.asList(Arrays.copyOfRange(allLines, beforeStart, matchIndex));
        context.setLinesBefore(beforeLines);

        // get lines after the match
        int afterEnd = Math.min(allLines.length, matchIndex + 1 + rules.getLinesAfter());
        List<String> afterLines =
                Arrays.asList(Arrays.copyOfRange(allLines, matchIndex + 1, afterEnd));
        context.setLinesAfter(afterLines);

        // TODO: Implement stack trace detection logic based on rules.getIncludeStackTrace()

        return context;
    }

    /**
     * Builds the metadata object for the analysis result.
     *
     * @param startTime The timestamp when the analysis began.
     * @param logLines The complete array of log lines.
     * @param patternSets The list of pattern sets used in the analysis.
     * @return A populated {@link AnalysisMetadata} object.
     */
    private AnalysisMetadata buildMetadata(
            long startTime, String[] logLines, List<PatternSet> patternSets) {
        AnalysisMetadata metadata = new AnalysisMetadata();
        metadata.setProcessingTimeMs(System.currentTimeMillis() - startTime);
        metadata.setTotalLines(logLines.length);
        metadata.setAnalyzedAt(Instant.now().toString());

        List<String> patternsUsed =
                patternSets.stream()
                        .map(ps -> ps.getMetadata().getLibraryId())
                        .collect(Collectors.toList());
        metadata.setPatternsUsed(patternsUsed);

        return metadata;
    }

    /**
     * Builds the summary object for the analysis result.
     *
     * @param events The list of all events found during the analysis.
     * @return A populated {@link AnalysisSummary} object.
     */
    private AnalysisSummary buildSummary(List<MatchedEvent> events) {
        AnalysisSummary summary = new AnalysisSummary();
        summary.setSignificantEvents(events.size());

        if (events.isEmpty()) {
            summary.setHighestSeverity("NONE");
            summary.setSeverityDistribution(Map.of());
            return summary;
        }

        // calculate severity distribution
        Map<String, Long> distribution =
                events.stream()
                        .map(e -> e.getMatchedPattern().getSeverity().toUpperCase())
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        summary.setSeverityDistribution(distribution);

        // determine highest severity
        List<String> severityOrder = List.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
        String highestSeverity =
                events.stream()
                        .map(e -> e.getMatchedPattern().getSeverity().toUpperCase())
                        .max(Comparator.comparingInt(severityOrder::indexOf))
                        .orElse("NONE");
        summary.setHighestSeverity(highestSeverity);

        return summary;
    }
}
