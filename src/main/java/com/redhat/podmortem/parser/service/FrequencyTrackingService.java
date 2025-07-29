package com.redhat.podmortem.parser.service;

import com.redhat.podmortem.common.model.analysis.PatternFrequency;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for tracking pattern match frequencies and applying frequency-based scoring penalties.
 *
 * <p>Maintains sliding time windows of pattern matches to identify frequently occurring patterns
 * that may be noise rather than genuine failure indicators. Applies penalties to reduce scores for
 * patterns that occur too frequently, helping prioritize unique or rare failure events that are
 * more likely to indicate root causes.
 */
@ApplicationScoped
public class FrequencyTrackingService {

    private static final Logger log = LoggerFactory.getLogger(FrequencyTrackingService.class);

    private final Map<String, PatternFrequency> patternFrequencies = new ConcurrentHashMap<>();

    @ConfigProperty(name = "scoring.frequency.threshold", defaultValue = "10.0")
    double frequencyThreshold;

    @ConfigProperty(name = "scoring.frequency.max-penalty", defaultValue = "0.8")
    double maxPenalty;

    @ConfigProperty(name = "scoring.frequency.time-window-hours", defaultValue = "1")
    int timeWindowHours;

    /**
     * Records a pattern match for frequency tracking.
     *
     * @param patternId The ID of the pattern that was matched
     */
    public void recordPatternMatch(String patternId) {
        if (patternId == null || patternId.trim().isEmpty()) {
            return;
        }

        PatternFrequency frequency =
                patternFrequencies.computeIfAbsent(
                        patternId, k -> new PatternFrequency(Duration.ofHours(timeWindowHours)));

        frequency.incrementCount();

        log.debug(
                "Recorded match for pattern '{}', current count: {}",
                patternId,
                frequency.getCurrentCount());
    }

    /**
     * Calculates the frequency penalty for a given pattern.
     *
     * @param patternId The ID of the pattern
     * @return Frequency penalty between 0.0 (no penalty) and maxPenalty (maximum penalty)
     */
    public double calculateFrequencyPenalty(String patternId) {
        if (patternId == null || patternId.trim().isEmpty()) {
            return 0.0;
        }

        PatternFrequency frequency = patternFrequencies.get(patternId);
        if (frequency == null) {
            return 0.0; // no matches recorded yet, no penalty
        }

        double currentRate = frequency.getHourlyRate();

        if (currentRate <= frequencyThreshold) {
            return 0.0; // below threshold, no penalty
        }

        // calculate penalty as a percentage of how much we exceed the threshold
        // penalty = min(maxPenalty, (currentRate - threshold) / threshold * scaleFactor)
        double excessRate = currentRate - frequencyThreshold;
        double penalty = Math.min(maxPenalty, excessRate / frequencyThreshold);

        log.debug(
                "Pattern '{}' frequency penalty: rate={}, threshold={}, penalty={}",
                patternId,
                currentRate,
                frequencyThreshold,
                penalty);

        return penalty;
    }

    /**
     * Gets the current frequency information for a pattern.
     *
     * @param patternId The ID of the pattern
     * @return The PatternFrequency object, or null if not found
     */
    public PatternFrequency getPatternFrequency(String patternId) {
        return patternFrequencies.get(patternId);
    }

    /**
     * Gets current frequency statistics for all tracked patterns.
     *
     * @return Map of pattern IDs to their current match counts
     */
    public Map<String, Integer> getFrequencyStatistics() {
        Map<String, Integer> stats = new ConcurrentHashMap<>();
        patternFrequencies.forEach(
                (patternId, frequency) -> stats.put(patternId, frequency.getCurrentCount()));
        return stats;
    }

    /**
     * Resets frequency tracking for a specific pattern.
     *
     * @param patternId The ID of the pattern to reset
     */
    public void resetPatternFrequency(String patternId) {
        PatternFrequency frequency = patternFrequencies.get(patternId);
        if (frequency != null) {
            frequency.reset();
            log.info("Reset frequency tracking for pattern '{}'", patternId);
        }
    }

    /** Resets frequency tracking for all patterns. */
    public void resetAllFrequencies() {
        patternFrequencies.clear();
        log.info("Reset frequency tracking for all patterns");
    }

    /**
     * Gets the current frequency threshold.
     *
     * @return The frequency threshold
     */
    public double getFrequencyThreshold() {
        return frequencyThreshold;
    }

    /**
     * Gets the maximum penalty value.
     *
     * @return The maximum penalty
     */
    public double getMaxPenalty() {
        return maxPenalty;
    }

    /**
     * Gets the time window for frequency tracking.
     *
     * @return The time window in hours
     */
    public int getTimeWindowHours() {
        return timeWindowHours;
    }
}
