package pivotwfsm.baselines.gspan;

import pivotwfsm.core.DFSCode.EdgeTuple;
import pivotwfsm.core.MinDFSCode;
import pivotwfsm.core.MultiGraphDB;
import pivotwfsm.core.Pattern;
import pivotwfsm.miner.InitialPatterns;
import pivotwfsm.miner.MiningStats;
import pivotwfsm.miner.RightMostExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Classic gSpan (Yan &amp; Han, ICDM 2002) - the unweighted reference
 * baseline. A pattern is frequent iff its transactional support meets
 * {@code sigmaMin}; weights play no role.
 *
 * <p>Two overloads: the simple {@link #mine(MultiGraphDB, int)} for callers
 * that don't care about counters, and the instrumented variant
 * {@link #mine(MultiGraphDB, int, MiningStats)} used by the
 * pruning-effectiveness comparison.
 */
public final class GSpanMiner {

    private GSpanMiner() {}

    public static List<Pattern> mine(MultiGraphDB db, int sigmaMin) {
        return mine(db, sigmaMin, null);
    }

    public static List<Pattern> mine(MultiGraphDB db, int sigmaMin, MiningStats stats) {
        Objects.requireNonNull(db, "db");
        List<Pattern> output = new ArrayList<>();
        List<Pattern> seeds = InitialPatterns.enumerate(db);
        seeds.sort(Comparator
            .comparingInt((Pattern p) -> p.code().edge(0).fromLabel)
            .thenComparingInt(p -> p.code().edge(0).toLabel));

        for (Pattern seed : seeds) {
            if (seed.support() < sigmaMin) continue;
            output.add(seed);
            if (stats != null) stats.childPatternsEmitted++;
            expand(seed, db, sigmaMin, stats, output);
        }
        return output;
    }

    private static void expand(Pattern parent, MultiGraphDB db, int sigmaMin,
                               MiningStats stats, List<Pattern> output) {
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
            if (child.support() < sigmaMin) continue;  // bound-pruned
            output.add(child);
            if (stats != null) stats.childPatternsEmitted++;
            expand(child, db, sigmaMin, stats, output);
        }
    }
}
