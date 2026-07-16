package pivotwfsm.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * gSpan-style canonical DFS code (Yan &amp; Han, ICDM 2002).
 *
 * <p>A DFS code is a sequence of edge tuples {@code (i, j, l_i, l_e, l_j)}
 * where {@code i} and {@code j} are vertex indices in the order they are
 * discovered by a DFS traversal, {@code l_i, l_j} are vertex labels, and
 * {@code l_e} is the edge label (we use a single sentinel {@code 0} for the
 * unlabeled case, since the illustrative dataset and TUDataset edges carry
 * no chemical bond type).
 *
 * <p>A tuple with {@code i &lt; j} is a <b>forward</b> edge (extends the DFS
 * tree to a new vertex); a tuple with {@code i &gt; j} is a <b>backward</b>
 * edge (closes a cycle to an already-seen vertex). gSpan's right-most
 * extension rule constrains the structure of valid extensions.
 *
 * <p>This class is immutable. Two DFSCodes are {@link #equals(Object) equal}
 * iff their tuple sequences match element by element.
 */
public final class DFSCode implements Comparable<DFSCode> {

    /** A single edge tuple in the canonical code. */
    public static final class EdgeTuple implements Comparable<EdgeTuple> {
        public final int from;
        public final int to;
        public final int fromLabel;
        public final int edgeLabel;
        public final int toLabel;

        public EdgeTuple(int from, int to, int fromLabel, int edgeLabel, int toLabel) {
            this.from = from;
            this.to = to;
            this.fromLabel = fromLabel;
            this.edgeLabel = edgeLabel;
            this.toLabel = toLabel;
        }

        public boolean isForward() {
            return from < to;
        }

        public boolean isBackward() {
            return from > to;
        }

        /**
         * gSpan lexicographic order on edge tuples. The order is what makes
         * the "minimum DFS code" well-defined.
         *
         * <p>The recipe (Yan &amp; Han 2002, Definition 5): tuple {@code e1}
         * precedes {@code e2} when, comparing field by field in the order
         * {@code (i, j, l_i, l_e, l_j)} with a special rule that <em>any
         * backward edge precedes any forward edge with the same {@code i}</em>,
         * the first differing field is smaller in {@code e1}.
         */
        @Override
        public int compareTo(EdgeTuple o) {
            // Special rule first: at the same source i, backward < forward.
            if (this.from == o.from && this.isBackward() != o.isBackward()) {
                return this.isBackward() ? -1 : 1;
            }
            int c = Integer.compare(this.from, o.from);
            if (c != 0) return c;
            c = Integer.compare(this.to, o.to);
            if (c != 0) return c;
            c = Integer.compare(this.fromLabel, o.fromLabel);
            if (c != 0) return c;
            c = Integer.compare(this.edgeLabel, o.edgeLabel);
            if (c != 0) return c;
            return Integer.compare(this.toLabel, o.toLabel);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EdgeTuple t)) return false;
            return from == t.from && to == t.to
                && fromLabel == t.fromLabel
                && edgeLabel == t.edgeLabel
                && toLabel == t.toLabel;
        }

        @Override
        public int hashCode() {
            return Objects.hash(from, to, fromLabel, edgeLabel, toLabel);
        }

        @Override
        public String toString() {
            return "(" + from + "," + to + "," + fromLabel + "," + edgeLabel + "," + toLabel + ")";
        }
    }

    private final EdgeTuple[] edges;
    private final int numVertices;

    private DFSCode(EdgeTuple[] edges, int numVertices) {
        this.edges = edges;
        this.numVertices = numVertices;
    }

    /**
     * Build a DFS code from an ordered list of tuples. Performs basic
     * structural validation: every forward edge must extend to a new vertex
     * index (max so far + 1), and every backward edge must point to an
     * already-discovered vertex.
     */
    public static DFSCode of(List<EdgeTuple> edges) {
        Objects.requireNonNull(edges, "edges");
        EdgeTuple[] arr = edges.toArray(new EdgeTuple[0]);
        int seen = -1;
        for (int i = 0; i < arr.length; i++) {
            EdgeTuple t = arr[i];
            if (t.from == t.to) {
                throw new IllegalArgumentException(
                    "edge " + i + " " + t + " is a self-loop, not supported");
            }
            if (i == 0) {
                // The first edge of any DFS code is forward (0, 1):
                // it introduces both vertex 0 and vertex 1 simultaneously.
                if (!t.isForward() || t.from != 0 || t.to != 1) {
                    throw new IllegalArgumentException(
                        "first edge " + t + " must be forward (0,1); "
                            + "every DFS code begins at vertex 0 extending to vertex 1");
                }
                seen = 1;
            } else if (t.isForward()) {
                int newIndex = Math.max(t.from, t.to);
                int oldIndex = Math.min(t.from, t.to);
                if (newIndex != seen + 1) {
                    throw new IllegalArgumentException(
                        "edge " + i + " " + t + " introduces vertex " + newIndex
                            + " out of order (expected " + (seen + 1) + ")");
                }
                if (oldIndex < 0 || oldIndex > seen) {
                    throw new IllegalArgumentException(
                        "edge " + i + " " + t + " references unseen vertex " + oldIndex);
                }
                seen = newIndex;
            } else {
                // Backward edge: both endpoints must have been seen.
                int hi = Math.max(t.from, t.to);
                int lo = Math.min(t.from, t.to);
                if (hi > seen || lo < 0) {
                    throw new IllegalArgumentException(
                        "edge " + i + " " + t + " is backward but vertex " + hi
                            + " has not been discovered (seen up to " + seen + ")");
                }
            }
        }
        return new DFSCode(arr, seen + 1);
    }

    public static DFSCode of(EdgeTuple... edges) {
        return of(Arrays.asList(edges));
    }

    /** Empty code (0 edges, 0 vertices). Sentinel for the search-tree root. */
    public static DFSCode empty() {
        return new DFSCode(new EdgeTuple[0], 0);
    }

    public int numEdges() {
        return edges.length;
    }

    public int numVertices() {
        return numVertices;
    }

    public EdgeTuple edge(int i) {
        return edges[i];
    }

    /** Returns an immutable view of the tuple sequence. */
    public List<EdgeTuple> edges() {
        return Collections.unmodifiableList(Arrays.asList(edges));
    }

    /**
     * Append one edge tuple, returning a new code. The caller is responsible
     * for ensuring the extension is structurally valid; this method
     * delegates validation to {@link #of(List)}.
     */
    public DFSCode extend(EdgeTuple tuple) {
        List<EdgeTuple> next = new ArrayList<>(edges.length + 1);
        Collections.addAll(next, edges);
        next.add(tuple);
        return of(next);
    }

    /**
     * Vertex labels recovered from the code, in DFS-index order. Useful for
     * lookups when matching against host graphs.
     */
    public int[] vertexLabels() {
        int[] labels = new int[numVertices];
        Arrays.fill(labels, Integer.MIN_VALUE);
        for (EdgeTuple t : edges) {
            int hi = Math.max(t.from, t.to);
            int lo = Math.min(t.from, t.to);
            int hiLabel = (t.from == hi) ? t.fromLabel : t.toLabel;
            int loLabel = (t.from == lo) ? t.fromLabel : t.toLabel;
            if (labels[hi] != Integer.MIN_VALUE && labels[hi] != hiLabel) {
                throw new IllegalStateException(
                    "vertex " + hi + " has conflicting labels in the code");
            }
            if (labels[lo] != Integer.MIN_VALUE && labels[lo] != loLabel) {
                throw new IllegalStateException(
                    "vertex " + lo + " has conflicting labels in the code");
            }
            labels[hi] = hiLabel;
            labels[lo] = loLabel;
        }
        return labels;
    }

    /**
     * The DFS right-most path: the sequence of vertex indices from the root
     * (vertex 0) to the right-most vertex (vertex {@code numVertices - 1}),
     * following only forward edges of the code.
     *
     * <p>gSpan's right-most extension rule only adds edges anchored at
     * vertices on this path: forward edges grow a new branch from any path
     * vertex, backward edges close cycles from the right-most vertex.
     *
     * <p>For the empty code returns an empty array.
     */
    public int[] rightMostPath() {
        if (numVertices == 0) return new int[0];
        if (numVertices == 1) return new int[]{0};

        int[] parent = new int[numVertices];
        Arrays.fill(parent, -1);
        for (EdgeTuple t : edges) {
            if (t.isForward()) {
                int hi = Math.max(t.from, t.to);
                int lo = Math.min(t.from, t.to);
                parent[hi] = lo;
            }
        }

        // Walk from rightmost back to 0.
        int[] reversed = new int[numVertices];
        int len = 0;
        int v = numVertices - 1;
        reversed[len++] = v;
        while (v > 0) {
            v = parent[v];
            if (v < 0) {
                throw new IllegalStateException(
                    "DFS code is disconnected - vertex above " + reversed[len - 1]
                        + " has no parent forward edge");
            }
            reversed[len++] = v;
        }
        int[] path = new int[len];
        for (int i = 0; i < len; i++) {
            path[i] = reversed[len - 1 - i];
        }
        return path;
    }

    @Override
    public int compareTo(DFSCode o) {
        int n = Math.min(this.edges.length, o.edges.length);
        for (int i = 0; i < n; i++) {
            int c = this.edges[i].compareTo(o.edges[i]);
            if (c != 0) return c;
        }
        return Integer.compare(this.edges.length, o.edges.length);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DFSCode d)) return false;
        return Arrays.equals(this.edges, d.edges);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(edges);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DFSCode[");
        for (int i = 0; i < edges.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(edges[i]);
        }
        return sb.append(']').toString();
    }
}
