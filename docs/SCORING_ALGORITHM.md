# Scoring Algorithm Documentation

## Overview

The log-parser uses a sophisticated multi-factor scoring algorithm to calculate confidence scores for matched failure patterns. The goal is to prioritize the most relevant and likely root cause events while filtering out noise and secondary effects.

## Overall Scoring Formula

```
Final Score = Base Confidence 
            × Severity Multiplier 
            × Chronological Factor 
            × Proximity Factor 
            × Temporal Factor 
            × Context Factor 
            × (1.0 - Frequency Penalty)
```

## Scoring Factors

### 1. Base Confidence

**Source**: Pattern definition in YAML files  
**Range**: 0.0 to 1.0  
**Purpose**: Pattern-specific confidence level defined by pattern authors

This is the starting confidence score defined in the pattern YAML file:

```yaml
primary_pattern:
  regex: "OutOfMemoryError"
  confidence: 0.85
```

### 2. Severity Multiplier

**Source**: Pattern definition in YAML files (severity field)  
**Range**: 1.0 to 5.0  
**Purpose**: Amplify scores for more severe failure types

| Severity | Multiplier |
|----------|------------|
| CRITICAL | 5.0        |
| HIGH     | 3.0        |
| MEDIUM   | 2.0        |
| LOW      | 1.5        |
| INFO     | 1.0        |

### 3. Chronological Factor

**Source**: Calculated from log line position  
**Range**: 0.5 to configurable max (default: 2.5)  
**Purpose**: Prioritize earlier errors as they're more likely to be root causes

The algorithm divides the log into three zones based on configurable thresholds:

#### Early Zone (0% to early_bonus_threshold, default: 20%)
```
factor = 1.5 + (early_bonus_threshold - position) × (bonus_range / early_bonus_threshold)
```

Where:
- `bonus_range = max_early_bonus - 1.5` (default: 2.5 - 1.5 = 1.0)
- `position = line_number / total_lines`

#### Middle Zone (20% to penalty_threshold, default: 50%)
```
factor = 1.0 + (penalty_threshold - position) × (0.5 / middle_range)
```

Where:
- `middle_range = penalty_threshold - early_bonus_threshold`

#### Late Zone (50% to 100%)
```
factor = 0.5 + (1.0 - position)
```

**Configuration Parameters**:
- `scoring.chronological.early-bonus-threshold` (default: 0.2)
- `scoring.chronological.max-early-bonus` (default: 2.5)
- `scoring.chronological.penalty-threshold` (default: 0.5)

### 4. Proximity Factor

**Source**: Secondary patterns defined in YAML files + line distance calculation  
**Range**: 1.0 to unlimited (practical max ~3.0)  
**Purpose**: Boost scores when secondary patterns are found nearby

Uses exponential decay to calculate proximity bonus:

```
decay_factor = e^(-distance / decay_constant)
proximity_contribution = secondary_weight × decay_factor
total_proximity_factor = 1.0 + Σ(proximity_contribution)
```

Where:
- `distance` = absolute difference in line numbers
- `decay_constant` = configurable decay rate (default: 10.0)
- `secondary_weight` = weight defined in pattern YAML

**Configuration Parameters**:
- `scoring.proximity.decay-constant` (default: 10.0)
- `scoring.proximity.max-window` (default: 100)

#### Example Proximity Calculation

For a secondary pattern with weight 0.8 found 5 lines away:
```
decay_factor = e^(-5/10) = e^(-0.5) ≈ 0.606
contribution = 0.8 × 0.606 ≈ 0.485
proximity_factor = 1.0 + 0.485 = 1.485
```

### 5. Temporal Factor

**Source**: Sequence patterns defined in YAML files + chronological event matching  
**Range**: 1.0 to unlimited  
**Purpose**: Bonus for matching event sequences that indicate cascading failures

```
temporal_factor = 1.0 + Σ(sequence_bonus_multiplier)
```

Where sequence matching:
1. Works backward from the primary match
2. Looks for sequence events in chronological order
3. Applies bonus if complete sequence is found

### 6. Context Factor

**Source**: Surrounding log lines analysis using regex patterns  
**Range**: 1.0 to configurable max (default: 2.5)  
**Purpose**: Boost scores based on error-rich surrounding context

