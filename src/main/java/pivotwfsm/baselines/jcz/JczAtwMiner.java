package pivotwfsm.baselines.jcz;

import pivotwfsm.core.DFSCode.EdgeTuple;
import pivotwfsm.core.Embedding;
import pivotwfsm.core.MinDFSCode;
import pivotwfsm.core.MultiGraph;
import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.core.Pattern;
import pivotwfsm.miner.InitialPatterns;
import pivotwfsm.miner.MiningStats;
import pivotwfsm.miner.RightMostExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Jiang–Coenen–Zito Average Total Weighting (ATW-gSpan)
 * (DaWaK 2010; also summarised in §2 of Islam et al., Appl. Intell.
 * 54(5):3756-3785, 2024; DOI 10.1007/s10489-024-05356-7).
 *
 * <p>The ATW weight of a subgraph {@code g} is
 *
 * <pre>
 *   ATW(g) = sum_{G in DB, g ⊆ G} Wavg(G)
 *            ---------------------------------
 *                  sum_{G in DB} Wavg(G)
 * </pre>
 *
 * where {@code Wavg(G)} is the average edge weight of host graph {@code G}.
 * A subgraph is frequent iff {@code ATW(g) >= tau}.
 *
 * <p>ATW is <em>not</em> anti-monotone (per the paper's §2 discussion), so
 * pruning is by transactional support only.
 * This adapter reuses the baseline search-tree machinery, swapping the
 * support measure for ATW.
 *
 * <p>This is the simplest of the three Jiang–Coenen–Zito schemes; AW
 * (Affinity Weighting) and UBW (Utility-Based Weighting) are out of scope
 * - they require additional metadata not present in TUDataset.
 */
public final class JczAtwMiner {

    /** One frequent pattern with its ATW weight. */
    public static final class JczResult {
        public final Pattern pattern;
        public final double atw;
        public JczResult(Pattern p, double atw) { this.pattern = p; this.atw = atw; }
    }

    private JczAtwMiner() {}

    /**
     * @param db        graph database (static-edge-weight assumed)
     * @param tau       minimum ATW value for emission
     * @param sigmaMin  minimum transactional support (anti-monotone) used to
     *                  prune the gSpan search tree. Without this, the
     *                  candidate space explodes on real TUDataset sizes
     *                  because ATW is not anti-monotone.
     */
    public static List<JczResult> mine(MultiGraphDB db, double tau, int sigmaMin) {
        return mine(db, tau, sigmaMin, null);
    }

    public static List<JczResult> mine(MultiGraphDB db, double tau, int sigmaMin, MiningStats stats) {
        double[] perGraphAvg = new double[db.size()];
        double dbWavgSum = 0.0;
        for (int gid = 0; gid < db.size(); gid++) {
            perGraphAvg[gid] = avgEdgeWeight(db.get(gid));
            dbWavgSum += perGraphAvg[gid];
        }
        if (dbWavgSum == 0.0) return List.of();

        List<JczResult> output = new ArrayList<>();
        List<Pattern> seeds = InitialPatterns.enumerate(db);
        seeds.sort(Comparator
            .comparingInt((Pattern p) -> p.code().edge(0).fromLabel)
            .thenComparingInt(p -> p.code().edge(0).toLabel));

        for (Pattern seed : seeds) {
            if (seed.support() < sigmaMin) continue;
            double atw = atwOf(seed, perGraphAvg, dbWavgSum);
            if (atw >= tau) {
                output.add(new JczResult(seed, atw));
                if (stats != null) stats.childPatternsEmitted++;
            }
            expand(seed, db, perGraphAvg, dbWavgSum, tau, sigmaMin, stats, output);
        }
        return output;
    }

    /** Legacy single-argument overload - assumes sigmaMin=1 (no support pruning). */
    public static List<JczResult> mine(MultiGraphDB db, double tau) {
        return mine(db, tau, /*sigmaMin=*/1, /*stats=*/null);
    }

    private static void expand(Pattern parent, MultiGraphDB db,
                               double[] perGraphAvg, double dbWavgSum,
                               double tau, int sigmaMin, MiningStats stats,
                               List<JczResult> output) {
        Map<EdgeTuple, Pattern> children = RightMostExtension.extend(parent, db);
        List<Map.Entry<EdgeTuple, Pattern>> entries = new ArrayList<>(children.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        for (Map.Entry<EdgeTuple, Pattern> en : entries) {
            Pattern child = en.getValue();
            if (stats != null) stats.childPatternsEnumerated++;
            if (!MinDFSCode.isMinimum(child.code())) {
                if (stats != null) stats.childPatternsRejectedNonCanonical++;
                continue;
            }
            if (child.support() < sigmaMin) continue;  // anti-monotone prune
            double atw = atwOf(child, perGraphAvg, dbWavgSum);
            if (atw >= tau) {
                output.add(new JczResult(child, atw));
                if (stats != null) stats.childPatternsEmitted++;
            }
            expand(child, db, perGraphAvg, dbWavgSum, tau, sigmaMin, stats, output);
        }
    }

    private static double atwOf(Pattern p, double[] perGraphAvg, double dbWavgSum) {
        double num = 0.0;
        for (int gid : p.occursIn()) num += perGraphAvg[gid];
        return num / dbWavgSum;
    }

    private static double avgEdgeWeight(MultiGraph g) {
        int m = g.numEdges();
        if (m == 0) return 0.0;
        double sum = 0.0;
        for (int e = 0; e < m; e++) sum += g.edgeWeight(e);
        return sum / m;
    }
}
