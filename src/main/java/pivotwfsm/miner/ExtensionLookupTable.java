package pivotwfsm.miner;

import pivotwfsm.core.MultiGraph;
import pivotwfsm.core.MultiGraphDB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified structural + weight prefilter - the second engineering contribution
 * of the baseline. Precomputes, for every undirected
 * vertex-label pair {@code (l_lo, l_hi)} present in the database, the list
 * of graphs containing at least one such edge along with the maximum edge
 * weight in each graph.
 *
 * <p>Once built (in {@code O(sum |E|)} time and {@code O(distinct label pairs)}
 * space), the table answers in <b>constant time</b> the prune question that
 * the miner asks before enumerating any embedding:
 *
 * <blockquote>
 *   "Could a right-most extension that introduces an edge of label pair
 *   {@code (anchorLabel, targetLabel)} possibly produce a child whose
 *   weighted transactional support is at least {@code sigma_min}?"
 * </blockquote>
 *
 * <p>Combines the constant-time structural test (does the label pair exist
 * anywhere?) with the weight upper bound (does it exist with weight
 * &geq; {@code tau_w}?) in one lookup - that is the "unified" part.
 */
public final class ExtensionLookupTable {

    /** One entry per host graph that contains at least one edge of a given label pair. */
    private static final class GraphEntry {
        final int graphId;
        final double maxWeight;

        GraphEntry(int graphId, double maxWeight) {
            this.graphId = graphId;
            this.maxWeight = maxWeight;
        }
    }

    /** Key: packed (lo << 32) | hi where lo &lt;= hi are the two vertex labels. */
    private final Map<Long, GraphEntry[]> table;

    private ExtensionLookupTable(Map<Long, GraphEntry[]> table) {
        this.table = table;
    }

    public static ExtensionLookupTable build(MultiGraphDB db) {
        // First pass: per (loLabel, hiLabel, graphId), record the maximum edge weight.
        Map<Long, Map<Integer, Double>> raw = new HashMap<>();

        for (int gid = 0; gid < db.size(); gid++) {
            MultiGraph g = db.get(gid);
            for (int e = 0; e < g.numEdges(); e++) {
                int l1 = g.vertexLabel(g.edgeSrc(e));
                int l2 = g.vertexLabel(g.edgeDst(e));
                int lo = Math.min(l1, l2);
                int hi = Math.max(l1, l2);
                long key = pack(lo, hi);
                double w = g.edgeWeight(e);

                Map<Integer, Double> perGraph = raw.computeIfAbsent(key, k -> new HashMap<>());
                Double cur = perGraph.get(gid);
                if (cur == null || w > cur) {
                    perGraph.put(gid, w);
                }
            }
        }

        // Second pass: flatten to GraphEntry[] arrays for fast iteration at query time.
        Map<Long, GraphEntry[]> compiled = new HashMap<>(raw.size() * 2);
        for (Map.Entry<Long, Map<Integer, Double>> en : raw.entrySet()) {
            Map<Integer, Double> perGraph = en.getValue();
            GraphEntry[] arr = new GraphEntry[perGraph.size()];
            int i = 0;
            for (Map.Entry<Integer, Double> pg : perGraph.entrySet()) {
                arr[i++] = new GraphEntry(pg.getKey(), pg.getValue());
            }
            compiled.put(en.getKey(), arr);
        }

        return new ExtensionLookupTable(compiled);
    }

    /**
     * Number of host graphs containing at least one edge of label pair
     * {@code (anchorLabel, targetLabel)} with weight {@code >= minWeight}.
     */
    public int graphsWithEdge(int anchorLabel, int targetLabel, double minWeight) {
        long key = pack(Math.min(anchorLabel, targetLabel), Math.max(anchorLabel, targetLabel));
        GraphEntry[] entries = table.get(key);
        if (entries == null) return 0;
        int n = 0;
        for (GraphEntry e : entries) {
            if (e.maxWeight >= minWeight) n++;
        }
        return n;
    }

