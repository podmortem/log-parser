package com.redhat.podmortem.parser.service;

import com.redhat.podmortem.common.model.analysis.MatchedEvent;
import com.redhat.podmortem.common.model.pattern.Pattern;
import com.redhat.podmortem.common.model.pattern.SecondaryPattern;
import com.redhat.podmortem.common.model.pattern.SequenceEvent;
import com.redhat.podmortem.common.model.pattern.SequencePattern;
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

        // calculate temporal factor using sequence pattern analysis
        double temporalFactor = calculateTemporalFactor(event, allLines);

        log.debug(
                "Pattern '{}': Base Confidence={}, Severity Multiplier={}, Proximity Factor={}, Temporal Factor={}",
                pattern.getName(),
                baseConfidence,
                severityMultiplier,
                proximityFactor,
                temporalFactor);

        // final score calculation using multiplicative formula
        double finalScore = baseConfidence * severityMultiplier * proximityFactor * temporalFactor;

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
     * Calculates the temporal factor using sequence pattern analysis.
     *
     * @param event The primary matched event.
     * @param allLines The complete array of log lines for context.
     * @return The temporal factor (1.0 + cumulative sequence bonuses).
     */
    private double calculateTemporalFactor(MatchedEvent event, String[] allLines) {
        Pattern pattern = event.getMatchedPattern();
        List<SequencePattern> sequences = pattern.getSequencePatterns();

        if (sequences == null || sequences.isEmpty()) {
            return 1.0;
        }

        double totalSequenceBonus = 0.0;
        for (SequencePattern sequence : sequences) {
            if (isSequenceMatched(sequence, event, allLines)) {
                totalSequenceBonus += sequence.getBonusMultiplier();

                log.debug(
                        "Sequence pattern '{}' matched, adding bonus: {}",
                        sequence.getDescription(),
                        sequence.getBonusMultiplier());
            }
        }

        return 1.0 + totalSequenceBonus;
    }

    /**
     * Checks if a sequence pattern is matched by finding all required events in order.
     *
     * @param sequence The sequence pattern to check.
     * @param event The primary matched event.
     * @param allLines The complete array of log lines for context.
     * @return True if the sequence is matched, false otherwise.
     */
    private boolean isSequenceMatched(
            SequencePattern sequence, MatchedEvent event, String[] allLines) {
        List<SequenceEvent> events = sequence.getEvents();
        if (events == null || events.isEmpty()) {
            return false;
        }

        int primaryLineIndex = event.getLineNumber() - 1;
        int currentSearchIndex = 0;

        // look for events in sequence order, working backwards from primary match
        for (int i = events.size() - 1; i >= 0; i--) {
            SequenceEvent sequenceEvent = events.get(i);

            // for the most recent event, it should be near or at the primary match
            if (i == events.size() - 1) {
                // check if this event matches the primary or is very close to it
                if (!isEventFoundNearPrimary(sequenceEvent, primaryLineIndex, allLines)) {
                    return false;
                }
                currentSearchIndex = primaryLineIndex;
            } else {
                // look for this event before the current position
                int foundIndex = findEventBefore(sequenceEvent, currentSearchIndex, allLines);
                if (foundIndex < 0) {
                    return false;
                }
                currentSearchIndex = foundIndex;
            }
        }

        return true;
    }

    /** Checks if a sequence event is found near the primary match. */
    private boolean isEventFoundNearPrimary(
            SequenceEvent sequenceEvent, int primaryIndex, String[] allLines) {
        // check a small window around the primary match (+/- 5 lines)
        int windowSize = 5;
        int start = Math.max(0, primaryIndex - windowSize);
        int end = Math.min(allLines.length, primaryIndex + windowSize + 1);

        for (int i = start; i < end; i++) {
            if (sequenceEvent.getCompiledRegex() != null
                    && sequenceEvent.getCompiledRegex().matcher(allLines[i]).find()) {
                return true;
            }
        }
        return false;
    }

    /** Finds a sequence event before the given index. */
    private int findEventBefore(SequenceEvent sequenceEvent, int beforeIndex, String[] allLines) {
        // search backwards from the given index
        for (int i = beforeIndex - 1; i >= 0; i--) {
            if (sequenceEvent.getCompiledRegex() != null
                    && sequenceEvent.getCompiledRegex().matcher(allLines[i]).find()) {
                return i;
            }
        }
        return -1; // not found
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
