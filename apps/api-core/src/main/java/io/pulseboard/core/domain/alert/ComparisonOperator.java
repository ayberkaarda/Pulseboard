package io.pulseboard.core.domain.alert;

/** How a sample value is compared with the threshold of a rule. */
public enum ComparisonOperator {
    GT,
    GTE,
    LT,
    LTE;

    /** Whether {@code value} satisfies the comparison against {@code threshold}; equality counts for GTE and LTE. */
    public boolean test(double value, double threshold) {
        return switch (this) {
            case GT -> value > threshold;
            case GTE -> value >= threshold;
            case LT -> value < threshold;
            case LTE -> value <= threshold;
        };
    }
}
