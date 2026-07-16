package pivotwfsm.pivot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FindAnyEmbedding: pivot-anchored subgraph-iso
 * existence check. The distinguishing mechanism of Pivot-WFSM versus the
 * embedding-store baselines: no embedding list is materialised or carried
 * between levels; each host graph is re-matched on demand from the pivot and
 * the search stops at the first complete embedding.
 *
 * <p>Injective on vertices AND on edges (parallel pattern edges must claim
 * distinct host edges - needed for multigraphs).
 */
public final class PivotMatcher {

    private PivotMatcher() {
    }

    public static boolean findAnyEmbedding(PatternGraph pattern, int pivot,
                                           HostGraphIndex hostGraph,
                                           List<Integer> candidatePivots) {
        return findAnyEmbedding(pattern, pivot, hostGraph, candidatePivots,
            Double.NEGATIVE_INFINITY);
    }

    /** Existence check where host edges below {@code minEdgeWeight} are invisible. */
    public static boolean findAnyEmbedding(PatternGraph pattern, int pivot,
                                           HostGraphIndex hostGraph,
                                           List<Integer> candidatePivots,
                                           double minEdgeWeight) {
        if (pattern.numVertices() > hostGraph.vertexCount()
            || pattern.numEdges() > hostGraph.edgeCount()) {
            return false;
        }

        int[] order = pattern.bfsOrder(pivot);
        int n = pattern.numVertices();

        for (int hostPivot : candidatePivots) {
            int[] vertexMap = new int[n];
            Arrays.fill(vertexMap, -1);
            boolean[] usedVertex = new boolean[hostGraph.vertexCount()];
            boolean[] usedEdge = new boolean[hostGraph.edgeCount()];

            vertexMap[pivot] = hostPivot;
            usedVertex[hostPivot] = true;

            if (matchFrom(pattern, hostGraph, order, 1, vertexMap, usedVertex, usedEdge,
                          minEdgeWeight)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchFrom(PatternGraph pattern, HostGraphIndex hostGraph,
                                     int[] order, int orderIndex,
                                     int[] vertexMap, boolean[] usedVertex, boolean[] usedEdge,
                                     double minEdgeWeight) {
        if (orderIndex == order.length) {
            return true;
        }

        int x = order[orderIndex];

        List<PatternGraph.Adj> mappedEdges = new ArrayList<>();
        for (PatternGraph.Adj adj : pattern.adjacencyOf(x)) {
            if (vertexMap[adj.neighbor()] >= 0) {
                mappedEdges.add(adj);
            }
        }

        int anchorHost = vertexMap[mappedEdges.get(0).neighbor()];
        int wantedLabel = pattern.vertexLabel(x);

        for (HostGraphIndex.NeighborRef ref : hostGraph.neighborsOf(anchorHost)) {
            int y = ref.toVertex();
            if (usedVertex[y] || ref.toVertexLabel() != wantedLabel) {
                continue;
            }

            int[] chosenEdges = new int[mappedEdges.size()];
            if (!assignEdges(hostGraph, mappedEdges, 0, y, vertexMap, usedEdge, chosenEdges,
                             minEdgeWeight)) {
                continue;
            }

            vertexMap[x] = y;
            usedVertex[y] = true;
            for (int e : chosenEdges) {
                usedEdge[e] = true;
            }

            if (matchFrom(pattern, hostGraph, order, orderIndex + 1,
                          vertexMap, usedVertex, usedEdge, minEdgeWeight)) {
                return true;
            }

            vertexMap[x] = -1;
            usedVertex[y] = false;
            for (int e : chosenEdges) {
                usedEdge[e] = false;
            }
        }
        return false;
    }

    private static boolean assignEdges(HostGraphIndex hostGraph,
                                       List<PatternGraph.Adj> mappedEdges, int index, int y,
                                       int[] vertexMap, boolean[] usedEdge, int[] chosenEdges,
                                       double minEdgeWeight) {
        if (index == mappedEdges.size()) {
            return true;
        }

        PatternGraph.Adj patternEdge = mappedEdges.get(index);
        int targetHost = vertexMap[patternEdge.neighbor()];

        for (HostGraphIndex.NeighborRef ref : hostGraph.neighborsOf(y)) {
            if (ref.toVertex() != targetHost
                || ref.edgeLabel() != patternEdge.edgeLabel()
                || ref.edgeWeight() < minEdgeWeight
                || usedEdge[ref.edgeId()]) {
                continue;
            }
            if (alreadyChosen(chosenEdges, index, ref.edgeId())) {
                continue;
            }

            chosenEdges[index] = ref.edgeId();
            if (assignEdges(hostGraph, mappedEdges, index + 1, y,
                            vertexMap, usedEdge, chosenEdges, minEdgeWeight)) {
                return true;
            }
        }
        return false;
    }

    private static boolean alreadyChosen(int[] chosenEdges, int count, int edgeId) {
        for (int i = 0; i < count; i++) {
            if (chosenEdges[i] == edgeId) {
                return true;
            }
        }
        return false;
    }
}