The context analysis examines lines before, at, and after the match:

```
context_score = 0.4 × error_lines 
              + 0.2 × warning_lines 
              + 0.1 × stack_trace_lines 
              + 0.3 × exception_lines 
              + min(stack_trace_lines × 0.1, 0.5)
```

**Density Penalty**: If more than 70% of context lines contain errors/stack traces:
```
context_score = context_score × 0.8
```

**Final Context Factor**:
```
context_factor = min(1.0 + context_score, max_context_factor)
```

**Configuration Parameters**:
- `scoring.context.max-context-factor` (default: 2.5)

### 7. Frequency Penalty

**Source**: Pattern match frequency tracking over time window  
**Range**: 0.0 to configurable max (default: 0.8)  
**Purpose**: Reduce scores for frequently occurring patterns (noise reduction)

```
if (hourly_rate <= threshold):
    penalty = 0.0
else:
    excess_rate = hourly_rate - threshold
    penalty = min(max_penalty, excess_rate / threshold)
```

**Final Application**:
```
final_score = base_score × (1.0 - penalty)
```

**Configuration Parameters**:
- `scoring.frequency.threshold` (default: 10.0)
- `scoring.frequency.max-penalty` (default: 0.8)
- `scoring.frequency.time-window-hours` (default: 1)

## Algorithm Flow

1. **Pattern Compilation**: Regex patterns are compiled once at startup
2. **Line-by-Line Analysis**: Each log line is tested against all patterns
3. **Context Extraction**: Surrounding lines are captured based on pattern rules
4. **Multi-Factor Scoring**: All factors are calculated and multiplied together
5. **Frequency Tracking**: Pattern matches are recorded for future penalty calculation
6. **Result Aggregation**: Events are sorted by score for prioritization

## Example Calculation

Given:
- Base confidence: 0.8
- Severity: HIGH (3.0x multiplier)
- Line position: 15% through log (chronological factor: ~2.1)
- Secondary pattern found 3 lines away with weight 0.6 (proximity: ~1.4)
- No sequence match (temporal: 1.0)
- 2 errors and 1 stack trace in context (context: ~1.5)
- Pattern seen 5 times in last hour, threshold 10 (frequency penalty: 0.0)

```
Final Score = 0.8 × 3.0 × 2.1 × 1.4 × 1.0 × 1.5 × (1.0 - 0.0)
            = 0.8 × 3.0 × 2.1 × 1.4 × 1.0 × 1.5 × 1.0
            = 21.17
```

## Tuning Guidelines

### Increasing Sensitivity
- Lower `scoring.frequency.threshold` to penalize common patterns sooner
- Increase `scoring.proximity.decay-constant` for wider secondary pattern detection
- Increase `scoring.context.max-context-factor` to reward error-rich contexts more

### Reducing Noise
- Increase `scoring.frequency.threshold` to be more tolerant of repeated patterns
- Decrease `scoring.chronological.max-early-bonus` to reduce early-line bias
- Decrease `scoring.context.max-context-factor` to limit context influence

### Performance Optimization
- Decrease `scoring.proximity.max-window` to limit secondary pattern search range
- Optimize pattern regex complexity
- Adjust `scoring.frequency.time-window-hours` based on analysis volume

## Pattern Definition Best Practices

### Base Confidence Guidelines
- **0.9-1.0**: Definitive failure indicators (OutOfMemoryError, StackOverflowError)
- **0.7-0.8**: Strong failure indicators (ClassNotFoundException, ConnectionTimeout)
- **0.5-0.6**: Moderate indicators (performance warnings, resource limits)
- **0.3-0.4**: Weak indicators (debug messages, minor warnings)

### Secondary Pattern Strategy
- Use secondary patterns for related symptoms (memory pressure near OOM)
- Set appropriate proximity windows (10-50 lines for related errors)
- Weight secondary patterns by relevance (0.3-0.8 typical range)

### Sequence Pattern Design
- Define cascading failure sequences (connection → timeout → retry → failure)
- Use moderate bonus multipliers (0.2-0.5) to avoid overwhelming primary scores
- Consider temporal relationships in container startup sequences 