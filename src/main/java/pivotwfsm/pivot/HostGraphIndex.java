package pivotwfsm.pivot;

import pivotwfsm.core.MultiGraph;
import pivotwfsm.core.MultiGraphDB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adjacency + neighbourhood-signature view over a {@link MultiGraph}, built
 * once per host graph. This is the data layer the pivot pipeline needs but
 * that {@link MultiGraph} (a bare label/weight holder) does not provide.
 *
 * <p>The datasets used here carry no edge labels, so every incident edge is
 * given the constant label 0. The label is kept explicit throughout rather than
 * dropped, so that the code also covers the labelled case unchanged.
 */
public final class HostGraphIndex {

    /** One adjacency entry viewed from a source vertex. */
    public record NeighborRef(int toVertex, int edgeId, int toVertexLabel,
                              int edgeLabel, double edgeWeight) {}

    /** Coarse local signature used to prune pivot candidates. */
    public record Signature(int vertexLabel, int degree,
                            Map<Integer, Integer> neighborLabelHistogram,
                            Map<Integer, Integer> incidentEdgeLabelHistogram,
                            int[] neighborDegreesDescending) {}

    private static final int EDGE_LABEL = 0;

    private final MultiGraph graph;
    private final NeighborRef[][] adjacency;
    private final Signature[] signatures;

    private HostGraphIndex(MultiGraph graph, NeighborRef[][] adjacency, Signature[] signatures) {
        this.graph = graph;
        this.adjacency = adjacency;
        this.signatures = signatures;
    }

    public static HostGraphIndex build(MultiGraph graph) {
        int n = graph.numVertices();
        List<List<NeighborRef>> buckets = new ArrayList<>(n);
        for (int v = 0; v < n; v++) {
            buckets.add(new ArrayList<>());
        }
        for (int e = 0; e < graph.numEdges(); e++) {
            int a = graph.edgeSrc(e);
            int b = graph.edgeDst(e);
            double w = graph.edgeWeight(e);
            buckets.get(a).add(new NeighborRef(b, e, graph.vertexLabel(b), EDGE_LABEL, w));
            buckets.get(b).add(new NeighborRef(a, e, graph.vertexLabel(a), EDGE_LABEL, w));
        }

        NeighborRef[][] adjacency = new NeighborRef[n][];
        for (int v = 0; v < n; v++) {
            adjacency[v] = buckets.get(v).toArray(new NeighborRef[0]);
        }
        Signature[] signatures = new Signature[n];
        for (int v = 0; v < n; v++) {
            signatures[v] = buildSignature(graph, adjacency[v], v);
        }
        return new HostGraphIndex(graph, adjacency, signatures);
    }

    private static Signature buildSignature(MultiGraph graph, NeighborRef[] neighbors, int v) {
        Map<Integer, Integer> neighborLabels = new HashMap<>();
        Map<Integer, Integer> edgeLabels = new HashMap<>();
        int[] neighborDegrees = new int[neighbors.length];
        for (int i = 0; i < neighbors.length; i++) {
            NeighborRef ref = neighbors[i];
            neighborLabels.merge(ref.toVertexLabel(), 1, Integer::sum);
            edgeLabels.merge(ref.edgeLabel(), 1, Integer::sum);
            neighborDegrees[i] = degreeOfRaw(graph, ref.toVertex());
        }
        sortDescending(neighborDegrees);
        return new Signature(graph.vertexLabel(v), neighbors.length,
            Map.copyOf(neighborLabels), Map.copyOf(edgeLabels), neighborDegrees);
    }

    private static int degreeOfRaw(MultiGraph graph, int v) {
        int deg = 0;
        for (int e = 0; e < graph.numEdges(); e++) {
            if (graph.edgeSrc(e) == v || graph.edgeDst(e) == v) {
                deg++;
            }
        }
        return deg;
    }

    private static void sortDescending(int[] values) {
        java.util.Arrays.sort(values);
        for (int lo = 0, hi = values.length - 1; lo < hi; lo++, hi--) {
            int t = values[lo];
            values[lo] = values[hi];
            values[hi] = t;
        }
    }

    public MultiGraph graph() {
        return graph;
    }

    public int vertexCount() {
        return graph.numVertices();
    }

    public int edgeCount() {
        return graph.numEdges();
    }

    public NeighborRef[] neighborsOf(int vertex) {
        return adjacency[vertex];
    }

    public int degreeOf(int vertex) {
        return adjacency[vertex].length;
    }

    public Signature signatureOf(int vertex) {
        return signatures[vertex];
    }
}
