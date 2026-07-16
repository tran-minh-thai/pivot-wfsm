package pivotwfsm.weight;

/**
 * Bottleneck-MIN: the weight of a pattern is the weight of its weakest edge.
 * This is anti-monotone by construction, since
 * W_min(S ∪ {e}) = min(W_min(S), w(e)) ≤ W_min(S), which is what makes the
 * search-tree pruning sound. It is the default aggregator used throughout, and
 * the common ground on which Pivot-WFSM and the embedding-store baseline mine
 * the same pattern set. The anti-monotonicity proof is exercised by the tests in
 * {@code src/test/java/pivotwfsm/theorem/}.
 */
public final class MinAggregator implements Aggregator {

    @Override
    public double aggregate(double[] edgeWeights) {
        if (edgeWeights.length == 0) {
            throw new IllegalArgumentException("MIN aggregate is undefined for 0 edges");
        }
        double m = edgeWeights[0];
        for (int i = 1; i < edgeWeights.length; i++) {
            if (edgeWeights[i] < m) m = edgeWeights[i];
        }
        return m;
    }

    @Override
    public double extend(double previous, double newEdgeWeight) {
        return Math.min(previous, newEdgeWeight);
    }

    @Override
    public boolean isAntiMonotone() {
        return true;
    }

    @Override
    public String name() {
        return "MIN";
    }
}
