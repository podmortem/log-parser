package com.redhat.podmortem.parser.service;

import com.redhat.podmortem.common.model.analysis.EventContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for analyzing the contextual information surrounding matched failure patterns.
 *
 * <p>Evaluates the lines before and after a pattern match to determine the severity and relevance
 * of the context. Uses regex patterns to identify error levels, stack traces, exceptions, and other
 * contextual indicators that affect the confidence score of a matched event.
 */
@ApplicationScoped
public class ContextAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ContextAnalysisService.class);

    @ConfigProperty(name = "scoring.context.max-context-factor", defaultValue = "2.5")
    double maxContextFactor;

    private static final Pattern ERROR_PATTERN =
            Pattern.compile("\\b(ERROR|FATAL|CRITICAL|SEVERE)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WARN_PATTERN =
            Pattern.compile("\\b(WARN|WARNING)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STACK_TRACE_PATTERN =
            Pattern.compile("^\\s*at\\s+[\\w\\.\\$]+\\(.*\\)\\s*$");
    private static final Pattern EXCEPTION_PATTERN =
            Pattern.compile("\\b\\w*Exception\\b|\\b\\w*Error\\b");

    /**
     * Calculates the context factor based on surrounding log lines.
     *
     * <p>Analyzes the context around a matched event to determine how much the surrounding lines
     * should boost the confidence score. Considers error levels, stack traces, exceptions, and
     * applies penalties for overly dense error contexts.
     *
     * @param context The event context containing lines before, matched line, and lines after.
     * @return The context factor (1.0 to maxContextFactor).
     */
    public double calculateContextFactor(EventContext context) {
        if (context == null) {
            return 1.0;
        }

        List<String> allLines = getAllContextLines(context);
        if (allLines.isEmpty()) {
            return 1.0;
        }

        double contextScore = 0.0;
        int errorLines = 0;
        int warnLines = 0;
        int stackTraceLines = 0;
        int exceptionLines = 0;

        for (String line : allLines) {
            // count severity levels
            if (ERROR_PATTERN.matcher(line).find()) {
                errorLines++;
                contextScore += 0.4; // high impact for errors
            } else if (WARN_PATTERN.matcher(line).find()) {
                warnLines++;
                contextScore += 0.2; // medium impact for warnings
            }

            // detect stack traces
            if (STACK_TRACE_PATTERN.matcher(line).find()) {
                stackTraceLines++;
                contextScore += 0.1; // small per-line bonus for stack traces
            }

            // detect exception/error class names
            if (EXCEPTION_PATTERN.matcher(line).find()) {
                exceptionLines++;
                contextScore += 0.3;
            }
        }

        // apply bonuses for patterns that indicate serious issues
        if (stackTraceLines > 0) {
            contextScore += Math.min(stackTraceLines * 0.1, 0.5); // cap stack trace bonus
        }

        // penalty for overly complex contexts (likely secondary effects)
        int totalLines = allLines.size();
        if (totalLines > 10 && (stackTraceLines + errorLines) > totalLines * 0.7) {
            contextScore *= 0.8; // reduce score for very dense error contexts
            log.debug(
                    "Applied density penalty for complex context: {} lines, {}% error content",
                    totalLines,
                    Math.round(((double) (stackTraceLines + errorLines) / totalLines) * 100));
        }

        double contextFactor = 1.0 + contextScore;

        // cap the context factor to prevent overwhelming
        if (contextFactor > maxContextFactor) {
            contextFactor = maxContextFactor;
            log.debug("Context factor capped at {}", maxContextFactor);
        }

        log.debug(
                "Context analysis: {} errors, {} warnings, {} stack traces, {} exceptions, factor={}",
                errorLines,
                warnLines,
                stackTraceLines,
                exceptionLines,
                contextFactor);

        return contextFactor;
    }

    /**
     * Extracts all context lines from the EventContext.
     *
     * @param context The event context.
     * @return A list of all context lines.
     */
    private List<String> getAllContextLines(EventContext context) {
        List<String> allLines = new ArrayList<>();

        // add lines before the match
        if (context.getLinesBefore() != null) {
            allLines.addAll(context.getLinesBefore());
        }

        // add the matched line itself
        if (context.getMatchedLine() != null) {
            allLines.add(context.getMatchedLine());
        }

        // add lines after the match
        if (context.getLinesAfter() != null) {
            allLines.addAll(context.getLinesAfter());
        }

        return allLines;
    }
}
