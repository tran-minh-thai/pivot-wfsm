package pivotwfsm.pivot;

import pivotwfsm.core.DFSCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Adjacency view of a pattern described by a {@link DFSCode}. Built on the
 * shared core graph types so pivot mining and the baselines share one codebase.
 */
public final class PatternGraph {

    public record Adj(int neighbor, int edgeId, int edgeLabel) {}

    private final DFSCode code;
    private final int[] vertexLabels;
    private final int[] edgeSrc;
    private final int[] edgeDst;
    private final int[] edgeLabels;
    private final List<List<Adj>> adjacency;

    private PatternGraph(DFSCode code, int[] vertexLabels,
                         int[] edgeSrc, int[] edgeDst, int[] edgeLabels,
                         List<List<Adj>> adjacency) {
        this.code = code;
        this.vertexLabels = vertexLabels;
        this.edgeSrc = edgeSrc;
        this.edgeDst = edgeDst;
        this.edgeLabels = edgeLabels;
        this.adjacency = adjacency;
    }

    public static PatternGraph fromCode(DFSCode code) {
        int n = code.numVertices();
        int m = code.numEdges();
        int[] vertexLabels = code.vertexLabels();
        int[] edgeSrc = new int[m];
        int[] edgeDst = new int[m];
        int[] edgeLabels = new int[m];

        List<List<Adj>> adjacency = new ArrayList<>(n);
        for (int v = 0; v < n; v++) {
            adjacency.add(new ArrayList<>());
        }
        for (int e = 0; e < m; e++) {
            DFSCode.EdgeTuple tuple = code.edge(e);
            edgeSrc[e] = tuple.from;
            edgeDst[e] = tuple.to;
            edgeLabels[e] = tuple.edgeLabel;
            adjacency.get(tuple.from).add(new Adj(tuple.to, e, tuple.edgeLabel));
            adjacency.get(tuple.to).add(new Adj(tuple.from, e, tuple.edgeLabel));
        }
        return new PatternGraph(code, vertexLabels, edgeSrc, edgeDst, edgeLabels, adjacency);
    }

    public DFSCode code() {
        return code;
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

    public int edgeLabel(int e) {
        return edgeLabels[e];
    }

    public List<Adj> adjacencyOf(int v) {
        return adjacency.get(v);
    }

    public int degree(int v) {
        return adjacency.get(v).size();
    }

    public int distinctNeighborLabelCount(int v) {
        return (int) adjacency.get(v).stream()
            .map(a -> vertexLabels[a.neighbor()])
            .distinct()
            .count();
    }

    public int[] bfsDistances(int source) {
        int[] dist = new int[numVertices()];
        Arrays.fill(dist, -1);
        dist[source] = 0;
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(source);
        while (!queue.isEmpty()) {
            int v = queue.poll();
            for (Adj adj : adjacency.get(v)) {
                if (dist[adj.neighbor()] < 0) {
                    dist[adj.neighbor()] = dist[v] + 1;
                    queue.add(adj.neighbor());
                }
            }
        }
        return dist;
    }

    public int[] bfsOrder(int source) {
        int n = numVertices();
        int[] order = new int[n];
        boolean[] seen = new boolean[n];
        int count = 0;
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(source);
        seen[source] = true;
        while (!queue.isEmpty()) {
            int v = queue.poll();
            order[count++] = v;
            for (Adj adj : adjacency.get(v)) {
                if (!seen[adj.neighbor()]) {
                    seen[adj.neighbor()] = true;
                    queue.add(adj.neighbor());
                }
            }
        }
        if (count != n) {
            throw new IllegalStateException("pattern is not connected: reached "
                + count + " of " + n + " vertices");
        }
        return order;
    }

    public int[] rightMostPath() {
        int n = numVertices();
        if (n == 1) {
            return new int[] {0};
        }
        int[] parent = new int[n];
        Arrays.fill(parent, -1);
        for (int e = 0; e < code.numEdges(); e++) {
            DFSCode.EdgeTuple tuple = code.edge(e);
            if (tuple.isForward()) {
                parent[tuple.to] = tuple.from;
            }
        }
        List<Integer> path = new ArrayList<>();
        for (int v = n - 1; v >= 0; v = parent[v]) {
            path.add(v);
            if (v == 0) {
                break;
            }
        }
        int[] result = new int[path.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = path.get(result.length - 1 - i);
        }
        return result;
    }
}
