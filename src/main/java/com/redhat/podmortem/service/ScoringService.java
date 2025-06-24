package com.redhat.podmortem.service;

import com.redhat.podmortem.common.model.analysis.MatchedEvent;
import com.redhat.podmortem.common.model.pattern.Pattern;
import com.redhat.podmortem.common.model.pattern.SecondaryPattern;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    // multipliers for different severity levels.
    private static final Map<String, Double> SEVERITY_MULTIPLIERS =
            Map.of(
                    "CRITICAL", 1.5,
                    "HIGH", 1.2,
                    "MEDIUM", 1.0,
                    "LOW", 0.8,
                    "INFO", 0.5);

    /**
     * Calculates the final score for a matched event.
     *
     * @param event The primary matched event.
     * @param allLines The complete array of log lines for context.
     * @return The calculated score.
     */
    public double calculateScore(MatchedEvent event, String[] allLines) {
        Pattern pattern = event.getMatchedPattern();
        double baseScore = pattern.getPrimaryPattern().getConfidence();

        // apply Severity Multiplier
        double severityMultiplier =
                SEVERITY_MULTIPLIERS.getOrDefault(pattern.getSeverity().toUpperCase(), 1.0);
        double score = baseScore * severityMultiplier;

        // apply proximity bonus from secondary patterns
        double proximityBonus = 0.0;
        List<SecondaryPattern> secondaryPatterns = pattern.getSecondaryPatterns();
        if (secondaryPatterns != null && !secondaryPatterns.isEmpty()) {
            for (SecondaryPattern secondary : secondaryPatterns) {
                if (isSecondaryPatternPresent(secondary, event.getLineNumber() - 1, allLines)) {
                    proximityBonus += secondary.getWeight();
                }
            }
        }

        log.debug(
                "Pattern '{}': Base Score={}, Severity Multiplier={}, Proximity Bonus={}",
                pattern.getName(),
                baseScore,
                severityMultiplier,
                proximityBonus);

        // final score calculation
        double finalScore = score + proximityBonus;

        // ensure score is capped at 1.0
        return Math.min(1.0, finalScore);
    }

    /**
     * check if a secondary pattern is present within the proximity window of a primary match
     *
     * @param secondary The secondary pattern to search for.
     * @param primaryMatchIndex The line number index of the primary match.
     * @param allLines The complete array of log lines.
     * @return True if the secondary pattern is found within the window, otherwise false.
     */
    private boolean isSecondaryPatternPresent(
            SecondaryPattern secondary, int primaryMatchIndex, String[] allLines) {
        int start = Math.max(0, primaryMatchIndex - secondary.getProximityWindow());
        int end = Math.min(allLines.length, primaryMatchIndex + secondary.getProximityWindow() + 1);

        for (int line = start; line < end; line++) {
            if (line == primaryMatchIndex) {
                continue; // don't match the primary line itself
            }

            if (secondary.getCompiledRegex().matcher(allLines[line]).find()) {
                log.debug(
                        "Found secondary pattern '{}' for primary match at line {}",
                        secondary.getRegex(),
                        primaryMatchIndex + 1);
                return true;
            }
        }
        return false;
    }
}
