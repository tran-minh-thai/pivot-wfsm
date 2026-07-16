package pivotwfsm.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * A transactional multigraph: bounded size, integer vertex labels, weighted
 * edges, parallel edges allowed (two distinct edge entries between the same
 * source/destination pair count as two edges).
 *
 * <p>Edges are stored in three parallel arrays so the inner loop of the
 * miner does not chase object references. The instance is immutable once
 * constructed; build via {@link #of(int, int[], int[], int[], double[])}.
 */
public final class MultiGraph {

    private final int id;
    private final int[] vertexLabels;
    private final int[] edgeSrc;
    private final int[] edgeDst;
    private final double[] edgeWeight;

    private MultiGraph(int id, int[] vertexLabels,
                       int[] edgeSrc, int[] edgeDst, double[] edgeWeight) {
        this.id = id;
        this.vertexLabels = vertexLabels;
        this.edgeSrc = edgeSrc;
        this.edgeDst = edgeDst;
        this.edgeWeight = edgeWeight;
    }

    /**
     * Construct a multigraph after defensive validation. Throws
     * {@link IllegalArgumentException} on shape mismatch - the loader relies
     * on this, so JSON typos surface immediately.
     */
    public static MultiGraph of(int id, int[] vertexLabels,
                                int[] edgeSrc, int[] edgeDst, double[] edgeWeight) {
        Objects.requireNonNull(vertexLabels, "vertexLabels");
        Objects.requireNonNull(edgeSrc, "edgeSrc");
        Objects.requireNonNull(edgeDst, "edgeDst");
        Objects.requireNonNull(edgeWeight, "edgeWeight");
        if (edgeSrc.length != edgeDst.length || edgeSrc.length != edgeWeight.length) {
            throw new IllegalArgumentException(
                "edge arrays have different lengths: src=" + edgeSrc.length
                + " dst=" + edgeDst.length + " weight=" + edgeWeight.length);
        }
        int n = vertexLabels.length;
        for (int e = 0; e < edgeSrc.length; e++) {
            if (edgeSrc[e] < 0 || edgeSrc[e] >= n) {
                throw new IllegalArgumentException(
                    "edge " + e + " src=" + edgeSrc[e] + " out of [0," + n + ")");
            }
            if (edgeDst[e] < 0 || edgeDst[e] >= n) {
                throw new IllegalArgumentException(
                    "edge " + e + " dst=" + edgeDst[e] + " out of [0," + n + ")");
            }
        }
        return new MultiGraph(id, vertexLabels.clone(),
            edgeSrc.clone(), edgeDst.clone(), edgeWeight.clone());
    }

    public int id() {
        return id;
    }

    public int numVertices() {
        return vertexLabels.length;
    }

    public int numEdges() {
        return edgeSrc.length;
    }

    public int vertexLabel(int v) {
        return vertexLabels[v];
    }

    public int edgeSrc(int e) {
        return edgeSrc[e];
    }

    public int edgeDst(int e) {
        return edgeDst[e];
    }

    public double edgeWeight(int e) {
        return edgeWeight[e];
    }

    /**
     * Snapshot of vertex labels. Caller may mutate without affecting this
     * instance.
     */
    public int[] vertexLabelsCopy() {
        return vertexLabels.clone();
    }

    @Override
    public String toString() {
        return "MultiGraph{id=" + id
            + ", |V|=" + vertexLabels.length
            + ", |E|=" + edgeSrc.length
            + ", labels=" + Arrays.toString(vertexLabels) + "}";
    }
}
