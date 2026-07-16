package pivotwfsm.core;

import pivotwfsm.core.DFSCode.EdgeTuple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Compute the lex-minimum DFS code (gSpan canonical form) of the pattern
 * encoded by a given {@link DFSCode}, and answer "is this code canonical?".
 *
 * <p>Algorithm: enumerate every DFS traversal of the underlying pattern
 * graph under gSpan's right-most extension rule; the lex-min completed code
 * is the canonical one. A candidate {@code C} is canonical iff
 * {@code C.equals(minimum(C))}.
 *
 * <p>This brute-force enumeration is acceptable for the patterns this miner
 * actually emits (typically 1..6 edges in the illustrative case; up to
 * ~10–15 edges in the TUDataset benchmarks). For deeper patterns, the
 * traversal can prune branches that exceed the running min; that pruning
 * is implemented below as <em>early termination</em>.
 */
public final class MinDFSCode {

    private MinDFSCode() {}

    /** True iff {@code candidate} is its own canonical (minimum) DFS code. */
    public static boolean isMinimum(DFSCode candidate) {
        DFSCode min = minimum(candidate);
        return min.equals(candidate);
    }

    /** The canonical (lex-minimum) DFS code of the pattern encoded by {@code C}. */
    public static DFSCode minimum(DFSCode C) {
        if (C.numEdges() == 0) return C;

        int n = C.numVertices();
        int m = C.numEdges();
        int[] labels = C.vertexLabels();

        // Build adjacency: for each pattern vertex, list of {neighbour, edgeLabel, edgeId}.
        // Edges are added in both directions because the pattern is undirected.
        List<int[]>[] adj = buildAdj(C, n, m);

        State state = new State(n, m);
        DFSCode[] best = {null};

        for (int start = 0; start < n; start++) {
            // The first DFS step is forced to be a forward edge from `start`.
            for (int[] ne : adj[start]) {
                int other = ne[0];
                int edgeLabel = ne[1];
                int edgeId = ne[2];

                EdgeTuple firstTuple = new EdgeTuple(0, 1, labels[start], edgeLabel, labels[other]);
                state.reset();
                state.toDfs[start] = 0;
                state.toDfs[other] = 1;
                state.fromDfs[0] = start;
                state.fromDfs[1] = other;
                state.usedEdges[edgeId] = true;
                state.partial[0] = firstTuple;

                explore(adj, labels, state, /*nextEdge=*/1, /*rightmost=*/1, best);

                // Undo (the explore() above touched state.partial but explore() is responsible
                // for unwinding its own edits; here we just reset for next start).
            }
        }

        if (best[0] == null) {
            throw new IllegalStateException(
                "no DFS code could be enumerated from pattern with " + m + " edges");
        }
        return best[0];
    }

