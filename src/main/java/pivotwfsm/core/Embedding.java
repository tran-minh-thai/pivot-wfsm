package pivotwfsm.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * One embedding of a pattern (described by a {@link DFSCode}) into one host
 * {@link MultiGraph}: which host vertex realises each pattern vertex, and
 * which host edge realises each pattern edge.
 *
 * <p>The edge-index map matters for multigraphs: two parallel edges between
 * the same vertex pair are distinct embeddings even though the vertex map
 * is identical. Carrying the explicit edge index disambiguates them.
 *
 * <p>For weighted FSM with the bottleneck-MIN aggregator the cached
 * {@link #wMin()} is the bottleneck of the realised edges. {@link #wAvg()}
 * is cached too so the parity test against WFSM-MaxPWS can reuse the same
 * embedding without recomputation.
 */
public final class Embedding {

    private final int hostGraphId;
    private final int[] vertexMap;
    private final int[] edgeMap;
    private final double[] edgeWeights;
    private final double wMin;
    private final double wAvg;

    private Embedding(int hostGraphId, int[] vertexMap, int[] edgeMap,
                      double[] edgeWeights, double wMin, double wAvg) {
        this.hostGraphId = hostGraphId;
        this.vertexMap = vertexMap;
        this.edgeMap = edgeMap;
        this.edgeWeights = edgeWeights;
        this.wMin = wMin;
        this.wAvg = wAvg;
    }

    /**
     * Construct an embedding and pre-compute its bottleneck / average over
     * the realised edges.
     *
     * @param hostGraphId index of the host graph in {@link MultiGraphDB}
     * @param hostGraph   the host graph itself; only used for weight lookups
     * @param vertexMap   {@code vertexMap[i]} = host vertex realising pattern vertex {@code i}
     * @param edgeMap     {@code edgeMap[k]}   = host edge index realising pattern edge {@code k}
     */
    public static Embedding of(int hostGraphId, MultiGraph hostGraph,
                               int[] vertexMap, int[] edgeMap) {
        Objects.requireNonNull(hostGraph, "hostGraph");
        Objects.requireNonNull(vertexMap, "vertexMap");
        Objects.requireNonNull(edgeMap, "edgeMap");
        if (edgeMap.length == 0) {
            // Zero-edge pattern (a single isolated vertex): W_min = +∞ semantics,
            // but we never actually mine such patterns since gSpan starts at edges.
            return new Embedding(hostGraphId, vertexMap.clone(), edgeMap.clone(),
                new double[0], Double.POSITIVE_INFINITY, Double.NaN);
        }
        double[] edgeWeights = new double[edgeMap.length];
        double wMin = Double.POSITIVE_INFINITY;
        double wSum = 0.0;
        for (int i = 0; i < edgeMap.length; i++) {
            double w = hostGraph.edgeWeight(edgeMap[i]);
            edgeWeights[i] = w;
            if (w < wMin) wMin = w;
            wSum += w;
        }
        double wAvg = wSum / edgeMap.length;
        return new Embedding(hostGraphId, vertexMap.clone(), edgeMap.clone(),
            edgeWeights, wMin, wAvg);
    }

    /**
     * Realised edge weights in the same order as the pattern's edges. Used
     * by aggregator strategies that are not pre-cached (anything other than
     * MIN/AVG). Defensive copy.
     */
    public double[] edgeWeights() {
        return edgeWeights.clone();
    }

    /**
     * Read-only view (no copy) of the realised edge weights, for hot-path
     * aggregator calls that won't mutate the array. Treat the returned
     * reference as immutable.
     */
    public double[] edgeWeightsUnsafe() {
        return edgeWeights;
    }

    public int hostGraphId() {
        return hostGraphId;
    }

    public int patternVertexCount() {
        return vertexMap.length;
    }

    public int patternEdgeCount() {
        return edgeMap.length;
    }

    public int hostVertexFor(int patternVertex) {
        return vertexMap[patternVertex];
    }

    public int hostEdgeFor(int patternEdge) {
        return edgeMap[patternEdge];
    }

    public double wMin() {
        return wMin;
    }

    public double wAvg() {
        return wAvg;
    }

    public int[] vertexMapCopy() {
        return vertexMap.clone();
    }

    public int[] edgeMapCopy() {
        return edgeMap.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Embedding e)) return false;
        return hostGraphId == e.hostGraphId
            && Arrays.equals(vertexMap, e.vertexMap)
            && Arrays.equals(edgeMap, e.edgeMap);
    }

    @Override
    public int hashCode() {
        int h = hostGraphId;
        h = 31 * h + Arrays.hashCode(vertexMap);
        h = 31 * h + Arrays.hashCode(edgeMap);
        return h;
    }

    @Override
    public String toString() {
        return "Embedding{G" + hostGraphId
            + ", V=" + Arrays.toString(vertexMap)
            + ", E=" + Arrays.toString(edgeMap)
            + ", wMin=" + wMin + ", wAvg=" + wAvg + "}";
    }
}
