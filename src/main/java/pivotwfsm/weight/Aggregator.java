package pivotwfsm.weight;

/**
 * Strategy interface for weight aggregation over an edge set.
 * Implementations: {@link MinAggregator} (the contribution), {@link MaxAggregator},
 * {@link AvgAggregator} (used in ablations and for parity against WFSM-MaxPWS).
 */
public interface Aggregator {

    /** Aggregate over a freshly-built edge weight array. */
    double aggregate(double[] edgeWeights);

    /** Incremental update when a single edge of weight w is added. */
    double extend(double previous, double newEdgeWeight);

    /** True if the operator is anti-monotone under edge extension. */
    boolean isAntiMonotone();

    /** Human-readable name for logging and result CSVs. */
    String name();
}
