package com.redhat.podmortem.parser.service;

import com.redhat.podmortem.common.model.analysis.MatchedEvent;
import com.redhat.podmortem.common.model.pattern.Pattern;
import com.redhat.podmortem.common.model.pattern.SecondaryPattern;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    // multipliers for different severity levels.
    private static final Map<String, Double> SEVERITY_MULTIPLIERS =
            Map.of(
                    "CRITICAL", 5.0,
                    "HIGH", 3.0,
                    "MEDIUM", 2.0,
                    "LOW", 1.5,
                    "INFO", 1.0);

    @ConfigProperty(name = "scoring.proximity.decay-constant", defaultValue = "10.0")
    double decayConstant;

    @ConfigProperty(name = "scoring.proximity.max-window", defaultValue = "100")
    int maxWindow;

    /**
     * Calculates the final score for a matched event.
     *
     * @param event The primary matched event.
     * @param allLines The complete array of log lines for context.
     * @return The calculated score.
     */
    public double calculateScore(MatchedEvent event, String[] allLines) {
        Pattern pattern = event.getMatchedPattern();
        double baseConfidence = pattern.getPrimaryPattern().getConfidence();

        // apply Severity Multiplier
        double severityMultiplier =
                SEVERITY_MULTIPLIERS.getOrDefault(pattern.getSeverity().toUpperCase(), 1.0);

        // calculate proximity factor using exponential decay
        double proximityFactor = calculateProximityFactor(event, allLines);

        log.debug(
                "Pattern '{}': Base Confidence={}, Severity Multiplier={}, Proximity Factor={}",
                pattern.getName(),
                baseConfidence,
                severityMultiplier,
                proximityFactor);

        // final score calculation using multiplicative formula
        double finalScore = baseConfidence * severityMultiplier * proximityFactor;

        return finalScore;
    }

    /**
     * Calculates the proximity factor using exponential decay based on distance to secondary
     * patterns.
     *
     * @param event The primary matched event.
     * @param allLines The complete array of log lines for context.
     * @return The proximity factor (1.0 + cumulative weighted decay factors).
     */
    private double calculateProximityFactor(MatchedEvent event, String[] allLines) {
        Pattern pattern = event.getMatchedPattern();
        List<SecondaryPattern> secondaryPatterns = pattern.getSecondaryPatterns();

        if (secondaryPatterns == null || secondaryPatterns.isEmpty()) {
            return 1.0; // no secondary patterns
        }

        double totalProximityFactor = 0.0;
        int primaryLineIndex = event.getLineNumber() - 1;

        for (SecondaryPattern secondary : secondaryPatterns) {
            double closestDistance =
                    findClosestSecondaryPatternDistance(secondary, primaryLineIndex, allLines);

            if (closestDistance >= 0) { // pattern found
                double decayFactor = Math.exp(-closestDistance / decayConstant);
                totalProximityFactor += secondary.getWeight() * decayFactor;

                log.debug(
                        "Secondary pattern '{}' found at distance {}, decay factor: {}, weighted contribution: {}",
                        secondary.getRegex(),
                        closestDistance,
                        decayFactor,
                        secondary.getWeight() * decayFactor);
            }
        }

        return 1.0 + totalProximityFactor; // base 1.0 + bonus
    }

    /**
     * Finds the closest distance to a secondary pattern within the proximity window.
     *
     * @param secondary The secondary pattern to search for.
     * @param primaryIndex The line number index of the primary match.
     * @param allLines The complete array of log lines.
     * @return The distance to the closest match, or -1 if not found within window.
     */
    private double findClosestSecondaryPatternDistance(
            SecondaryPattern secondary, int primaryIndex, String[] allLines) {

        // Use the smaller of configured max window or pattern's proximity window
        int windowSize = Math.min(maxWindow, secondary.getProximityWindow());
        int start = Math.max(0, primaryIndex - windowSize);
        int end = Math.min(allLines.length, primaryIndex + windowSize + 1);

        double closestDistance = -1;

        for (int line = start; line < end; line++) {
            if (line == primaryIndex) {
                continue; // don't match the primary line itself
            }

            if (secondary.getCompiledRegex().matcher(allLines[line]).find()) {
                double distance = Math.abs(line - primaryIndex);

                if (closestDistance < 0 || distance < closestDistance) {
                    closestDistance = distance;
                }

                log.debug(
                        "Found secondary pattern '{}' at line {} (distance {} from primary at line {})",
                        secondary.getRegex(),
                        line + 1,
                        distance,
                        primaryIndex + 1);
            }
        }

        return closestDistance;
    }
}
