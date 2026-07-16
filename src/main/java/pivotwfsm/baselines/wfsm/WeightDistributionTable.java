package pivotwfsm.baselines.wfsm;

import pivotwfsm.core.MultiGraph;
import pivotwfsm.core.MultiGraphDB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Weight Distribution Table (WDT) used by DewgSpan
 * (Islam et al., Applied Intelligence 54(5):3756–3785, 2024, §5).
 *
 * <p>For dynamic-edge-weight databases the weight of an edge is not a
 * function of its label triple - each instance may differ. To still apply
 * the MaxPWS bound, DewgSpan precomputes, for each label triple, the
 * <b>sorted descending list of edge weights</b> seen across the database.
 * Given a partial pattern that needs to be extended by {@code k} more
 * edges of a given label triple, the bound uses the {@code k} largest
 * weights of that triple. This is tight under dynamic weights and
 * <em>reduces to FTW</em> when all weights of a given triple are equal -
 * which is the static-edge-weight case.
 *
 * <p>On static-weight inputs the WDT simply records one weight per triple
 * (all instances equal). DewgSpan's behaviour then coincides with
 * WFSM-MaxPWS for those inputs.
 */
public final class WeightDistributionTable {

    /** Key: packed {@code (loLabel << 32) | hiLabel}. */
    private final Map<Long, double[]> sortedDescending;
    private final double dbMaxWeight;

    private WeightDistributionTable(Map<Long, double[]> sortedDescending, double dbMaxWeight) {
        this.sortedDescending = sortedDescending;
        this.dbMaxWeight = dbMaxWeight;
    }

    public static WeightDistributionTable build(MultiGraphDB db) {
        Map<Long, List<Double>> buckets = new HashMap<>();
        double dbMax = 0.0;
        for (int gid = 0; gid < db.size(); gid++) {
            MultiGraph g = db.get(gid);
            for (int e = 0; e < g.numEdges(); e++) {
                int l1 = g.vertexLabel(g.edgeSrc(e));
                int l2 = g.vertexLabel(g.edgeDst(e));
                long key = ((long) Math.min(l1, l2) << 32) | (Math.max(l1, l2) & 0xFFFFFFFFL);
                double w = g.edgeWeight(e);
                buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
                if (w > dbMax) dbMax = w;
            }
        }
        Map<Long, double[]> sorted = new HashMap<>();
        for (var en : buckets.entrySet()) {
            List<Double> ws = en.getValue();
            Collections.sort(ws, Collections.reverseOrder());
            double[] arr = new double[ws.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = ws.get(i);
            sorted.put(en.getKey(), arr);
        }
        return new WeightDistributionTable(sorted, dbMax);
    }

    /**
     * Sum of the top-{@code k} edge weights across all label triples in the
     * database. Used as the dynamic-weight analog of "k extensions at FTW".
     *
     * <p>If multiple label triples have ties for "top-k", we walk through
     * the union of all per-triple sorted lists in descending order. This is
     * an upper bound (a generous one) appropriate for MaxPWS extension.
     */
    public double sumOfTopK(int k) {
        if (k <= 0) return 0.0;
        // Flatten all weights, sort descending, take k.
        List<Double> all = new ArrayList<>();
        for (double[] arr : sortedDescending.values()) {
            for (double w : arr) all.add(w);
        }
        all.sort(Collections.reverseOrder());
        double sum = 0.0;
        int take = Math.min(k, all.size());
        for (int i = 0; i < take; i++) sum += all.get(i);
        return sum;
    }

    /** Max edge weight in the database - paper's MaxW. */
    public double maxWeight() {
        return dbMaxWeight;
    }

    /** Heaviest weight for a specific label pair, or {@code 0} if pair unseen. */
    public double maxForPair(int labelA, int labelB) {
        long key = ((long) Math.min(labelA, labelB) << 32) | (Math.max(labelA, labelB) & 0xFFFFFFFFL);
        double[] arr = sortedDescending.get(key);
        return arr == null ? 0.0 : arr[0];
    }
}
