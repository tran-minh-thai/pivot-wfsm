package pivotwfsm.weight;

/**
 * AVERAGE. Neither anti-monotone nor monotone - this is the case that forces
 * WFSM-MaxPWS / WeFreS to construct artificial upper bounds (MaxPWS,
 * MaxPosW). Kept here to enable apples-to-apples parity tests.
 */
public final class AvgAggregator implements Aggregator {

    @Override
    public double aggregate(double[] edgeWeights) {
        if (edgeWeights.length == 0) {
            throw new IllegalArgumentException("AVG aggregate is undefined for 0 edges");
        }
        double sum = 0.0;
        for (double w : edgeWeights) sum += w;
        return sum / edgeWeights.length;
    }

    /**
     * Cannot be implemented purely incrementally from {@code previous} alone:
     * we need the count of edges so far. The miner will call
     * {@link #aggregate(double[])} when AVG is selected.
     */
    @Override
    public double extend(double previous, double newEdgeWeight) {
        throw new UnsupportedOperationException(
            "AVG is not incrementally composable; recompute via aggregate(...) instead.");
    }

    @Override
    public boolean isAntiMonotone() {
        return false;
    }

    @Override
    public String name() {
        return "AVG";
    }
}