    @SuppressWarnings("unchecked")
    private static List<int[]>[] buildAdj(DFSCode C, int n, int m) {
        List<int[]>[] adj = (List<int[]>[]) new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int e = 0; e < m; e++) {
            EdgeTuple t = C.edge(e);
            adj[t.from].add(new int[]{t.to, t.edgeLabel, e});
            adj[t.to  ].add(new int[]{t.from, t.edgeLabel, e});
        }
        return adj;
    }

    private static void explore(List<int[]>[] adj, int[] labels, State state,
                                int nextEdge, int rightmost, DFSCode[] best) {
        int m = state.partial.length;

        if (nextEdge == m) {
            // Complete code candidate.
            EdgeTuple[] snapshot = state.partial.clone();
            DFSCode candidate = DFSCode.of(Arrays.asList(snapshot));
            if (best[0] == null || candidate.compareTo(best[0]) < 0) {
                best[0] = candidate;
            }
            return;
        }

        // Early-termination pruning: if the partial code already exceeds the
        // running best at the corresponding position, no completion can
        // produce a smaller full code.
        if (best[0] != null) {
            for (int i = 0; i < nextEdge; i++) {
                int cmp = state.partial[i].compareTo(best[0].edge(i));
                if (cmp < 0) break;          // we're already smaller - keep going
                if (cmp > 0) return;         // we're already larger - abort branch
            }
        }

        int[] rmPath = computeRightMostPath(state.partial, nextEdge, rightmost);

        int rightmostPatV = state.fromDfs[rightmost];

        // 1. BACKWARD extensions from the rightmost DFS-vertex back to another
        //    DFS-vertex on the right-most path (excluding rightmost itself).
        for (int[] ne : adj[rightmostPatV]) {
            int otherPatV = ne[0];
            int edgeLabel = ne[1];
            int edgeId = ne[2];
            if (state.usedEdges[edgeId]) continue;

            int otherDfs = state.toDfs[otherPatV];
            if (otherDfs < 0) continue;
            if (otherDfs == rightmost) continue;
            // otherDfs must be on rmPath excluding the last element.
            boolean onPath = false;
            for (int p = 0; p < rmPath.length - 1; p++) {
                if (rmPath[p] == otherDfs) { onPath = true; break; }
            }
            if (!onPath) continue;

            EdgeTuple newTuple = new EdgeTuple(rightmost, otherDfs,
                labels[rightmostPatV], edgeLabel, labels[otherPatV]);
            state.partial[nextEdge] = newTuple;
            state.usedEdges[edgeId] = true;
            explore(adj, labels, state, nextEdge + 1, rightmost, best);
            state.usedEdges[edgeId] = false;
        }

        // 2. FORWARD extensions from any vertex on the right-most path to a NEW vertex.
        int newDfs = rightmost + 1;
        for (int p = 0; p < rmPath.length; p++) {
            int anchorDfs = rmPath[p];
            int anchorPatV = state.fromDfs[anchorDfs];
            for (int[] ne : adj[anchorPatV]) {
                int otherPatV = ne[0];
                int edgeLabel = ne[1];
                int edgeId = ne[2];
                if (state.usedEdges[edgeId]) continue;
                if (state.toDfs[otherPatV] >= 0) continue;   // must be a new vertex

                EdgeTuple newTuple = new EdgeTuple(anchorDfs, newDfs,
                    labels[anchorPatV], edgeLabel, labels[otherPatV]);
                state.partial[nextEdge] = newTuple;
                state.toDfs[otherPatV] = newDfs;
                state.fromDfs[newDfs] = otherPatV;
                state.usedEdges[edgeId] = true;

                explore(adj, labels, state, nextEdge + 1, newDfs, best);

                state.usedEdges[edgeId] = false;
                state.toDfs[otherPatV] = -1;
            }
        }
    }

    /**
     * Right-most path of the partial code (vertices in DFS index space, root
     * → rightmost). Computed by following forward edges back from the
     * current rightmost.
     */
    private static int[] computeRightMostPath(EdgeTuple[] partial, int filled, int rightmost) {
        int[] parent = new int[rightmost + 1];
        Arrays.fill(parent, -1);
        for (int i = 0; i < filled; i++) {
            EdgeTuple t = partial[i];
            if (t.isForward()) {
                int hi = Math.max(t.from, t.to);
                int lo = Math.min(t.from, t.to);
                parent[hi] = lo;
            }
        }

        int[] rev = new int[rightmost + 1];
        int len = 0;
        int v = rightmost;
        rev[len++] = v;
        while (v > 0) {
            v = parent[v];
            if (v < 0) {
                throw new IllegalStateException(
                    "partial code is disconnected during right-most path computation");
            }
            rev[len++] = v;
        }
        int[] path = new int[len];
        for (int i = 0; i < len; i++) path[i] = rev[len - 1 - i];
        return path;
    }

    /** Mutable scratch state shared across the recursive explore() call. */
    private static final class State {
        final int[] toDfs;      // pattern vertex -> DFS index, -1 if unassigned
        final int[] fromDfs;    // DFS index -> pattern vertex
        final boolean[] usedEdges;
        final EdgeTuple[] partial;

        State(int n, int m) {
            this.toDfs = new int[n];
            this.fromDfs = new int[n];
            this.usedEdges = new boolean[m];
            this.partial = new EdgeTuple[m];
        }

        void reset() {
            Arrays.fill(toDfs, -1);
            Arrays.fill(fromDfs, -1);
            Arrays.fill(usedEdges, false);
            Arrays.fill(partial, null);
        }
    }
}
