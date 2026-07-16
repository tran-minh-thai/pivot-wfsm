package pivotwfsm.weight;

/**
 * MAX. Monotone INCREASING under edge extension - NOT useful for downward
 * pruning, only for ablation and as a sanity contrast in tests.
 */
public final class MaxAggregator implements Aggregator {

    @Override
    public double aggregate(double[] edgeWeights) {
        if (edgeWeights.length == 0) {
            throw new IllegalArgumentException("MAX aggregate is undefined for 0 edges");
        }
        double m = edgeWeights[0];
        for (int i = 1; i < edgeWeights.length; i++) {
            if (edgeWeights[i] > m) m = edgeWeights[i];
        }
        return m;
    }

    @Override
    public double extend(double previous, double newEdgeWeight) {
        return Math.max(previous, newEdgeWeight);
    }

    @Override
    public boolean isAntiMonotone() {
        return false;
    }

    @Override
    public String name() {
        return "MAX";
    }
}
