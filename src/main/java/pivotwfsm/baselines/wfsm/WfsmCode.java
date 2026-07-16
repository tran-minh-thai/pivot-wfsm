package pivotwfsm.baselines.wfsm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Weighted-extended DFS code for WFSM-MaxPWS (paper §4.1). An ordered list
 * of {@link WfsmTuple}s with the same structural-validity rules as gSpan's
 * DFS code, but with the Lemma 1 weight-aware comparison.
 *
 * <p>Structural validation mirrors {@link pivotwfsm.core.DFSCode}: the first
 * edge must be the forward edge (0, 1); subsequent forward edges introduce
 * exactly one new vertex (max so far + 1); backward edges only reference
 * already-discovered vertices; self-loops are rejected.
 *
 * <p>The "first tuple weight" (FTW) - used by MaxPWS as the heaviest
 * possible canonical extension weight - is simply {@code edge(0).weight}.
 */
public final class WfsmCode implements Comparable<WfsmCode> {

    private final WfsmTuple[] edges;
    private final int numVertices;

    private WfsmCode(WfsmTuple[] edges, int numVertices) {
        this.edges = edges;
        this.numVertices = numVertices;
    }

    public static WfsmCode of(List<WfsmTuple> edges) {
        Objects.requireNonNull(edges, "edges");
        WfsmTuple[] arr = edges.toArray(new WfsmTuple[0]);
        int seen = -1;
        for (int i = 0; i < arr.length; i++) {
            WfsmTuple t = arr[i];
            if (t.from == t.to) {
                throw new IllegalArgumentException(
                    "edge " + i + " " + t + " is a self-loop");
            }
            if (i == 0) {
                if (!t.isForward() || t.from != 0 || t.to != 1) {
                    throw new IllegalArgumentException(
                        "first edge " + t + " must be forward (0,1)");
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
                int hi = Math.max(t.from, t.to);
                if (hi > seen) {
                    throw new IllegalArgumentException(
                        "edge " + i + " " + t + " is backward but vertex " + hi
                            + " not yet discovered (seen up to " + seen + ")");
                }
            }
        }
        return new WfsmCode(arr, seen + 1);
    }

    public static WfsmCode of(WfsmTuple... edges) { return of(Arrays.asList(edges)); }

    public static WfsmCode empty() { return new WfsmCode(new WfsmTuple[0], 0); }

    public int numEdges() { return edges.length; }
    public int numVertices() { return numVertices; }
    public WfsmTuple edge(int i) { return edges[i]; }

    public List<WfsmTuple> edges() { return Collections.unmodifiableList(Arrays.asList(edges)); }

    /**
     * First Tuple Weight (FTW). Paper §4.2 names this as the heaviest possible
     * canonical extension weight. Returns 0 for the empty code.
     */
    public double firstTupleWeight() {
        return edges.length == 0 ? 0.0 : edges[0].weight;
    }

    /**
     * Average edge weight W(g). The denominator is the number of edges, not
     * the number of vertices.
     */
    public double averageWeight() {
        if (edges.length == 0) return 0.0;
        double sum = 0.0;
        for (WfsmTuple t : edges) sum += t.weight;
        return sum / edges.length;
    }

    public WfsmCode extend(WfsmTuple tuple) {
        List<WfsmTuple> next = new ArrayList<>(edges.length + 1);
        Collections.addAll(next, edges);
        next.add(tuple);
        return of(next);
    }

    @Override
    public int compareTo(WfsmCode o) {
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
        if (!(o instanceof WfsmCode c)) return false;
        return Arrays.equals(this.edges, c.edges);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(edges); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WfsmCode[");
        for (int i = 0; i < edges.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(edges[i]);
        }
        return sb.append(']').toString();
    }
}
