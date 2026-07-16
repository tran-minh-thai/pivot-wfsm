package pivotwfsm.baselines.wfsm;

import java.util.Objects;

/**
 * Extended edge tuple used by WFSM-MaxPWS (Islam et al., Appl. Intell.
 * 54(5):3756-3785, 2024; DOI 10.1007/s10489-024-05356-7).
 *
 * <p>The tuple format is {@code <from, to, fromLabel, toLabel, edgeLabel, weight>}.
 * The weight participates in the canonical ordering - that is the central
 * deviation from gSpan's tuple comparison and the source of the "tighter
 * MaxPWS" property (Lemma 1 of the paper).
 *
 * <p>Lemma 1 ordering rule, applied in order:
 * <ol>
 *   <li>(from, to) by the gSpan edge order {@code &lt;_e};</li>
 *   <li>if equal, the <b>heavier</b> weight is the <em>smaller</em> tuple
 *       (so the FIRST tuple of any canonical code has the heaviest weight);</li>
 *   <li>if still equal, lexicographic order on
 *       {@code (fromLabel, toLabel, edgeLabel)}.</li>
 * </ol>
 *
 * <p>Note the inversion in step (2): heavier weight ranks earlier. This is
 * what makes the "first tuple weight" (FTW) the heaviest possible canonical
 * extension weight (the proof of Lemma 1).
 */
public final class WfsmTuple implements Comparable<WfsmTuple> {

    public final int from;
    public final int to;
    public final int fromLabel;
    public final int toLabel;
    public final int edgeLabel;
    public final double weight;

    public WfsmTuple(int from, int to, int fromLabel, int toLabel, int edgeLabel, double weight) {
        this.from = from;
        this.to = to;
        this.fromLabel = fromLabel;
        this.toLabel = toLabel;
        this.edgeLabel = edgeLabel;
        this.weight = weight;
    }

    public boolean isForward() { return from < to; }
    public boolean isBackward() { return from > to; }

    /**
     * gSpan edge order &lt;_e (paper §4.1). Negative if this &lt;_e other.
     * The four cases match Yan &amp; Han (2002) extended to weighted edges.
     */
    public static int compareEdgePosition(WfsmTuple a, WfsmTuple b) {
        boolean af = a.isForward();
        boolean bf = b.isForward();
        if (af && bf) {
            // Both forward: smaller `to` first; tie-break: larger `from` first.
            int cmp = Integer.compare(a.to, b.to);
            if (cmp != 0) return cmp;
            return Integer.compare(b.from, a.from);          // larger `from` smaller
        }
        if (!af && !bf) {
            // Both backward: smaller `from` first; tie-break: smaller `to` first.
            int cmp = Integer.compare(a.from, b.from);
            if (cmp != 0) return cmp;
            return Integer.compare(a.to, b.to);
        }
        if (af /* a forward, b backward */) {
            // Forward to a node with smaller discovery time is smaller than
            // any backward edge from that node onward: a.to <= b.from → a smaller.
            return (a.to <= b.from) ? -1 : 1;
        }
        // a backward, b forward.
        return (a.from < b.to) ? -1 : 1;
    }

    /**
     * Full Lemma 1 comparison: edge-position first, then weight INVERTED
     * (heavier = smaller), then label trio lexicographically.
     */
    @Override
    public int compareTo(WfsmTuple o) {
        int cmp = compareEdgePosition(this, o);
        if (cmp != 0) return cmp;
        // Heavier weight is smaller - invert the natural double comparison.
        cmp = Double.compare(o.weight, this.weight);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(this.fromLabel, o.fromLabel);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(this.toLabel, o.toLabel);
        if (cmp != 0) return cmp;
        return Integer.compare(this.edgeLabel, o.edgeLabel);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WfsmTuple t)) return false;
        return from == t.from && to == t.to
            && fromLabel == t.fromLabel && toLabel == t.toLabel
            && edgeLabel == t.edgeLabel
            && Double.compare(weight, t.weight) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, fromLabel, toLabel, edgeLabel, weight);
    }

    @Override
    public String toString() {
        return "(" + from + "," + to + "," + fromLabel + "," + toLabel + ","
            + edgeLabel + "," + weight + ")";
    }
}
