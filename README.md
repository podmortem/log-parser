# log-parser

A Quarkus-based service for analyzing logs and identifying failure patterns.

## Overview

This service processes raw pod failure logs to identify known failure patterns using pattern matching and scoring algorithms. It extracts meaningful events, calculates confidence scores, and provides structured analysis results for AI analysis.

## REST Endpoints

- `POST /parse` - Analyze pod failure logs and return structured results

## Scoring Algorithm

The scoring system evaluates multiple factors:

- **Base Confidence** - Pattern-defined confidence level
- **Severity Multiplier** - Critical > High > Medium > Low > Info
- **Chronological Factor** - Earlier errors weighted higher (likely root causes)
- **Proximity Factor** - Bonus for nearby secondary patterns using exponential decay
- **Temporal Factor** - Bonus for matching event sequences
- **Context Factor** - Bonus for error-rich surrounding context
- **Frequency Penalty** - Reduced scores for frequently occurring patterns

For detailed mathematical formulas, configuration parameters, and tuning guidelines, see [SCORING_ALGORITHM.md](docs/SCORING_ALGORITHM.md).

## Configuration

Key configuration properties:

- `pattern.directory` - Directory containing YAML pattern files
- `scoring.proximity.decay-constant` - Exponential decay rate for proximity scoring
- `scoring.frequency.threshold` - Frequency threshold before applying penalties
- `scoring.context.max-context-factor` - Maximum context score multiplier

## Dependencies

- `common-lib` - Shared models and pattern definitions

## Building

```bash
./mvnw package
```

For native compilation:
```bash
./mvnw package -Dnative
``` 