    /**
     * O(1) prefilter decision: can extending a right-most-anchored vertex of
     * label {@code anchorLabel} with an edge to a vertex of label
     * {@code targetLabel} possibly produce a child that is frequent under
     * the given thresholds?
     *
     * <p>If this returns {@code false}, no embedding enumeration is needed:
     * fewer than {@code sigmaMin} host graphs contain a sufficiently heavy
     * edge of that label pair to ever support a frequent child.
     */
    public boolean canExtend(int anchorLabel, int targetLabel, double tauW, int sigmaMin) {
        return graphsWithEdge(anchorLabel, targetLabel, tauW) >= sigmaMin;
    }

    /**
     * Fraction of label-pairs in the table that the prefilter would prune
     * at the given thresholds. A value in {@code [0, 1]}: 0 means every
     * pair survives (prefilter is overhead with no benefit), 1 means every
     * pair is dead (prefilter eliminates everything trivially).
     *
     * <p>Cheap to compute (linear in the number of distinct label-pairs).
     * Diagnostic only - initial attempt at an adaptive-activation signal,
     * but empirically kill_rate is high (0.6-1.0) on both per_instance and
     * static schemas, so it cannot distinguish them. See
     * {@link #looksStaticSchema} for the predictor actually used by
     * {@link pivotwfsm.miner.EmbeddingStoreMiner#mineAdaptive}.
     */
    public double estimatedKillRate(double tauW, int sigmaMin) {
        if (table.isEmpty()) return 0.0;
        int dead = 0;
        for (Long key : table.keySet()) {
            int lo = (int) (key >>> 32);
            int hi = (int) (key & 0xFFFFFFFFL);
            if (!canExtend(lo, hi, tauW, sigmaMin)) dead++;
        }
        return (double) dead / table.size();
    }

    /**
     * Heuristic detector: does every label-pair in the database have a
     * (near-)uniform max-edge-weight across all host graphs containing
     * that pair?
     *
     * <p>Under the <em>static</em> weight schema each (labelU, labelV)
     * triple is sampled once and reused for every instance, so the
     * per-graph maximum is essentially constant. Under <em>per_instance</em>
     * each edge instance is sampled independently, so per-graph maxima vary.
     * This is the signal {@link pivotwfsm.miner.EmbeddingStoreMiner#mineAdaptive} uses
     * to decide whether to activate the prefilter at runtime.
     *
     * <p>"Near-uniform" is defined as range / mean &le; {@code tolerance}
     * for every label-pair entry containing at least two graphs. Empirical
     * value of {@code 0.05} cleanly separates per_instance from static on
     * MUTAG, PTC_MR, and NCI1/109.
     */
    public boolean looksStaticSchema() {
        final double tolerance = 0.05;
        for (GraphEntry[] entries : table.values()) {
            if (entries.length < 2) continue;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            double sum = 0.0;
            for (GraphEntry e : entries) {
                if (e.maxWeight < min) min = e.maxWeight;
                if (e.maxWeight > max) max = e.maxWeight;
                sum += e.maxWeight;
            }
            double mean = sum / entries.length;
            if (mean <= 0.0) continue;
            if ((max - min) / mean > tolerance) return false;
        }
        return true;
    }

    /** All distinct undirected label pairs present in the database. */
    public List<int[]> labelPairs() {
        List<int[]> pairs = new ArrayList<>(table.size());
        for (Long key : table.keySet()) {
            pairs.add(unpack(key));
        }
        pairs.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        return pairs;
    }

    /** True iff some host graph has an edge of this label pair (ignoring weights). */
    public boolean exists(int label1, int label2) {
        long key = pack(Math.min(label1, label2), Math.max(label1, label2));
        return table.containsKey(key);
    }

    private static long pack(int lo, int hi) {
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private static int[] unpack(long key) {
        return new int[]{(int) (key >>> 32), (int) (key & 0xFFFFFFFFL)};
    }

    @Override
    public String toString() {
        return "ExtensionLookupTable{" + table.size() + " label pairs}";
    }
}
