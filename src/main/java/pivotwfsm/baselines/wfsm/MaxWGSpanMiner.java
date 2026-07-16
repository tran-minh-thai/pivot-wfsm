package pivotwfsm.baselines.wfsm;

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
import java.util.TreeMap;

/**
 * MaxW-gSpan - the degraded variant of WFSM-MaxPWS described in Islam et al.
 * 2024 §4.2 (Eq. (11)). Identical to WFSM-MaxPWS except the PWS bound uses
 * <b>MaxW</b> (the maximum edge weight in the entire database) instead of
 * <b>FTW</b> (the first-tuple weight, which under Lemma 1 is the heaviest
 * canonical-extension weight).
 *
 * <p>Because MaxW &ge; FTW always, MaxW-gSpan's bound is at least as loose
 * as WFSM-MaxPWS's. The two algorithms emit the SAME pattern set; the
 * difference shows up as MaxW-gSpan exploring strictly more candidates.
 *
 * <p>The paper uses this baseline to isolate the contribution of Lemma 1
 * (weight-aware canonical ordering). Without Lemma 1, MaxPWS pruning still
 * works - just less tightly.
 */
public final class MaxWGSpanMiner {

    private MaxWGSpanMiner() {}

    public static List<WfsmMaxPwsMiner.WfsmResult> mine(MultiGraphDB db, double tauWsup) {
        return mine(db, tauWsup, null);
    }

    public static List<WfsmMaxPwsMiner.WfsmResult> mine(MultiGraphDB db, double tauWsup,
                                                         MiningStats stats) {
        WfsmMaxPwsMiner.validateStaticWeights(db);
        EdgeClass ec = EdgeClass.build(db);
        double maxW = databaseMaxEdgeWeight(db);

        List<WfsmMaxPwsMiner.WfsmResult> output = new ArrayList<>();
        List<Pattern> seeds = InitialPatterns.enumerate(db);
        seeds.sort(Comparator
            .comparingInt((Pattern p) -> p.code().edge(0).fromLabel)
            .thenComparingInt(p -> p.code().edge(0).toLabel));

        for (Pattern seed : seeds) {
            double W = patternAverageWeight(seed);
            int    sup = seed.support();
            double wsup = W * sup;

            OccurrenceList ol = occurrenceListOf(seed, ec);
            double maxPws = MaxPwsBound.maxPwsOver(
                seed.code().numEdges(), W, /*ftw=*/maxW,
                ol.edgeCounts(), ol.mpfArrayAscending());

            if (wsup >= tauWsup) {
                output.add(new WfsmMaxPwsMiner.WfsmResult(seed, W, sup, wsup));
                if (stats != null) stats.childPatternsEmitted++;
                expand(seed, db, ec, maxW, tauWsup, stats, output);
            } else if (maxPws >= tauWsup) {
                expand(seed, db, ec, maxW, tauWsup, stats, output);
            }
        }
        return output;
    }

    private static void expand(Pattern parent, MultiGraphDB db, EdgeClass ec,
                               double maxW, double tauWsup, MiningStats stats,
                               List<WfsmMaxPwsMiner.WfsmResult> output) {
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

            double W = patternAverageWeight(child);
            int sup = child.support();
            double wsup = W * sup;

            OccurrenceList ol = occurrenceListOf(child, ec);
            double maxPws = MaxPwsBound.maxPwsOver(
                child.code().numEdges(), W, /*ftw=*/maxW,
                ol.edgeCounts(), ol.mpfArrayAscending());

            if (wsup >= tauWsup) {
                output.add(new WfsmMaxPwsMiner.WfsmResult(child, W, sup, wsup));
                if (stats != null) stats.childPatternsEmitted++;
                expand(child, db, ec, maxW, tauWsup, stats, output);
            } else if (maxPws >= tauWsup) {
                expand(child, db, ec, maxW, tauWsup, stats, output);
            }
        }
    }

    private static double patternAverageWeight(Pattern p) {
        for (int gid : p.occursIn()) {
            List<Embedding> embs = p.embeddingsInGraph(gid);
            if (!embs.isEmpty()) return embs.get(0).wAvg();
        }
        return 0.0;
    }

    private static double databaseMaxEdgeWeight(MultiGraphDB db) {
        double m = 0.0;
        for (int gid = 0; gid < db.size(); gid++) {
            MultiGraph g = db.get(gid);
            for (int e = 0; e < g.numEdges(); e++) {
                double w = g.edgeWeight(e);
                if (w > m) m = w;
            }
        }
        return m;
    }

    private static OccurrenceList occurrenceListOf(Pattern p, EdgeClass ec) {
        Map<Integer, Integer> graphToPartition = new HashMap<>();
        for (var part : ec.partitionsAscending()) {
            for (int gid : part.graphIds) graphToPartition.put(gid, part.edgeCount);
        }
        TreeMap<Integer, List<Integer>> byPartition = new TreeMap<>();
        for (int gid : p.occursIn()) {
            int m = graphToPartition.getOrDefault(gid, -1);
            if (m < 0) continue;
            byPartition.computeIfAbsent(m, k -> new ArrayList<>()).add(gid);
        }
        List<OccurrenceList.OLM> olms = new ArrayList<>();
        for (var en : byPartition.entrySet()) {
            int[] ids = en.getValue().stream().mapToInt(Integer::intValue).toArray();
            olms.add(new OccurrenceList.OLM(en.getKey(), ids));
        }
        return new OccurrenceList(olms);
    }
}
